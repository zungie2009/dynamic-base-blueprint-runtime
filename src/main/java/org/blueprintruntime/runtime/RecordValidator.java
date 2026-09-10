package org.blueprintruntime.runtime;

import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.FieldDefinition;
import org.blueprintruntime.config.FieldType;
import org.blueprintruntime.config.TableDefinition;
import org.blueprintruntime.schema.Identifiers;
import org.blueprintruntime.schema.SchemaPlanner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates a fully assembled candidate record against the {@code genericCrudContract}
 * and {@code nullableUniqueContract}: required/length/precision/enum shape, reference
 * existence, and nullable-unique semantics (multiple NULLs permitted; non-null
 * duplicates rejected, case-insensitively when declared). Collects every violation
 * before throwing so a form can show them all at once.
 */
public final class RecordValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** Validates a complete candidate record (all configured fields present or intentionally null) prior to INSERT. */
    public void validateForCreate(Connection connection, ResolvedBusinessBlueprint blueprint, TableDefinition table, GenericRecord candidate) throws SQLException {
        validateCommon(connection, blueprint, table, candidate, null);
    }

    /** Validates the complete resulting record (existing values merged with submitted changes) prior to UPDATE. */
    public void validateForUpdate(Connection connection, ResolvedBusinessBlueprint blueprint, TableDefinition table, String recordId, GenericRecord candidate) throws SQLException {
        validateCommon(connection, blueprint, table, candidate, recordId);
    }

    private void validateCommon(Connection connection, ResolvedBusinessBlueprint blueprint, TableDefinition table, GenericRecord candidate, String excludeRecordId) throws SQLException {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldDefinition field : table.fields()) {
            if (field.type() == FieldType.IDENTITY) continue; // server-generated; never user-validated
            Object value = candidate.get(field.fieldId());

            if (value == null) {
                if (field.required()) {
                    errors.put(field.fieldId(), field.label() + " is required");
                }
                continue; // nullable and absent: nothing further to check
            }

            switch (field.type()) {
                case STRING, TEXT -> {
                    int length = value.toString().length();
                    int max = field.length() != null ? field.length() : (field.type() == FieldType.TEXT ? Integer.MAX_VALUE : 255);
                    if (length > max) errors.put(field.fieldId(), field.label() + " must be at most " + max + " characters");
                }
                case EMAIL -> {
                    int max = field.length() != null ? field.length() : 320;
                    if (value.toString().length() > max) {
                        errors.put(field.fieldId(), field.label() + " must be at most " + max + " characters");
                    } else if (!EMAIL_PATTERN.matcher(value.toString()).matches()) {
                        errors.put(field.fieldId(), field.label() + " must be a valid email address");
                    }
                }
                case ENUM -> {
                    if (!field.enumValues().contains(value.toString())) {
                        errors.put(field.fieldId(), field.label() + " must be one of " + field.enumValues());
                    }
                }
                case REFERENCE -> {
                    if (!referenceExists(connection, blueprint, field, value.toString())) {
                        errors.put(field.fieldId(), field.label() + " references a record that does not exist");
                    }
                }
                default -> {
                    // INTEGER, DECIMAL_19_2, BOOLEAN, DATE, DATETIME are already strongly typed by ValueCodec by this point.
                }
            }

            if (field.unique() && !errors.containsKey(field.fieldId())) {
                if (isDuplicate(connection, blueprint, table, field, value, excludeRecordId)) {
                    errors.put(field.fieldId(), field.label() + " must be unique");
                }
            }
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    private boolean referenceExists(Connection connection, ResolvedBusinessBlueprint blueprint, FieldDefinition field, String value) throws SQLException {
        var ref = field.reference();
        String qualified = Identifiers.quote(blueprint.databaseSchemaName()) + "." + Identifiers.quote(Identifiers.tableName(ref.tableId()));
        String column = Identifiers.quote(Identifiers.columnName(ref.fieldId()));
        String sql = "SELECT 1 FROM " + qualified + " WHERE " + column + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean isDuplicate(Connection connection, ResolvedBusinessBlueprint blueprint, TableDefinition table, FieldDefinition field, Object value, String excludeRecordId) throws SQLException {
        String qualified = Identifiers.quote(blueprint.databaseSchemaName()) + "." + Identifiers.quote(Identifiers.tableName(table.tableId()));
        FieldDefinition pk = table.requirePrimaryKeyField();
        String pkColumn = Identifiers.quote(Identifiers.columnName(pk.fieldId()));

        String column;
        String compareValue;
        if (field.caseInsensitiveUniqueness()) {
            column = Identifiers.quote(SchemaPlanner.ciColumnName(field.fieldId()));
            compareValue = value.toString().toLowerCase(Locale.ROOT);
        } else {
            column = Identifiers.quote(Identifiers.columnName(field.fieldId()));
            compareValue = value.toString();
        }

        String sql = "SELECT 1 FROM " + qualified + " WHERE " + column + " = ?"
                + (excludeRecordId != null ? " AND " + pkColumn + " <> ?" : "");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, compareValue);
            if (excludeRecordId != null) ps.setString(2, excludeRecordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
