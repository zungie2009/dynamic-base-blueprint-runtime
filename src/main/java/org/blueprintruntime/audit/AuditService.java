package org.blueprintruntime.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists {@link AuditEvent}s to {@code PUBLIC.DI_RUNTIME_AUDIT}, per {@code
 * auditContract} and {@code cascadingMutationAuditPolicy}: every write participates in
 * the caller's current transaction (never opens or commits its own), so a rolled-back
 * mutation leaves no audit row behind either.
 */
public final class AuditService {

    public void ensureTable(Connection connection) throws SQLException {
        try (var stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS PUBLIC.DI_RUNTIME_AUDIT (
                        EVENT_ID VARCHAR(36) NOT NULL PRIMARY KEY,
                        OCCURRED_AT_UTC TIMESTAMP NOT NULL,
                        BUSINESS_ID VARCHAR(200) NOT NULL,
                        SCHEMA_VERSION VARCHAR(50) NOT NULL,
                        EVENT_TYPE VARCHAR(50) NOT NULL,
                        TABLE_ID VARCHAR(200),
                        RECORD_ID VARCHAR(200),
                        RULE_SET_ID VARCHAR(200),
                        RULE_SET_VERSION VARCHAR(50),
                        RULE_ID VARCHAR(200),
                        TRANSACTION_ID VARCHAR(36) NOT NULL,
                        CORRELATION_ID VARCHAR(36) NOT NULL,
                        CAUSATION_EVENT_ID VARCHAR(36),
                        ORIGIN VARCHAR(20) NOT NULL,
                        OUTCOME VARCHAR(20),
                        DETAILS_JSON CLOB
                    )
                    """);
        }
    }

    /** Inserts the event (bound parameters only) and returns it finalized with a generated id and timestamp. */
    public AuditEvent write(Connection connection, AuditEvent draft) throws SQLException {
        String eventId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        String sql = "INSERT INTO PUBLIC.DI_RUNTIME_AUDIT "
                + "(EVENT_ID, OCCURRED_AT_UTC, BUSINESS_ID, SCHEMA_VERSION, EVENT_TYPE, TABLE_ID, RECORD_ID, "
                + "RULE_SET_ID, RULE_SET_VERSION, RULE_ID, TRANSACTION_ID, CORRELATION_ID, CAUSATION_EVENT_ID, "
                + "ORIGIN, OUTCOME, DETAILS_JSON) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventId);
            ps.setTimestamp(2, Timestamp.from(occurredAt));
            ps.setString(3, draft.businessId());
            ps.setString(4, draft.schemaVersion());
            ps.setString(5, draft.eventType());
            bindNullableString(ps, 6, draft.tableId());
            bindNullableString(ps, 7, draft.recordId());
            bindNullableString(ps, 8, draft.ruleSetId());
            bindNullableString(ps, 9, draft.ruleSetVersion());
            bindNullableString(ps, 10, draft.ruleId());
            ps.setString(11, draft.transactionId());
            ps.setString(12, draft.correlationId());
            bindNullableString(ps, 13, draft.causationEventId());
            ps.setString(14, draft.origin());
            bindNullableString(ps, 15, draft.outcome());
            bindNullableString(ps, 16, draft.detailsJson());
            ps.executeUpdate();
        }
        return draft.withIdAndTimestamp(eventId, occurredAt);
    }

    private static void bindNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) ps.setNull(index, Types.VARCHAR);
        else ps.setString(index, value);
    }

    public List<AuditEvent> findByTransaction(Connection connection, String transactionId) throws SQLException {
        return query(connection, "TRANSACTION_ID = ?", transactionId);
    }

    public List<AuditEvent> findByCorrelation(Connection connection, String correlationId) throws SQLException {
        return query(connection, "CORRELATION_ID = ?", correlationId);
    }

    public int countByEventType(Connection connection, String correlationId, String eventType) throws SQLException {
        String sql = "SELECT COUNT(*) AS N FROM PUBLIC.DI_RUNTIME_AUDIT WHERE CORRELATION_ID = ? AND EVENT_TYPE = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, correlationId);
            ps.setString(2, eventType);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt("N");
            }
        }
    }

    private List<AuditEvent> query(Connection connection, String whereClause, String param) throws SQLException {
        String sql = "SELECT EVENT_ID, OCCURRED_AT_UTC, BUSINESS_ID, SCHEMA_VERSION, EVENT_TYPE, TABLE_ID, RECORD_ID, "
                + "RULE_SET_ID, RULE_SET_VERSION, RULE_ID, TRANSACTION_ID, CORRELATION_ID, CAUSATION_EVENT_ID, "
                + "ORIGIN, OUTCOME, DETAILS_JSON FROM PUBLIC.DI_RUNTIME_AUDIT WHERE " + whereClause + " ORDER BY OCCURRED_AT_UTC ASC";
        List<AuditEvent> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new AuditEvent(
                            rs.getString("EVENT_ID"),
                            rs.getTimestamp("OCCURRED_AT_UTC").toInstant(),
                            rs.getString("BUSINESS_ID"),
                            rs.getString("SCHEMA_VERSION"),
                            rs.getString("EVENT_TYPE"),
                            rs.getString("TABLE_ID"),
                            rs.getString("RECORD_ID"),
                            rs.getString("RULE_SET_ID"),
                            rs.getString("RULE_SET_VERSION"),
                            rs.getString("RULE_ID"),
                            rs.getString("TRANSACTION_ID"),
                            rs.getString("CORRELATION_ID"),
                            rs.getString("CAUSATION_EVENT_ID"),
                            rs.getString("ORIGIN"),
                            rs.getString("OUTCOME"),
                            rs.getString("DETAILS_JSON")));
                }
            }
        }
        return results;
    }
}
