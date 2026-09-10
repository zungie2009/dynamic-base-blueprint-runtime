package org.blueprintruntime.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code requiredTests}: a valid configuration loads and resolves; a malformed
 * JSON file and a semantically invalid configuration are both rejected before any
 * database mutation is attempted (construction alone, with no {@code Database} in
 * scope, is the proof).
 */
class ConfigurationLoaderTest {

    @Test
    void validSampleConfigurationLoads() {
        BusinessConfiguration config = ConfigurationLoader.loadFromFile(Path.of("samples/business-schema-config.jsonc"));
        assertEquals("CONSULTING_DEMO", config.businessId());
        assertEquals(4, config.tables().size());
        assertTrue(config.table("clients").isPresent());
        assertTrue(config.table("invoiceLines").isPresent());
    }

    @Test
    void malformedJsonFileIsRejected(@TempDir Path tempDir) throws IOException {
        Path badFile = tempDir.resolve("malformed.jsonc");
        Files.writeString(badFile, "{ \"business\": { \"businessId\": \"X\", }, \"tables\": [ ] "); // truncated / trailing comma

        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigurationLoader.loadFromFile(badFile));
        assertTrue(e.getMessage() != null && !e.getMessage().isBlank());
    }

    @Test
    void semanticallyInvalidConfigurationIsRejected(@TempDir Path tempDir) throws IOException {
        // Valid JSON, but a table with two fields sharing a fieldId and no primary key at all.
        String invalid = """
                {
                  "configurationId": "BAD_CONFIG",
                  "business": { "businessId": "BAD_BIZ", "businessName": "Bad", "schemaVersion": "1" },
                  "tables": [
                    {
                      "tableId": "things",
                      "displayField": "name",
                      "fields": [
                        { "fieldId": "name", "label": "Name", "type": "STRING", "length": 10 },
                        { "fieldId": "name", "label": "Duplicate", "type": "STRING", "length": 10 }
                      ]
                    }
                  ]
                }
                """;
        Path badFile = tempDir.resolve("semantically-invalid.jsonc");
        Files.writeString(badFile, invalid);

        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigurationLoader.loadFromFile(badFile));
        assertTrue(e.problems().stream().anyMatch(p -> p.toLowerCase().contains("duplicate")
                || p.toLowerCase().contains("primary key")));
    }
}
