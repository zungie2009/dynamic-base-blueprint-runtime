package org.blueprintruntime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code requiredTests}: "A source scan finds no sample business, table, or
 * field identifiers in Java source." Every identifier here comes straight from the
 * Consulting sample configuration and rule file — this runtime must derive its
 * behavior from those files at runtime, never encode them at compile time, per {@code
 * baseBlueprint.mustNotKnow} and {@code prohibitedImplementation}.
 */
class SourceScanTest {

    private static final List<String> FORBIDDEN_IDENTIFIERS = List.of(
            "CONSULTING_DEMO", "CONSULTING_SCHEMA_DEMO", "CONSULTING_INVOICE_CALCULATIONS", "CONSULTING_UI_DEMO_V1", "Consulting Company",
            "clientId", "engagementId", "invoiceId", "invoiceLines", "invoiceNumber", "invoiceDate",
            "lineAmount", "unitPrice", "startDate"
    );

    @Test
    void mainSourceContainsNoSampleBusinessTableOrFieldIdentifiers() throws IOException {
        Path mainSourceRoot = Path.of("src/main/java");
        StringBuilder violations = new StringBuilder();

        try (Stream<Path> files = Files.walk(mainSourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file);
                for (String forbidden : FORBIDDEN_IDENTIFIERS) {
                    if (content.contains(forbidden)) {
                        violations.append(file).append(" contains forbidden sample identifier '").append(forbidden).append("'\n");
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Main source must stay domain-neutral, but found:\n" + violations);
    }

    @Test
    void mainSourceContainsNoLowercaseSampleTableNamesEitherCaseInsensitively() throws IOException {
        // A separate, case-insensitive pass over the sample table ids themselves, since a careless
        // implementation could smuggle a table name in via a different capitalization or as part of
        // a longer identifier (e.g. a hardcoded switch branch) rather than the exact literal above.
        List<String> tableIds = List.of("clients", "engagements", "invoices");
        Path mainSourceRoot = Path.of("src/main/java");
        StringBuilder violations = new StringBuilder();

        try (Stream<Path> files = Files.walk(mainSourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file).toLowerCase(Locale.ROOT);
                for (String tableId : tableIds) {
                    // Word-boundary search: this file's own prose legitimately uses ordinary English
                    // words ("invoices" as a business concept is never mentioned; "client" as in HTTP
                    // client is unrelated) so an exact identifier match, not a broad substring, is used.
                    if (content.matches("(?s).*\\b" + tableId + "\\b.*") && !tableId.equals("client")) {
                        violations.append(file).append(" contains sample table name '").append(tableId).append("'\n");
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Main source must not name a sample table, but found:\n" + violations);
    }
}
