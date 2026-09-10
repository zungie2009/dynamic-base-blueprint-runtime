package org.blueprintruntime.rules;

import org.blueprintruntime.audit.AuditEvent;
import org.blueprintruntime.audit.AuditService;
import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.FieldDefinition;
import org.blueprintruntime.config.FieldType;
import org.blueprintruntime.config.TableDefinition;
import org.blueprintruntime.db.Database;
import org.blueprintruntime.json.JsonValue.JObject;
import org.blueprintruntime.runtime.BusinessContext;
import org.blueprintruntime.runtime.GenericCrudService;
import org.blueprintruntime.runtime.GenericRecord;
import org.blueprintruntime.runtime.RecordStore;
import org.blueprintruntime.runtime.ValueCodec;
import org.blueprintruntime.schema.SchemaProvisioner;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Implements {@code ruleAcceptanceExampleProtocol}: runs every declared acceptance
 * example for a candidate rule set against an isolated, throwaway in-memory H2 schema,
 * through the exact same {@link RuleEngine}/{@link GenericCrudService} production code
 * paths a live mutation uses, then rolls the schema back and discards it. Every
 * example must pass before {@code candidateActivation} may replace the active {@link
 * ActiveRuleSetSnapshot}.
 */
public final class AcceptanceExampleRunner {

    public record Failure(String exampleName, String ruleId, String message) {
    }

    public record Result(boolean passed, List<Failure> failures) {
    }

    private static final class AssertionFailure extends RuntimeException {
        final String exampleName;
        final String ruleId;

        AssertionFailure(String exampleName, String ruleId, String message) {
            super(message);
            this.exampleName = exampleName;
            this.ruleId = ruleId;
        }
    }

    public Result runAll(ResolvedBusinessBlueprint blueprint, BusinessRuleSet candidate) {
        List<Failure> failures = new ArrayList<>();
        ActiveRuleSetSnapshot snapshot;
        try {
            snapshot = ActiveRuleSetSnapshot.activate(candidate, "candidate");
        } catch (RuntimeException e) {
            failures.add(new Failure(null, null, "Rule dependency graph is invalid: " + e.getMessage()));
            return new Result(false, failures);
        }

        for (JObject example : candidate.acceptanceExamples()) {
            String exampleName = example.getString("name", "(unnamed example)");
            try {
                runOne(blueprint, snapshot, example);
            } catch (AssertionFailure af) {
                failures.add(new Failure(af.exampleName, af.ruleId, af.getMessage()));
            } catch (SQLException | RuntimeException e) {
                failures.add(new Failure(exampleName, null, "Unexpected error: " + e));
            }
        }
        return new Result(failures.isEmpty(), failures);
    }

    private void runOne(ResolvedBusinessBlueprint blueprint, ActiveRuleSetSnapshot snapshot, JObject example) throws SQLException {
        String exampleName = example.getString("name", "(unnamed example)");
        Database db = Database.inMemory("acceptance_" + UUID.randomUUID().toString().replace("-", ""));
        BusinessContext exampleContext = new BusinessContext(blueprint, db, null, null, null, null);
        exampleContext.setActiveRuleSet(snapshot);
        RecordStore recordStore = exampleContext.recordStore();
        AuditService auditService = exampleContext.auditService();
        RuleEngine ruleEngine = exampleContext.ruleEngine();
        GenericCrudService crud = exampleContext.crudService();

        try (Connection connection = db.getConnection()) {
            connection.setAutoCommit(false);
            new SchemaProvisioner().provision(connection, blueprint);
            auditService.ensureTable(connection);
            connection.commit();

            loadFixtures(connection, recordStore, blueprint.configuration(), example.getObject("fixture").getObject("records"));

            String stimulusMode = example.getString("stimulusMode");
            String transactionId = UUID.randomUUID().toString();
            String correlationId = transactionId;
            Map<String, GenericRecord> overrides = new LinkedHashMap<>();

            if ("DIRECT_EVENT".equals(stimulusMode)) {
                dispatchDirectEvent(connection, blueprint, snapshot, ruleEngine, example.getObject("event"), transactionId, correlationId, overrides);
            } else if ("FULL_MUTATION_PIPELINE".equals(stimulusMode)) {
                dispatchMutationPipeline(connection, blueprint, crud, example.getObject("mutation"), transactionId, correlationId);
            } else {
                throw new AssertionFailure(exampleName, null, "Unsupported stimulusMode '" + stimulusMode + "'");
            }

            JObject expect = example.getObject("expect");
            if (expect.has("records")) {
                assertRecords(exampleName, connection, blueprint, recordStore, overrides, expect.getObject("records"));
            }
            if (expect.has("absentRecords")) {
                assertAbsentRecords(exampleName, connection, recordStore, overrides, expect.getObject("absentRecords"));
            }
            if (expect.has("audit")) {
                assertAudit(exampleName, connection, auditService, correlationId, expect.getObject("audit"));
            }

            connection.rollback(); // destroy the isolated schema's data; the in-memory database itself is discarded on close.
        }
    }

    // --------------------------------------------------------------- fixtures

    private void loadFixtures(Connection connection, RecordStore recordStore, BusinessConfiguration config, JObject fixtureRecords) throws SQLException {
        Set<String> tablesInFixture = new LinkedHashSet<>(fixtureRecords.keysSorted());
        for (String tableId : insertionOrder(config, tablesInFixture)) {
            TableDefinition table = config.requireTable(tableId);
            for (JObject json : fixtureRecords.getArray(tableId).asObjectList()) {
                recordStore.insert(connection, parseRecord(table, json));
            }
        }
    }

    private List<String> insertionOrder(BusinessConfiguration config, Set<String> tablesInFixture) {
        Map<String, Set<String>> dependsOn = new LinkedHashMap<>();
        for (String tableId : tablesInFixture) {
            Set<String> deps = new HashSet<>();
            for (FieldDefinition field : config.requireTable(tableId).fields()) {
                if (field.type() == FieldType.REFERENCE
                        && tablesInFixture.contains(field.reference().tableId())
                        && !field.reference().tableId().equals(tableId)) {
                    deps.add(field.reference().tableId());
                }
            }
            dependsOn.put(tableId, deps);
        }
        List<String> order = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(tablesInFixture);
        while (!remaining.isEmpty()) {
            String next = remaining.stream()
                    .filter(t -> order.containsAll(dependsOn.get(t)))
                    .findFirst()
                    .orElse(remaining.iterator().next()); // undeclared cycle: break arbitrarily, matching the schema's own cycle tolerance
            order.add(next);
            remaining.remove(next);
        }
        return order;
    }

    private GenericRecord parseRecord(TableDefinition table, JObject json) {
        GenericRecord record = GenericRecord.empty(table.tableId());
        for (String key : json.keysSorted()) {
            table.field(key).ifPresent(field -> record.set(key, ValueCodec.parseJsonValue(field, json.get(key))));
        }
        return record;
    }

    // ---------------------------------------------------------------- stimulus

    private void dispatchDirectEvent(Connection connection, ResolvedBusinessBlueprint blueprint, ActiveRuleSetSnapshot snapshot,
                                      RuleEngine ruleEngine, JObject eventObj, String transactionId, String correlationId,
                                      Map<String, GenericRecord> overrides) throws SQLException {
        String eventType = eventObj.getString("type");
        String table = eventObj.getString("table");
        TableDefinition tableDef = blueprint.configuration().requireTable(table);

        GenericRecord recordBefore = eventObj.has("recordBefore") ? parseRecord(tableDef, eventObj.getObject("recordBefore")) : null;
        GenericRecord recordAfter = eventObj.has("recordAfter") ? parseRecord(tableDef, eventObj.getObject("recordAfter")) : null;

        if (eventType.startsWith("BEFORE_")) {
            ruleEngine.applyBeforeRules(blueprint, snapshot, connection, eventType, recordAfter);
            FieldDefinition pk = tableDef.requirePrimaryKeyField();
            Object id = recordAfter.get(pk.fieldId());
            if (id != null) overrides.put(table + "|" + id, recordAfter);
        } else {
            ruleEngine.propagateAfter(connection, blueprint, snapshot, MutationEvent.origin(eventType, table, recordBefore, recordAfter),
                    transactionId, correlationId, null);
        }
    }

    private void dispatchMutationPipeline(Connection connection, ResolvedBusinessBlueprint blueprint, GenericCrudService crud,
                                           JObject mutation, String transactionId, String correlationId) throws SQLException {
        String operation = mutation.getString("operation");
        String table = mutation.getString("table");
        TableDefinition tableDef = blueprint.configuration().requireTable(table);

        switch (operation) {
            case "CREATE" -> crud.create(connection, table, parseTypedValues(tableDef, mutation.getObject("submittedValues")),
                    "USER", transactionId, correlationId);
            case "UPDATE" -> crud.update(connection, table, mutation.getString("recordId"),
                    parseTypedValues(tableDef, mutation.getObject("submittedValues")), "USER", transactionId, correlationId);
            case "DELETE" -> crud.delete(connection, table, mutation.getString("recordId"), "USER", transactionId, correlationId);
            default -> throw new AssertionFailure(null, null, "Unsupported mutation.operation '" + operation + "'");
        }
    }

    private Map<String, Object> parseTypedValues(TableDefinition table, JObject submittedValues) {
        Map<String, Object> typed = new LinkedHashMap<>();
        for (String key : submittedValues.keysSorted()) {
            FieldDefinition field = table.requireField(key);
            typed.put(key, ValueCodec.parseJsonValue(field, submittedValues.get(key)));
        }
        return typed;
    }

    // --------------------------------------------------------------- assertions

    private void assertRecords(String exampleName, Connection connection, ResolvedBusinessBlueprint blueprint, RecordStore recordStore,
                                Map<String, GenericRecord> overrides, JObject expectRecords) throws SQLException {
        for (String tableId : expectRecords.keysSorted()) {
            TableDefinition table = blueprint.configuration().requireTable(tableId);
            FieldDefinition pk = table.requirePrimaryKeyField();
            for (JObject fragment : expectRecords.getArray(tableId).asObjectList()) {
                String id = fragment.getString(pk.fieldId());
                GenericRecord actual = overrides.get(tableId + "|" + id);
                if (actual == null) {
                    actual = recordStore.find(connection, tableId, id)
                            .orElseThrow(() -> new AssertionFailure(exampleName, null, "expected record " + tableId + "/" + id + " to exist but it does not"));
                }
                for (String fieldId : fragment.keysSorted()) {
                    if (fieldId.equals(pk.fieldId())) continue;
                    FieldDefinition field = table.requireField(fieldId);
                    Object expectedValue = ValueCodec.parseJsonValue(field, fragment.get(fieldId));
                    Object actualValue = actual.get(fieldId);
                    if (!ValueCodec.equalsCanonical(field, expectedValue, actualValue)) {
                        throw new AssertionFailure(exampleName, null, tableId + "/" + id + "." + fieldId
                                + " expected " + expectedValue + " but was " + actualValue);
                    }
                }
            }
        }
    }

    private void assertAbsentRecords(String exampleName, Connection connection, RecordStore recordStore,
                                      Map<String, GenericRecord> overrides, JObject absentRecords) throws SQLException {
        for (String tableId : absentRecords.keysSorted()) {
            for (String id : absentRecords.getArray(tableId).asStringList()) {
                if (overrides.containsKey(tableId + "|" + id) || recordStore.find(connection, tableId, id).isPresent()) {
                    throw new AssertionFailure(exampleName, null, "expected record " + tableId + "/" + id + " to be absent but it still exists");
                }
            }
        }
    }

    private void assertAudit(String exampleName, Connection connection, AuditService auditService, String correlationId, JObject auditExpect) throws SQLException {
        List<AuditEvent> events = auditService.findByCorrelation(connection, correlationId);

        if (auditExpect.has("eventCounts")) {
            JObject eventCounts = auditExpect.getObject("eventCounts");
            for (String eventType : eventCounts.keysSorted()) {
                int expected = eventCounts.getInt(eventType, 0);
                long actual = events.stream().filter(e -> e.eventType().equals(eventType)).count();
                if (actual != expected) {
                    throw new AssertionFailure(exampleName, null, "audit.eventCounts." + eventType + " expected " + expected + " but was " + actual);
                }
            }
        }
        if (auditExpect.has("ruleEvaluationCounts")) {
            JObject ruleCounts = auditExpect.getObject("ruleEvaluationCounts");
            for (String ruleId : ruleCounts.keysSorted()) {
                int expected = ruleCounts.getInt(ruleId, 0);
                long actual = events.stream().filter(e -> "RULE_EVALUATED".equals(e.eventType()) && ruleId.equals(e.ruleId())).count();
                if (actual != expected) {
                    throw new AssertionFailure(exampleName, ruleId, "audit.ruleEvaluationCounts." + ruleId + " expected " + expected + " but was " + actual);
                }
            }
        }
        if (auditExpect.has("requiredRows")) {
            for (JObject requirement : auditExpect.getArray("requiredRows").asObjectList()) {
                boolean matched = events.stream().anyMatch(e -> matchesRequirement(e, requirement));
                if (!matched) {
                    throw new AssertionFailure(exampleName, requirement.getString("ruleId", null),
                            "no audit row matched required row " + requirement.toCanonicalJson());
                }
            }
        }
        if (auditExpect.getBool("sharedTransaction", false)) {
            long distinctTransactions = events.stream().map(AuditEvent::transactionId).distinct().count();
            if (distinctTransactions > 1) throw new AssertionFailure(exampleName, null, "audit rows do not share one transactionId");
        }
        if (auditExpect.getBool("sharedCorrelation", false)) {
            long distinctCorrelations = events.stream().map(AuditEvent::correlationId).distinct().count();
            if (distinctCorrelations > 1) throw new AssertionFailure(exampleName, null, "audit rows do not share one correlationId");
        }
        if ("VALID".equals(auditExpect.getString("causationLineage", null))) {
            Set<String> ids = new HashSet<>();
            for (AuditEvent e : events) ids.add(e.eventId());
            for (AuditEvent e : events) {
                if (e.causationEventId() != null && !ids.contains(e.causationEventId())) {
                    throw new AssertionFailure(exampleName, null, "event " + e.eventId() + " (" + e.eventType()
                            + ") has a causationEventId that does not point to another audit row in this transaction");
                }
            }
        }
    }

    private boolean matchesRequirement(AuditEvent e, JObject requirement) {
        if (requirement.has("eventType") && !requirement.getString("eventType").equals(e.eventType())) return false;
        if (requirement.has("origin") && !requirement.getString("origin").equals(e.origin())) return false;
        if (requirement.has("tableId") && !requirement.getString("tableId").equals(e.tableId())) return false;
        if (requirement.has("recordId") && !requirement.getString("recordId").equals(e.recordId())) return false;
        if (requirement.has("ruleId") && !requirement.getString("ruleId").equals(e.ruleId())) return false;
        if (requirement.has("outcome") && !requirement.getString("outcome").equals(e.outcome())) return false;
        return true;
    }
}
