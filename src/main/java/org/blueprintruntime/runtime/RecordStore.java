package org.blueprintruntime.runtime;

import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.FieldDefinition;
import org.blueprintruntime.config.TableDefinition;
import org.blueprintruntime.schema.Identifiers;
import org.blueprintruntime.schema.SchemaPlanner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes {@link GenericRecord}s against the active business's schema over
 * plain JDBC — every statement here is a {@link PreparedStatement} with bound
 * parameters, never a concatenated value, per the {@code databaseNaming.safety} and
 * {@code genericCrudContract.unknownInputRule} rules. Used by both the generic CRUD
 * service and the rule engine's cross-table propagation, so both read and write
 * records through exactly one code path.
 */
public final class RecordStore {

    private final ResolvedBusinessBlueprint blueprint;

    public RecordStore(ResolvedBusinessBlueprint blueprint) {
        this.blueprint = blueprint;
    }

    private String qualifiedTable(String tableId) {
        return Identifiers.quote(blueprint.databaseSchemaName()) + "." + Identifiers.quote(Identifiers.tableName(tableId));
    }

    public Optional<GenericRecord> find(Connection connection, String tableId, String recordId) throws SQLException {
        TableDefinition table = blueprint.configuration().requireTable(tableId);
        FieldDefinition pk = table.requirePrimaryKeyField();
        String sql = "SELECT * FROM " + qualifiedTable(tableId) + " WHERE " + Identifiers.quote(Identifiers.columnName(pk.fieldId())) + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, recordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRow(table, rs));
            }
        }
    }

    public List<GenericRecord> findAll(Connection connection, String tableId) throws SQLException {
        TableDefinition table = blueprint.configuration().requireTable(tableId);
        String sql = "SELECT * FROM " + qualifiedTable(tableId);
        List<GenericRecord> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) results.add(mapRow(table, rs));
        }
        return results;
    }

    public List<GenericRecord> findByField(Connection connection, String tableId, String fieldId, String value) throws SQLException {
        TableDefinition table = blueprint.configuration().requireTable(tableId);
        String col = Identifiers.quote(Identifiers.columnName(fieldId));
        String sql = "SELECT * FROM " + qualifiedTable(tableId) + " WHERE " + col + " = ?";
        List<GenericRecord> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(table, rs));
            }
        }
        return results;
    }

    private GenericRecord mapRow(TableDefinition table, ResultSet rs) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (FieldDefinition field : table.fields()) {
            values.put(field.fieldId(), ValueCodec.read(rs, Identifiers.columnName(field.fieldId()), field));
        }
        return new GenericRecord(table.tableId(), values);
    }

    public void insert(Connection connection, GenericRecord record) throws SQLException {
        TableDefinition table = blueprint.configuration().requireTable(record.tableId());
        List<String> columns = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        List<FieldDefinition> paramFields = new ArrayList<>();

        for (FieldDefinition field : table.fields()) {
            Object value = record.get(field.fieldId());
            columns.add(Identifiers.quote(Identifiers.columnName(field.fieldId())));
            params.add(value);
            paramFields.add(field);
            if (field.unique() && field.caseInsensitiveUniqueness()) {
                columns.add(Identifiers.quote(SchemaPlanner.ciColumnName(field.fieldId())));
                params.add(normalizedCiValue(field, value));
                paramFields.add(null); // sentinel: bind as plain string
            }
        }

        String sql = "INSERT INTO " + qualifiedTable(table.tableId()) + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                bindParam(ps, i + 1, paramFields.get(i), params.get(i));
            }
            ps.executeUpdate();
        }
    }

    public void update(Connection connection, String tableId, String recordId, Map<String, Object> changes) throws SQLException {
        if (changes.isEmpty()) return;
        TableDefinition table = blueprint.configuration().requireTable(tableId);
        FieldDefinition pk = table.requirePrimaryKeyField();

        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        List<FieldDefinition> paramFields = new ArrayList<>();

        for (var entry : changes.entrySet()) {
            FieldDefinition field = table.requireField(entry.getKey());
            setClauses.add(Identifiers.quote(Identifiers.columnName(field.fieldId())) + " = ?");
            params.add(entry.getValue());
            paramFields.add(field);
            if (field.unique() && field.caseInsensitiveUniqueness()) {
                setClauses.add(Identifiers.quote(SchemaPlanner.ciColumnName(field.fieldId())) + " = ?");
                params.add(normalizedCiValue(field, entry.getValue()));
                paramFields.add(null);
            }
        }

        String sql = "UPDATE " + qualifiedTable(tableId) + " SET " + String.join(", ", setClauses)
                + " WHERE " + Identifiers.quote(Identifiers.columnName(pk.fieldId())) + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            for (int j = 0; j < params.size(); j++) {
                bindParam(ps, i++, paramFields.get(j), params.get(j));
            }
            ps.setString(i, recordId);
            ps.executeUpdate();
        }
    }

    public void delete(Connection connection, String tableId, String recordId) throws SQLException {
        TableDefinition table = blueprint.configuration().requireTable(tableId);
        FieldDefinition pk = table.requirePrimaryKeyField();
        String sql = "DELETE FROM " + qualifiedTable(tableId) + " WHERE " + Identifiers.quote(Identifiers.columnName(pk.fieldId())) + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, recordId);
            ps.executeUpdate();
        }
    }

    /** Implements the SUM_RELATED operator generically by parsing {@code source} ("childTable.childField") and {@code where} ("childTable.childField = parentTable.parentKeyField"). */
    public BigDecimal sumRelated(Connection connection, String source, String where, String targetRecordId, BigDecimal emptyResult, int scale) throws SQLException {
        String[] sourceParts = source.split("\\.", 2);
        String childTable = sourceParts[0];
        String childField = sourceParts[1];

        String[] sides = where.split("=");
        String[] leftParts = sides[0].trim().split("\\.", 2);
        String[] rightParts = sides[1].trim().split("\\.", 2);
        String fkTable = leftParts[0].equals(childTable) ? leftParts[0] : rightParts[0];
        String fkField = leftParts[0].equals(childTable) ? leftParts[1] : rightParts[1];

        TableDefinition table = blueprint.configuration().requireTable(childTable);
        String sql = "SELECT SUM(" + Identifiers.quote(Identifiers.columnName(childField)) + ") AS TOTAL FROM "
                + qualifiedTable(fkTable) + " WHERE " + Identifiers.quote(Identifiers.columnName(fkField)) + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, targetRecordId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                BigDecimal total = rs.getBigDecimal("TOTAL");
                return (total == null ? emptyResult : total).setScale(scale, RoundingMode.HALF_UP);
            }
        }
    }

    private static String normalizedCiValue(FieldDefinition field, Object rawValue) {
        if (rawValue == null) return null;
        return rawValue.toString().toLowerCase(Locale.ROOT);
    }

    private static void bindParam(PreparedStatement ps, int index, FieldDefinition field, Object value) throws SQLException {
        if (field == null) {
            if (value == null) ps.setNull(index, java.sql.Types.VARCHAR);
            else ps.setString(index, value.toString());
            return;
        }
        ValueCodec.bind(ps, index, field, value);
    }
}
