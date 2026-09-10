package org.blueprintruntime.config;

import java.util.List;

/**
 * One configured field on one table. This is the unit the entire runtime derives
 * behavior from: its {@link FieldType} decides the SQL column and HTML control, its
 * flags decide validation and mutability, and — for a REFERENCE field — its
 * {@link ReferenceDefinition} decides the foreign key and picker.
 *
 * <p>Every property here (except presentation-only {@code label}) also participates
 * in the schema fingerprint; see {@link org.blueprintruntime.schema.FingerprintCalculator}.</p>
 */
public record FieldDefinition(
        String fieldId,
        String label,
        FieldType type,
        Integer length,
        boolean primaryKey,
        boolean required,
        boolean editable,
        boolean unique,
        String blankPolicy,
        String uniqueComparison,
        String defaultValue,
        boolean ruleWritable,
        List<String> enumValues,
        ReferenceDefinition reference
) {
    public FieldDefinition {
        if (fieldId == null || fieldId.isBlank()) throw new IllegalArgumentException("field.fieldId is required");
        if (type == null) throw new IllegalArgumentException("field.type is required for field '" + fieldId + "'");
        if (label == null || label.isBlank()) label = fieldId;
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        if (type == FieldType.REFERENCE && reference == null) {
            throw new IllegalArgumentException("REFERENCE field '" + fieldId + "' must declare 'references'");
        }
    }

    public boolean isNullableUnique() {
        return unique && !required;
    }

    public boolean caseInsensitiveUniqueness() {
        return "CASE_INSENSITIVE_FOR_NON_NULL_VALUES".equals(uniqueComparison);
    }

    public boolean normalizeBlankToNull() {
        return "NORMALIZE_TO_NULL".equals(blankPolicy);
    }
}
