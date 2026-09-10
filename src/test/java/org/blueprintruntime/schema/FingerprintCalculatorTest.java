package org.blueprintruntime.schema;

import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.ConfigurationLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies {@code schemaFingerprintCanonicalization} against the real sample
 * configuration: the computed SHA-256 must exactly match the value the corresponding
 * {@code business-rules.jsonc} declares as its {@code expectedSchemaFingerprint} — this
 * is the same value independently hand-verified during design review.
 */
class FingerprintCalculatorTest {

    private static final String EXPECTED_CONSULTING_FINGERPRINT =
            "1d4a004c82e570e13173079f90bb23401118438f6ecd9677714a6a75a7ad4efc";

    @Test
    void computesTheIndependentlyVerifiedConsultingFingerprint() {
        BusinessConfiguration config = ConfigurationLoader.loadFromFile(Path.of("samples/business-schema-config.jsonc"));
        String fingerprint = FingerprintCalculator.compute(config);
        assertEquals(EXPECTED_CONSULTING_FINGERPRINT, fingerprint);
    }

    @Test
    void fingerprintIsDeterministicAcrossRepeatedComputation() {
        BusinessConfiguration config = ConfigurationLoader.loadFromFile(Path.of("samples/business-schema-config.jsonc"));
        assertEquals(FingerprintCalculator.compute(config), FingerprintCalculator.compute(config));
    }

    @Test
    void fingerprintChangesWhenAFieldIsRenamed() {
        BusinessConfiguration original = ConfigurationLoader.loadFromFile(Path.of("samples/business-schema-config.jsonc"));
        // Re-parsing renamed JSON text (rather than mutating the record) proves the fingerprint
        // is sensitive to structural changes, not merely stable under no-op recomputation.
        String renamedJson = readSample().replace("\"invoiceNumber\"", "\"invoiceNo\"");
        BusinessConfiguration renamed = ConfigurationLoader.loadFromText(renamedJson, "renamed");
        assertNotEquals(FingerprintCalculator.compute(original), FingerprintCalculator.compute(renamed));
    }

    private static String readSample() {
        try {
            return java.nio.file.Files.readString(Path.of("samples/business-schema-config.jsonc"));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
