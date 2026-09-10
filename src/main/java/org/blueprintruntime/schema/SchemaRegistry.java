package org.blueprintruntime.schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Bookkeeping for which business schemas are installed and at which structural
 * fingerprint, backed by the {@code DI_SCHEMA_REGISTRY} table the contract names.
 * This table lives outside any per-business schema (in {@code PUBLIC}) since it
 * tracks businesses, not business data.
 */
public final class SchemaRegistry {

    public record Entry(String configurationId, String businessId, String schemaVersion,
                         String schemaFingerprint, String provisioningStatus,
                         Instant installedAt, Instant lastValidatedAt) {
    }

    public void ensureTable(Connection connection) throws SQLException {
        try (var stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS PUBLIC.DI_SCHEMA_REGISTRY (
                        BUSINESS_ID VARCHAR(200) NOT NULL PRIMARY KEY,
                        CONFIGURATION_ID VARCHAR(200) NOT NULL,
                        SCHEMA_VERSION VARCHAR(50) NOT NULL,
                        SCHEMA_FINGERPRINT VARCHAR(64) NOT NULL,
                        PROVISIONING_STATUS VARCHAR(30) NOT NULL,
                        INSTALLED_AT TIMESTAMP NOT NULL,
                        LAST_VALIDATED_AT TIMESTAMP NOT NULL
                    )
                    """);
        }
    }

    public Optional<Entry> find(Connection connection, String businessId) throws SQLException {
        String sql = "SELECT BUSINESS_ID, CONFIGURATION_ID, SCHEMA_VERSION, SCHEMA_FINGERPRINT, "
                + "PROVISIONING_STATUS, INSTALLED_AT, LAST_VALIDATED_AT FROM PUBLIC.DI_SCHEMA_REGISTRY WHERE BUSINESS_ID = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, businessId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Entry(
                        rs.getString("CONFIGURATION_ID"),
                        rs.getString("BUSINESS_ID"),
                        rs.getString("SCHEMA_VERSION"),
                        rs.getString("SCHEMA_FINGERPRINT"),
                        rs.getString("PROVISIONING_STATUS"),
                        rs.getTimestamp("INSTALLED_AT").toInstant(),
                        rs.getTimestamp("LAST_VALIDATED_AT").toInstant()));
            }
        }
    }

    public void recordInstalled(Connection connection, String configurationId, String businessId,
                                 String schemaVersion, String schemaFingerprint) throws SQLException {
        Instant now = Instant.now();
        String sql = "MERGE INTO PUBLIC.DI_SCHEMA_REGISTRY "
                + "(BUSINESS_ID, CONFIGURATION_ID, SCHEMA_VERSION, SCHEMA_FINGERPRINT, PROVISIONING_STATUS, INSTALLED_AT, LAST_VALIDATED_AT) "
                + "KEY (BUSINESS_ID) VALUES (?, ?, ?, ?, 'INSTALLED', ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, businessId);
            ps.setString(2, configurationId);
            ps.setString(3, schemaVersion);
            ps.setString(4, schemaFingerprint);
            ps.setTimestamp(5, Timestamp.from(now));
            ps.setTimestamp(6, Timestamp.from(now));
            ps.executeUpdate();
        }
    }

    public void touchLastValidated(Connection connection, String businessId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE PUBLIC.DI_SCHEMA_REGISTRY SET LAST_VALIDATED_AT = ? WHERE BUSINESS_ID = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, businessId);
            ps.executeUpdate();
        }
    }
}
