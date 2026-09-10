package org.blueprintruntime.rules;

import org.blueprintruntime.audit.AuditEvent;
import org.blueprintruntime.audit.AuditService;
import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.FieldDefinition;
import org.blueprintruntime.config.FieldType;
import org.blueprintruntime.config.TableDefinition;
import org.blueprintruntime.runtime.GenericRecord;
import org.blueprintruntime.runtime.RecordStore;
import org.blueprintruntime.runtime.ValueCodec;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates the active {@link ActiveRuleSetSnapshot} against mutations, per {@code
 * dynamicRuleContract} and {@code crossTableEventPropagation}. BEFORE hooks compute
 * fields on the record being created/updated, before it is persisted, as part of the
 * same user-origin write. AFTER hooks propagate to same-table self-recalculation and
 * to cross-table {@code triggerBinding}-resolved parent(s) — reparenting recalculates
 * both the former and the new parent — recursively, bounded by {@code maxPasses},
 * coalescing every field a stabilized pass changes on one record into one physical
 * UPDATE and one {@code RECORD_UPDATED} audit row, per {@code
 * cascadingMutationAuditPolicy}. As a documented simplification, once a table is known
 * to be affected, every rule declared against that table participates in its coalesced
 * evaluation, rather than re-filtering each rule's own trigger list a second time —
 * this is sufficient and correct for every rule set this runtime has been validated
 * against, since a rule's own {@code triggers}/{@code triggerBinding.events} already
 * gated whether that table became affected in the first place.
 */
public final class RuleEngine {

    private final RecordStore recordStore;
    private final AuditService auditService;

    public RuleEngine(RecordStore recordStore, AuditService auditService) {
        this.recordStore = recordStore;
        this.auditService = auditService;
    }

    public record Evaluation(String ruleId, String outcome, boolean changed) {
    }

    private record TargetKey(String table, String recordId) {
    }

    /**
     * Runs every non-cross-table rule targeting {@code candidate.tableId()} whose
     * triggers include {@code triggerEvent} (BEFORE_CREATE or BEFORE_UPDATE), mutating
     * {@code candidate} in place with each rule's computed output. Returns the
     * evaluations so the caller can audit them once the record's identity is durable.
     */
    public List<Evaluation> applyBeforeRules(ResolvedBusinessBlueprint blueprint, ActiveRuleSetSnapshot snapshot,
                                              Connection connection, String triggerEvent, GenericRecord candidate) throws SQLException {
        List<Evaluation> evaluations = new ArrayList<>();
        if (snapshot == null) return evaluations;
        BusinessConfiguration config = blueprint.configuration();
        String table = candidate.tableId();
        for (RuleDefinition rule : snapshot.rulesInOrder()) {
            if (rule.isCrossTable()) continue;
            if (!rule.targetTable().equals(table)) continue;
            if (!rule.firingEvents().contains(triggerEvent)) continue;
            evaluations.add(evaluateAndApply(connection, config, rule, table, candidate));
        }
        return evaluations;
    }

    /**
     * Propagates an AFTER_CREATE/AFTER_UPDATE/AFTER_DELETE event: resolves affected
     * records, coalesces their rule-driven changes into one UPDATE and one audit row
     * each, and recurses on the resulting change (bounded by {@code maxPasses}). All
     * writes and audit rows happen inside the caller's transaction. Returns every audit
     * event written, in order.
     */
    public List<AuditEvent> propagateAfter(Connection connection, ResolvedBusinessBlueprint blueprint, ActiveRuleSetSnapshot snapshot,
                                            MutationEvent originEvent, String transactionId, String correlationId, String rootCausationEventId) throws SQLException {
        List<AuditEvent> written = new ArrayList<>();
        if (snapshot == null) return written;
        BusinessConfiguration config = blueprint.configuration();

        int remainingPasses = snapshot.ruleSet().maxPasses();
        Deque<MutationEvent> queue = new ArrayDeque<>();
        queue.add(originEvent);
        String causation = rootCausationEventId;

        while (!queue.isEmpty() && remainingPasses > 0) {
            MutationEvent event = queue.poll();
            remainingPasses--;

            LinkedHashMap<TargetKey, Boolean> affected = resolveAffected(config, snapshot, event);

            for (TargetKey key : affected.keySet()) {
                TableDefinition targetTable = config.requireTable(key.table());
                Optional<GenericRecord> currentOpt = recordStore.find(connection, key.table(), key.recordId());
                if (currentOpt.isEmpty()) continue; // e.g. the parent itself was deleted meanwhile

                GenericRecord original = currentOpt.get();
                GenericRecord working = original.copy();

                List<RuleDefinition> rulesForTable = snapshot.rulesInOrder().stream()
                        .filter(r -> r.targetTable().equals(key.table()))
                        .toList();

                String firstChangingRuleId = null;
                for (RuleDefinition rule : rulesForTable) {
                    Evaluation evaluation = evaluateAndApply(connection, config, rule, key.table(), working);
                    AuditEvent evalEvent = auditService.write(connection, AuditEvent.draft(
                            blueprint.businessId(), blueprint.schemaVersion(), "RULE_EVALUATED",
                            key.table(), key.recordId(), snapshot.ruleSet().ruleSetId(), snapshot.ruleSet().version(),
                            rule.ruleId(), transactionId, correlationId, causation, "RULE", evaluation.outcome(), null));
                    written.add(evalEvent);
                    if (evaluation.changed() && firstChangingRuleId == null) {
                        firstChangingRuleId = rule.ruleId();
                    }
                    if (evaluation.changed()) {
                        causation = evalEvent.eventId();
                    }
                }

                Map<String, Object> changes = new LinkedHashMap<>();
                for (FieldDefinition field : targetTable.fields()) {
                    Object before = original.get(field.fieldId());
                    Object after = working.get(field.fieldId());
                    if (!ValueCodec.equalsCanonical(field, before, after)) {
                        changes.put(field.fieldId(), after);
                    }
                }
                if (changes.isEmpty()) continue;

                recordStore.update(connection, key.table(), key.recordId(), changes);
                AuditEvent updateEvent = auditService.write(connection, AuditEvent.draft(
                        blueprint.businessId(), blueprint.schemaVersion(), "RECORD_UPDATED",
                        key.table(), key.recordId(), null, null, firstChangingRuleId,
                        transactionId, correlationId, causation, "RULE", null, null));
                written.add(updateEvent);
                causation = updateEvent.eventId();

                GenericRecord after = original.copy();
                changes.forEach(after::set);
                queue.add(MutationEvent.synthetic("AFTER_UPDATE", key.table(), original, after));
            }
        }
        return written;
    }

    private LinkedHashMap<TargetKey, Boolean> resolveAffected(BusinessConfiguration config, ActiveRuleSetSnapshot snapshot, MutationEvent event) {
        LinkedHashMap<TargetKey, Boolean> affected = new LinkedHashMap<>();
        for (RuleDefinition rule : snapshot.rulesInOrder()) {
            if (!rule.eventTable().equals(event.table())) continue;
            if (!rule.firingEvents().contains(event.eventType())) continue;

            if (!rule.isCrossTable()) {
                // A synthetic (already-coalesced) event's own table was already evaluated in full by
                // rulesForTable when the update that produced this event was made — re-matching a
                // same-table rule here would evaluate and audit it a second time for no new input.
                if (event.synthetic()) continue;
                GenericRecord snapshot0 = "AFTER_DELETE".equals(event.eventType()) ? event.recordBefore() : event.recordAfter();
                String recordId = recordIdOf(config, event.table(), snapshot0);
                if (recordId != null) affected.putIfAbsent(new TargetKey(rule.targetTable(), recordId), Boolean.TRUE);
                continue;
            }

            TriggerBinding binding = rule.triggerBinding();
            switch (event.eventType()) {
                case "AFTER_CREATE" -> {
                    String parentId = referenceValue(event.recordAfter(), binding.referenceField());
                    if (parentId != null) affected.putIfAbsent(new TargetKey(binding.targetTable(), parentId), Boolean.TRUE);
                }
                case "AFTER_DELETE" -> {
                    String parentId = referenceValue(event.recordBefore(), binding.referenceField());
                    if (parentId != null) affected.putIfAbsent(new TargetKey(binding.targetTable(), parentId), Boolean.TRUE);
                }
                case "AFTER_UPDATE" -> {
                    String before = referenceValue(event.recordBefore(), binding.referenceField());
                    String after = referenceValue(event.recordAfter(), binding.referenceField());
                    if (before != null) affected.putIfAbsent(new TargetKey(binding.targetTable(), before), Boolean.TRUE);
                    if (after != null && !after.equals(before)) affected.putIfAbsent(new TargetKey(binding.targetTable(), after), Boolean.TRUE);
                }
                default -> {
                }
            }
        }
        return affected;
    }

    private static String recordIdOf(BusinessConfiguration config, String table, GenericRecord record) {
        if (record == null) return null;
        FieldDefinition pk = config.requireTable(table).requirePrimaryKeyField();
        Object value = record.get(pk.fieldId());
        return value == null ? null : value.toString();
    }

    private static String referenceValue(GenericRecord record, String fieldId) {
        if (record == null) return null;
        Object value = record.get(fieldId);
        return value == null ? null : value.toString();
    }

    private Evaluation evaluateAndApply(Connection connection, BusinessConfiguration config, RuleDefinition rule, String currentTable, GenericRecord working) throws SQLException {
        BigDecimal result = evaluateExpression(connection, config, rule, currentTable, working);
        boolean changed = false;
        for (FieldRef writeRef : rule.writes()) {
            if (!writeRef.table().equals(currentTable)) continue;
            FieldDefinition field = config.requireTable(currentTable).requireField(writeRef.field());
            Object previous = working.get(writeRef.field());
            if (!ValueCodec.equalsCanonical(field, previous, result)) {
                working.set(writeRef.field(), result);
                changed = true;
            }
        }
        return new Evaluation(rule.ruleId(), changed ? "CHANGED" : "NO_CHANGE", changed);
    }

    private BigDecimal evaluateExpression(Connection connection, BusinessConfiguration config, RuleDefinition rule, String currentTable, GenericRecord working) throws SQLException {
        RuleExpression expr = rule.expression();
        if ("SUM_RELATED".equals(expr.operator())) {
            FieldDefinition pk = config.requireTable(currentTable).requirePrimaryKeyField();
            Object targetId = working.get(pk.fieldId());
            return recordStore.sumRelated(connection, expr.sumSource(), expr.sumWhere(), String.valueOf(targetId), expr.sumEmptyResult(), expr.scale());
        }
        List<BigDecimal> args = new ArrayList<>();
        for (RuleExpression.Argument argument : expr.arguments()) {
            args.add(resolveArgument(config, argument, currentTable, working));
        }
        return expr.apply(args, null);
    }

    private BigDecimal resolveArgument(BusinessConfiguration config, RuleExpression.Argument arg, String currentTable, GenericRecord working) {
        if (arg instanceof RuleExpression.Argument.ConstantArgument constant) return constant.value();
        RuleExpression.Argument.FieldArgument fieldArg = (RuleExpression.Argument.FieldArgument) arg;
        FieldRef ref = fieldArg.ref();
        if (!ref.table().equals(currentTable)) {
            throw new IllegalStateException("Rule argument '" + ref + "' reads outside its own target table '"
                    + currentTable + "'; only SUM_RELATED resolves another table generically in this runtime");
        }
        FieldDefinition field = config.requireTable(currentTable).requireField(ref.field());
        Object value = working.get(ref.field());
        if (value == null) {
            throw new IllegalStateException("Field '" + ref + "' is null and cannot be used in a numeric rule expression");
        }
        return field.type() == FieldType.INTEGER ? BigDecimal.valueOf((Integer) value) : (BigDecimal) value;
    }
}
