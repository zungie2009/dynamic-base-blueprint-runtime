package org.blueprintruntime.schema;

import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * Executes the {@code automaticSchemaProvisioning.activationSequence}: on first
 * activation of a business, create its schema and tables (transactionally, all or
 * nothing); on a later activation with an unchanged fingerprint, reuse the installed
 * schema untouched; on a changed fingerprint, reject activation rather than migrate.
 */
public final class SchemaProvisioner {

    private final SchemaRegistry registry = new SchemaRegistry();

    /**
     * @return {@code true} if a new schema was created, {@code false} if an existing
     * matching schema was reused.
     */
    public boolean provision(Connection connection, ResolvedBusinessBlueprint blueprint) throws SQLException {
        boolean priorAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            registry.ensureTable(connection);
            connection.commit();

            Optional<SchemaRegistry.Entry> existing = registry.find(connection, blueprint.businessId());
            if (existing.isPresent()) {
                SchemaRegistry.Entry entry = existing.get();
                if (entry.schemaFingerprint().equals(blueprint.schemaFingerprint())) {
                    registry.touchLastValidated(connection, blueprint.businessId());
                    connection.commit();
                    return false;
                }
                connection.rollback();
                throw new SchemaMigrationRequiredException(blueprint.businessId(), entry.schemaFingerprint(), blueprint.schemaFingerprint());
            }

            SchemaPlan plan = SchemaPlanner.plan(blueprint);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(plan.createSchemaStatement());
                for (String ddl : plan.createTableStatements()) {
                    stmt.execute(ddl);
                }
                for (String ddl : plan.addForeignKeyStatements()) {
                    stmt.execute(ddl);
                }
            }
            registry.recordInstalled(connection, blueprint.applicationId(), blueprint.businessId(),
                    blueprint.schemaVersion(), blueprint.schemaFingerprint());
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // best effort
            }
            throw e;
        } finally {
            connection.setAutoCommit(priorAutoCommit);
        }
    }
}
