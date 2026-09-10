package org.blueprintruntime.config;

/**
 * A foreign-key relationship declared on a REFERENCE field: which table and field
 * it points to, and what happens when the referenced parent record is deleted.
 * Corresponds to a field's {@code references} object in the schema configuration.
 */
public record ReferenceDefinition(String tableId, String fieldId, String onDelete) {
    public ReferenceDefinition {
        if (tableId == null || tableId.isBlank()) throw new IllegalArgumentException("references.tableId is required");
        if (fieldId == null || fieldId.isBlank()) throw new IllegalArgumentException("references.fieldId is required");
        if (onDelete == null || onDelete.isBlank()) onDelete = "RESTRICT";
    }
}
