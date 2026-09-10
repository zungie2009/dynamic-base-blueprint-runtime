package org.blueprintruntime.runtime;

import org.blueprintruntime.audit.AuditEvent;
import org.blueprintruntime.audit.AuditService;
import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.FieldDefinition;
import org.blueprintruntime.config.FieldType;
import org.blueprintruntime.config.TableDefinition;
import org.blueprintruntime.rules.ActiveRuleSetSnapshot;
import org.blueprintruntime.rules.MutationEvent;
import org.blueprintruntime.rules.RuleEngine;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The one generic CRUD implementation every configured table shares, per {@code
 * genericCrudContract}: no per-table Java class exists anywhere in this codebase.
 * Owns the transaction boundary for every mutation — the originating write, its
 * BEFORE/AFTER rule evaluations, every rule-driven cascade write, and every audit row
 * they produce all commit or roll back together, per {@code
 * cascadingMutationAuditPolicy.transactionBoundary}.
 */
public final class GenericCrudService {

    private final ResolvedBusinessBlueprint blueprint;
    private final RecordStore recordStore;
    private final RecordValidator recordValidator;
    private final RuleEngine ruleEngine;
    private final AuditService auditService;
    private final BusinessContext context;

    public GenericCrudService(ResolvedBusinessBlueprint blueprint, RecordStore recordStore, RecordValidator recordValidator,
                               RuleEngine ruleEngine, AuditService auditService, BusinessContext context) {
        this.blueprint = blueprint;
        this.recordStore = recordStore;
        this.recordValidator = recordValidator;
        this.ruleEngine = ruleEngine;
        this.auditService = auditService;
        this.context = context;
    }

    // ---------------------------------------------------------------- reads

    public List<GenericRecord> list(String tableId) throws SQLException {
        try (Connection connection = context.database().getConnection()) {
            return recordStore.findAll(connection, tableId);
        }
    }

    public Optional<GenericRecord> view(String tableId, String recordId) throws SQLException {
        try (Connection connection = context.database().getConnection()) {
            return recordStore.find(connection, tableId, recordId);
        }
    }

    // ------------------------------------------------------- HTTP-facing writes

    /** Parses an HTML form submission, then runs the full CREATE pipeline in one transaction. */
    public GenericRecord createFromForm(String tableId, Map<String, String> formValues) throws SQLException {
        TableDefinition table = blueprint.configuration().requireTable(tableId);
        Map<String, Object> typed = parseSubmission(table, formValues, true);
        String requestId = UUID.randomUUID().toString();
        try (Connection connection = context.database().getConnection()) {
            connection.setAutoCommit(false);
            try {
                GenericRecord created = create(connection, tableId, typed, "USER", requestId, requestId);
                connection.commit();
                return created;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /** Parses an HTML form submission, then runs the full UPDATE pipeline in one transaction. */
    public GenericRecord updateFromForm(String tableId, String recordId, Map<String, String> formValues) throws SQLException {
        TableDefinition table = blueprint.configuration().requireTable(tableId);
        Map<String, Object> typed = parseSubmission(table, formValues, false);
        String requestId = UUID.randomUUID().toString();
        try (Connection connection = context.database().getConnection()) {
            connection.setAutoCommit(false);
            try {
                GenericRecord updated = update(connection, tableId, recordId, typed, "USER", requestId, requestId);
                connection.commit();
                return updated;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void deleteFromRoute(String tableId, String recordId) throws SQLException {
        String requestId = UUID.randomUUID().toString();
        try (Connection connection = context.database().getConnection()) {
            connection.setAutoCommit(false);
            try {
                delete(connection, tableId, recordId, "USER", requestId, requestId);
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    // ------------------------------------------------------- transactional core

    /** Full CREATE pipeline against an already-open transaction: identity generation, defaults, BEFORE_CREATE rules, validation, insert, audit, AFTER_CREATE propagation. */
    public GenericRecord create(Connection connection, String tableId, Map<String, Object> submittedTyped,
                                 String origin, String transactionId, String correlationId) throws SQLException {
        TableDefinition table = blueprint.configuration().requireTable(tableId);
        FieldDefinition pk = table.requirePrimaryKeyField();

        GenericRecord candidate = GenericRecord.empty(tableId);
        candidate.set(pk.fieldId(), UUID.randomUUID().toString());
        for (FieldDefinition field : table.fields()) {
            if (field.type() == FieldType.IDENTITY) continue;
            Object value = submittedTyped.get(field.fieldId());
            if (value == null && field.defaultValue() != null && !submittedTyped.containsKey(field.fieldId())) {
                value = ValueCodec.parseFormValue(field, field.defaultValue());
            }
            candidate.set(field.fieldId(), value);
        }

        ActiveRuleSetSnapshot snapshot = context.activeRuleSet();
        List<RuleEngine.Evaluation> beforeEvaluations =
                ruleEngine.applyBeforeRules(blueprint, snapshot, connection, "BEFORE_CREATE", candidate);

        recordValidator.validateForCreate(connection, blueprint, table, candidate);
        recordStore.insert(connection, candidate);

        String recordId = candidate.get(pk.fieldId()).toString();
        AuditEvent createdEvent = auditService.write(connection, AuditEvent.draft(
                blueprint.businessId(), blueprint.schemaVersion(), "RECORD_CREATED", tableId, recordId,
                null, null, null, transactionId, correlationId, null, origin, null, null));

        auditBeforeEvaluations(connection, snapshot, tableId, recordId, transactionId, correlationId, createdEvent.eventId(), beforeEvaluations);

        if (snapshot != null) {
            ruleEngine.propagateAfter(connection, blueprint, snapshot,
                    MutationEvent.origin("AFTER_CREATE", tableId, null, candidate),
                    transactionId, correlationId, createdEvent.eventId());
        }
        return candidate;
    }

    /** Full UPDATE pipeline against an already-open transaction: merge, BEFORE_UPDATE rules, validation, coalesced update, audit, AFTER_UPDATE propagation. */
    public GenericRecord update(Connection connection, String tableId, String recordId, Map<String, Object> submittedTyped,
                                 String origin, String transactionId, String correlationId) throws SQLException {
        TableDefinition table = blueprint.configuration().requireTable(tableId);
        GenericRecord existing = recordStore.find(connection, tableId, recordId)
                .orElseThrow(() -> new NoSuchRecordException(tableId, recordId));
        GenericRecord candidate = existing.copy();
        submittedTyped.forEach(candidate::set);

        ActiveRuleSetSnapshot snapshot = context.activeRuleSet();
        List<RuleEngine.Evaluation> beforeEvaluations =
                ruleEngine.applyBeforeRules(blueprint, snapshot, connection, "BEFORE_UPDATE", candidate);

        recordValidator.validateForUpdate(connection, blueprint, table, recordId, candidate);

        Map<String, Object> changes = new LinkedHashMap<>();
        for (FieldDefinition field : table.fields()) {
            if (field.type() == FieldType.IDENTITY) continue;
            if (!ValueCodec.equalsCanonical(field, existing.get(field.fieldId()), candidate.get(field.fieldId()))) {
                changes.put(field.fieldId(), candidate.get(field.fieldId()));
            }
        }
        if (changes.isEmpty()) {
            return existing; // nothing changed: no update, no audit row, per cascadingMutationAuditPolicy
        }

        recordStore.update(connection, tableId, recordId, changes);
        AuditEvent updatedEvent = auditService.write(connection, AuditEvent.draft(
                blueprint.businessId(), blueprint.schemaVersion(), "RECORD_UPDATED", tableId, recordId,
                null, null, null, transactionId, correlationId, null, origin, null, null));

        auditBeforeEvaluations(connection, snapshot, tableId, recordId, transactionId, correlationId, updatedEvent.eventId(), beforeEvaluations);

        if (snapshot != null) {
            ruleEngine.propagateAfter(connection, blueprint, snapshot,
                    MutationEvent.origin("AFTER_UPDATE", tableId, existing, candidate),
                    transactionId, correlationId, updatedEvent.eventId());
        }
        return candidate;
    }

    /** Full DELETE pipeline: capture pre-delete state (so AFTER_DELETE relationship rules can resolve the former parent), delete, translate FK RESTRICT violations, audit, AFTER_DELETE propagation. */
    public void delete(Connection connection, String tableId, String recordId,
                        String origin, String transactionId, String correlationId) throws SQLException {
        GenericRecord existing = recordStore.find(connection, tableId, recordId)
                .orElseThrow(() -> new NoSuchRecordException(tableId, recordId));
        try {
            recordStore.delete(connection, tableId, recordId);
        } catch (SQLException e) {
            if (isIntegrityConstraintViolation(e)) {
                throw new ForeignKeyRestrictionException(tableId, recordId);
            }
            throw e;
        }

        AuditEvent deletedEvent = auditService.write(connection, AuditEvent.draft(
                blueprint.businessId(), blueprint.schemaVersion(), "RECORD_DELETED", tableId, recordId,
                null, null, null, transactionId, correlationId, null, origin, null, null));

        ActiveRuleSetSnapshot snapshot = context.activeRuleSet();
        if (snapshot != null) {
            ruleEngine.propagateAfter(connection, blueprint, snapshot,
                    MutationEvent.origin("AFTER_DELETE", tableId, existing, null),
                    transactionId, correlationId, deletedEvent.eventId());
        }
    }

    // ------------------------------------------------------------- helpers

    private void auditBeforeEvaluations(Connection connection, ActiveRuleSetSnapshot snapshot, String tableId, String recordId,
                                         String transactionId, String correlationId, String causationEventId,
                                         List<RuleEngine.Evaluation> evaluations) throws SQLException {
        if (snapshot == null || evaluations.isEmpty()) return;
        for (RuleEngine.Evaluation evaluation : evaluations) {
            auditService.write(connection, AuditEvent.draft(
                    blueprint.businessId(), blueprint.schemaVersion(), "RULE_EVALUATED", tableId, recordId,
                    snapshot.ruleSet().ruleSetId(), snapshot.ruleSet().version(), evaluation.ruleId(),
                    transactionId, correlationId, causationEventId, "RULE", evaluation.outcome(), null));
        }
    }

    private static boolean isIntegrityConstraintViolation(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }

    /** Parses raw HTML form strings into typed values, enforcing {@code unknownInputRule} and the editable/generated boundary. */
    private Map<String, Object> parseSubmission(TableDefinition table, Map<String, String> formValues, boolean isCreate) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (String submittedField : formValues.keySet()) {
            if (table.field(submittedField).isEmpty()) {
                errors.put(submittedField, "Unknown field '" + submittedField + "'");
            }
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        for (FieldDefinition field : table.fields()) {
            if (field.type() == FieldType.IDENTITY) continue; // server-generated, never accepted from submission
            if (!formValues.containsKey(field.fieldId())) continue;
            if (!field.editable()) {
                errors.put(field.fieldId(), field.label() + " cannot be set directly");
                continue;
            }
            try {
                typed.put(field.fieldId(), ValueCodec.parseFormValue(field, formValues.get(field.fieldId())));
            } catch (RuntimeException ex) {
                errors.put(field.fieldId(), "Invalid value for " + field.label());
            }
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return typed;
    }
}
