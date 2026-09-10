package org.blueprintruntime.schema;

import java.util.Locale;

/**
 * SQL identifier sanitization per {@code databaseNaming}: schema, table, and column
 * names are all derived from configuration identifiers by uppercasing and replacing
 * anything that is not {@code [A-Z0-9_]}. Sanitized identifiers are always rendered
 * quoted in generated SQL so case and any residual special character are preserved
 * exactly and request values are never concatenated unescaped into a statement.
 */
public final class Identifiers {

    private Identifiers() {
    }

    public static String sanitizeUpper(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("identifier must not be blank");
        String upper = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        if (upper.isEmpty() || Character.isDigit(upper.charAt(0))) {
            upper = "X_" + upper;
        }
        return upper;
    }

    public static String quote(String sanitizedIdentifier) {
        return "\"" + sanitizedIdentifier.replace("\"", "\"\"") + "\"";
    }

    public static String schemaName(String businessId) {
        return "APP_" + sanitizeUpper(businessId);
    }

    public static String tableName(String tableId) {
        return sanitizeUpper(tableId);
    }

    public static String columnName(String fieldId) {
        return sanitizeUpper(fieldId);
    }

    /** Deterministic constraint name derived from businessId, tableId, fieldId, and kind — never longer than H2's identifier limits in practice for this experiment's scale. */
    public static String constraintName(String businessId, String tableId, String fieldId, String kind) {
        return sanitizeUpper(businessId + "_" + tableId + "_" + fieldId + "_" + kind);
    }
}
