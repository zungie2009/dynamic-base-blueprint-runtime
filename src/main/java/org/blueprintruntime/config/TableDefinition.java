package org.blueprintruntime.config;

import java.util.List;
import java.util.Optional;

/**
 * One configured table: its left-navigation presentation, its fields, and the
 * single field that supplies the human-readable record label ({@code displayField}).
 */
public record TableDefinition(
        String tableId,
        String menuLabel,
        int menuOrder,
        String singularLabel,
        String displayField,
        List<FieldDefinition> fields
) {
    public TableDefinition {
        if (tableId == null || tableId.isBlank()) throw new IllegalArgumentException("table.tableId is required");
        if (fields == null || fields.isEmpty()) throw new IllegalArgumentException("table '" + tableId + "' must declare at least one field");
        fields = List.copyOf(fields);
        if (menuLabel == null || menuLabel.isBlank()) menuLabel = tableId;
        if (singularLabel == null || singularLabel.isBlank()) singularLabel = menuLabel;
    }

    public Optional<FieldDefinition> field(String fieldId) {
        return fields.stream().filter(f -> f.fieldId().equals(fieldId)).findFirst();
    }

    public FieldDefinition requireField(String fieldId) {
        return field(fieldId).orElseThrow(() ->
                new IllegalArgumentException("Table '" + tableId + "' has no field '" + fieldId + "'"));
    }

    public Optional<FieldDefinition> primaryKeyField() {
        return fields.stream().filter(FieldDefinition::primaryKey).findFirst();
    }

    public FieldDefinition requirePrimaryKeyField() {
        return primaryKeyField().orElseThrow(() ->
                new IllegalArgumentException("Table '" + tableId + "' declares no primary key field"));
    }
}
