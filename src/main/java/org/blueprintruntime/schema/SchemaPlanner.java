package org.blueprintruntime.schema;

import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.FieldDefinition;
import org.blueprintruntime.config.TableDefinition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link ResolvedBusinessBlueprint} into concrete H2 DDL, purely by walking
 * the configured tables and fields — this class never mentions a business, table, or
 * field name, only the generic shapes {@code fieldTypeControlMap} and
 * {@code databaseNaming} describe.
 */
public final class SchemaPlanner {

    private SchemaPlanner() {
    }

    /** Column name of the case-insensitive normalized companion column for a unique, case-insensitive field. */
    public static String ciColumnName(String fieldId) {
        return Identifiers.columnName(fieldId) + "_CI";
    }

    public static SchemaPlan plan(ResolvedBusinessBlueprint blueprint) {
        BusinessConfiguration config = blueprint.configuration();
        String schema = Identifiers.quote(blueprint.databaseSchemaName());

        List<String> createTables = new ArrayList<>();
        List<String> addForeignKeys = new ArrayList<>();

        for (TableDefinition table : config.tables()) {
            createTables.add(createTableStatement(config, schema, table));
            addForeignKeys.addAll(foreignKeyStatements(config, schema, table));
        }

        String createSchema = "CREATE SCHEMA IF NOT EXISTS " + schema;
        return new SchemaPlan(blueprint.databaseSchemaName(), createSchema, createTables, addForeignKeys);
    }

    private static String createTableStatement(BusinessConfiguration config, String schema, TableDefinition table) {
        String tableName = schema + "." + Identifiers.quote(Identifiers.tableName(table.tableId()));
        List<String> columnDefs = new ArrayList<>();
        List<String> tableConstraints = new ArrayList<>();

        for (FieldDefinition field : table.fields()) {
            columnDefs.add(columnDefinition(config, field));
            if (field.unique()) {
                String colName = Identifiers.quote(Identifiers.columnName(field.fieldId()));
                if (field.caseInsensitiveUniqueness()) {
                    columnDefs.add(Identifiers.quote(ciColumnName(field.fieldId())) + " VARCHAR("
                            + (field.length() != null ? field.length() : 320) + ")");
                    tableConstraints.add("CONSTRAINT " + Identifiers.quote(Identifiers.constraintName(
                            config.businessId(), table.tableId(), field.fieldId(), "UQ_CI"))
                            + " UNIQUE (" + Identifiers.quote(ciColumnName(field.fieldId())) + ")");
                } else {
                    tableConstraints.add("CONSTRAINT " + Identifiers.quote(Identifiers.constraintName(
                            config.businessId(), table.tableId(), field.fieldId(), "UQ"))
                            + " UNIQUE (" + colName + ")");
                }
            }
        }

        String pkColumn = Identifiers.quote(Identifiers.columnName(table.requirePrimaryKeyField().fieldId()));
        tableConstraints.add("CONSTRAINT " + Identifiers.quote(Identifiers.constraintName(
                config.businessId(), table.tableId(), table.requirePrimaryKeyField().fieldId(), "PK"))
                + " PRIMARY KEY (" + pkColumn + ")");

        List<String> allDefs = new ArrayList<>(columnDefs);
        allDefs.addAll(tableConstraints);
        return "CREATE TABLE IF NOT EXISTS " + tableName + " (\n    " + String.join(",\n    ", allDefs) + "\n)";
    }

    private static String columnDefinition(BusinessConfiguration config, FieldDefinition field) {
        String colName = Identifiers.quote(Identifiers.columnName(field.fieldId()));
        String referencedKeySqlType = null;
        if (field.type() == org.blueprintruntime.config.FieldType.REFERENCE) {
            var targetTable = config.requireTable(field.reference().tableId());
            var targetField = targetTable.requireField(field.reference().fieldId());
            referencedKeySqlType = targetField.type().sqlType(targetField.length(), null);
        }
        String sqlType = field.type().sqlType(field.length(), referencedKeySqlType);

        StringBuilder def = new StringBuilder(colName).append(' ').append(sqlType);
        if (field.required() || field.primaryKey()) {
            def.append(" NOT NULL");
        }
        if (field.defaultValue() != null) {
            def.append(" DEFAULT ").append(sqlLiteral(field, field.defaultValue()));
        }
        return def.toString();
    }

    private static String sqlLiteral(FieldDefinition field, String value) {
        return switch (field.type()) {
            case INTEGER -> String.valueOf(Integer.parseInt(value));
            case DECIMAL_19_2 -> new BigDecimal(value).toPlainString();
            case BOOLEAN -> Boolean.parseBoolean(value) ? "TRUE" : "FALSE";
            default -> "'" + value.replace("'", "''") + "'";
        };
    }

    private static List<String> foreignKeyStatements(BusinessConfiguration config, String schema, TableDefinition table) {
        List<String> statements = new ArrayList<>();
        String tableName = schema + "." + Identifiers.quote(Identifiers.tableName(table.tableId()));
        for (FieldDefinition field : table.fields()) {
            if (field.type() != org.blueprintruntime.config.FieldType.REFERENCE) continue;
            var ref = field.reference();
            String targetTableName = schema + "." + Identifiers.quote(Identifiers.tableName(ref.tableId()));
            String targetColumn = Identifiers.quote(Identifiers.columnName(ref.fieldId()));
            String sourceColumn = Identifiers.quote(Identifiers.columnName(field.fieldId()));
            String constraintName = Identifiers.quote(Identifiers.constraintName(
                    config.businessId(), table.tableId(), field.fieldId(), "FK"));
            statements.add("ALTER TABLE " + tableName + " ADD CONSTRAINT " + constraintName
                    + " FOREIGN KEY (" + sourceColumn + ") REFERENCES " + targetTableName + " (" + targetColumn + ")"
                    + " ON DELETE RESTRICT");
        }
        return statements;
    }
}
