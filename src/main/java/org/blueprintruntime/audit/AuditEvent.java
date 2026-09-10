package org.blueprintruntime.audit;

import java.time.Instant;

/**
 * One row of the {@code auditContract}: {@code event_id, occurred_at_utc, business_id,
 * schema_version, event_type, table_id, record_id, rule_set_id, rule_set_version,
 * rule_id, transaction_id, correlation_id, causation_event_id, origin, outcome,
 * details_json}. Built as an unwritten {@link #draft} (no id/timestamp yet) and
 * finalized by {@link org.blueprintruntime.audit.AuditService#write} once persisted.
 */
public record AuditEvent(
        String eventId,
        Instant occurredAtUtc,
        String businessId,
        String schemaVersion,
        String eventType,
        String tableId,
        String recordId,
        String ruleSetId,
        String ruleSetVersion,
        String ruleId,
        String transactionId,
        String correlationId,
        String causationEventId,
        String origin,
        String outcome,
        String detailsJson
) {
    public static AuditEvent draft(
            String businessId, String schemaVersion, String eventType, String tableId, String recordId,
            String ruleSetId, String ruleSetVersion, String ruleId,
            String transactionId, String correlationId, String causationEventId,
            String origin, String outcome, String detailsJson) {
        return new AuditEvent(null, null, businessId, schemaVersion, eventType, tableId, recordId,
                ruleSetId, ruleSetVersion, ruleId, transactionId, correlationId, causationEventId,
                origin, outcome, detailsJson);
    }

    public AuditEvent withIdAndTimestamp(String id, Instant occurredAt) {
        return new AuditEvent(id, occurredAt, businessId, schemaVersion, eventType, tableId, recordId,
                ruleSetId, ruleSetVersion, ruleId, transactionId, correlationId, causationEventId,
                origin, outcome, detailsJson);
    }
}
