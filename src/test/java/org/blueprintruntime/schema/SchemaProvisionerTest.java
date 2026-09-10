package org.blueprintruntime.schema;

import org.blueprintruntime.blueprint.BlueprintResolver;
import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.ConfigurationLoader;
import org.blueprintruntime.db.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code automaticSchemaProvisioning} required tests: a missing schema is
 * created automatically; an unchanged configuration reuses the installed schema
 * without modification; and a changed structural fingerprint is rejected as
 * migration-required, leaving the installed schema untouched.
 */
class SchemaProvisionerTest {

    @Test
    void firstActivationCreatesTheSchemaAndSecondReusesIt(@TempDir Path tempDir) throws SQLException {
        BusinessConfiguration config = ConfigurationLoader.loadFromFile(Path.of("samples/business-schema-config.jsonc"));
        ResolvedBusinessBlueprint blueprint = BlueprintResolver.resolve(config);
        Database database = Database.embeddedFile(tempDir.resolve("provisioner-test").toString());

        try (Connection connection = database.getConnection()) {
            boolean created = new SchemaProvisioner().provision(connection, blueprint);
            assertTrue(created, "first activation against an empty data directory must create the schema");
        }
        try (Connection connection = database.getConnection()) {
            boolean createdAgain = new SchemaProvisioner().provision(connection, blueprint);
            assertFalse(createdAgain, "an unchanged configuration must reuse the installed schema, not recreate it");
        }
        try (Connection connection = database.getConnection()) {
            SchemaRegistry.Entry entry = new SchemaRegistry().find(connection, blueprint.businessId()).orElseThrow();
            assertEquals(blueprint.schemaFingerprint(), entry.schemaFingerprint());
        }
    }

    @Test
    void changedFingerprintIsRejectedWithoutModifyingTheInstalledSchema(@TempDir Path tempDir) throws SQLException {
        BusinessConfiguration original = ConfigurationLoader.loadFromFile(Path.of("samples/business-schema-config.jsonc"));
        ResolvedBusinessBlueprint originalBlueprint = BlueprintResolver.resolve(original);
        Database database = Database.embeddedFile(tempDir.resolve("migration-test").toString());

        try (Connection connection = database.getConnection()) {
            new SchemaProvisioner().provision(connection, originalBlueprint);
        }

        // Same businessId, but a structurally different configuration (a shortened field length changes the fingerprint).
        String changedText = readSample().replace("\"length\": 200", "\"length\": 199");
        BusinessConfiguration changed = ConfigurationLoader.loadFromText(changedText, "changed");
        ResolvedBusinessBlueprint changedBlueprint = BlueprintResolver.resolve(changed);
        assert !changedBlueprint.schemaFingerprint().equals(originalBlueprint.schemaFingerprint());

        try (Connection connection = database.getConnection()) {
            assertThrows(SchemaMigrationRequiredException.class,
                    () -> new SchemaProvisioner().provision(connection, changedBlueprint));
        }

        try (Connection connection = database.getConnection()) {
            SchemaRegistry.Entry entry = new SchemaRegistry().find(connection, originalBlueprint.businessId()).orElseThrow();
            assertEquals(originalBlueprint.schemaFingerprint(), entry.schemaFingerprint(),
                    "the installed schema's recorded fingerprint must remain the original one; no in-place migration is ever performed");
        }
    }

    private static String readSample() {
        try {
            return java.nio.file.Files.readString(Path.of("samples/business-schema-config.jsonc"));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
