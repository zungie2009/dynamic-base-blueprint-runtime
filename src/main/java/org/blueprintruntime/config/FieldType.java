package org.blueprintruntime.config;

/**
 * The logical field types a schema configuration may declare, and the fixed
 * projection of each into a SQL column type and an HTML form control. This is a
 * direct implementation of the Base Blueprint's {@code fieldTypeControlMap} — the
 * runtime must never branch on a business- or field-specific name, only on this type.
 */
public enum FieldType {
    IDENTITY("VARCHAR(36)", "READ_ONLY_TEXT", true),
    STRING("VARCHAR", "TEXT_INPUT", false),
    TEXT("CLOB", "TEXTAREA", false),
    EMAIL("VARCHAR", "EMAIL_INPUT", false),
    INTEGER("INTEGER", "INTEGER_INPUT", false),
    DECIMAL_19_2("DECIMAL(19,2)", "DECIMAL_INPUT", false),
    BOOLEAN("BOOLEAN", "CHECKBOX", false),
    DATE("DATE", "DATE_INPUT", false),
    DATETIME("TIMESTAMP", "DATETIME_LOCAL_INPUT", false),
    ENUM("VARCHAR(64)", "SELECT", false),
    REFERENCE(null, "FOREIGN_KEY_SELECT", false);

    private final String sqlType;
    private final String control;
    private final boolean generated;

    FieldType(String sqlType, String control, boolean generated) {
        this.sqlType = sqlType;
        this.control = control;
        this.generated = generated;
    }

    public String control() {
        return control;
    }

    public boolean isGenerated() {
        return generated;
    }

    /**
     * The SQL column type for this field. STRING and EMAIL take an explicit
     * {@code length} (defaulting to 255 / 320); REFERENCE takes the referenced key's
     * own SQL type, which the caller must resolve and pass in.
     */
    public String sqlType(Integer declaredLength, String referencedKeySqlType) {
        return switch (this) {
            case STRING -> "VARCHAR(" + (declaredLength != null ? declaredLength : 255) + ")";
            case EMAIL -> "VARCHAR(" + (declaredLength != null ? declaredLength : 320) + ")";
            case REFERENCE -> referencedKeySqlType != null ? referencedKeySqlType : "VARCHAR(36)";
            default -> sqlType;
        };
    }

    public static FieldType fromConfig(String name) {
        try {
            return FieldType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown field type '" + name + "'");
        }
    }
}
