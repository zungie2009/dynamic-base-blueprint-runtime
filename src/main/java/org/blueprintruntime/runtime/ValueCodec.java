package org.blueprintruntime.runtime;

import org.blueprintruntime.config.FieldDefinition;
import org.blueprintruntime.config.FieldType;
import org.blueprintruntime.json.JsonValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Converts values for one field between its external representation (an HTTP form
 * string or a JSON fixture value), its canonical in-memory Java type, and its JDBC
 * representation — the single place every numeric/date/boolean coercion rule in the
 * contract (BigDecimal for money, never a double round-trip; JSON numeric or HTTP
 * string normalized to Integer, etc.) is implemented once for every field type.
 */
public final class ValueCodec {

    private ValueCodec() {
    }

    /** Parses a value coming from an HTML form submission (always a String, possibly blank/absent). */
    public static Object parseFormValue(FieldDefinition field, String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        boolean blank = trimmed.isEmpty();
        if (field.type() == FieldType.STRING || field.type() == FieldType.EMAIL) {
            if (blank) {
                return field.normalizeBlankToNull() ? null : "";
            }
            return trimmed;
        }
        if (blank) return null;
        return switch (field.type()) {
            case INTEGER -> Integer.valueOf(trimmed);
            case DECIMAL_19_2 -> new BigDecimal(trimmed).setScale(2, RoundingMode.HALF_UP);
            case BOOLEAN -> Boolean.valueOf(trimmed.equalsIgnoreCase("true") || trimmed.equals("on") || trimmed.equals("1"));
            case DATE -> LocalDate.parse(trimmed);
            case DATETIME -> LocalDateTime.parse(trimmed.replace(' ', 'T'));
            case IDENTITY, ENUM, REFERENCE, TEXT -> trimmed;
            default -> trimmed;
        };
    }

    /** Parses a value coming from a parsed JSON fixture/document (already typed as JsonValue, but decimals are always JSON strings). */
    public static Object parseJsonValue(FieldDefinition field, JsonValue value) {
        if (value == null || value.isNull()) return null;
        return switch (field.type()) {
            case INTEGER -> value.asBigDecimal().intValueExact();
            case DECIMAL_19_2 -> value.asBigDecimal().setScale(2, RoundingMode.HALF_UP);
            case BOOLEAN -> value.asBoolean();
            case DATE -> LocalDate.parse(value.asString());
            case DATETIME -> LocalDateTime.parse(value.asString().replace(' ', 'T'));
            default -> value.asString();
        };
    }

    public static JsonValue toJson(FieldDefinition field, Object javaValue) {
        if (javaValue == null) return JsonValue.JNull.INSTANCE;
        return switch (field.type()) {
            case INTEGER -> new JsonValue.JNumber(BigDecimal.valueOf((Integer) javaValue));
            case DECIMAL_19_2 -> new JsonValue.JString(((BigDecimal) javaValue).setScale(2, RoundingMode.HALF_UP).toPlainString());
            case BOOLEAN -> new JsonValue.JBool((Boolean) javaValue);
            case DATE -> new JsonValue.JString(javaValue.toString());
            case DATETIME -> new JsonValue.JString(javaValue.toString());
            default -> new JsonValue.JString(javaValue.toString());
        };
    }

    public static String toDisplayString(FieldDefinition field, Object javaValue) {
        if (javaValue == null) return "";
        if (field.type() == FieldType.DECIMAL_19_2) {
            return ((BigDecimal) javaValue).setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        if (field.type() == FieldType.BOOLEAN) {
            return Boolean.TRUE.equals(javaValue) ? "true" : "false";
        }
        return javaValue.toString();
    }

    public static void bind(PreparedStatement ps, int index, FieldDefinition field, Object javaValue) throws SQLException {
        if (javaValue == null) {
            ps.setNull(index, sqlType(field));
            return;
        }
        switch (field.type()) {
            case INTEGER -> ps.setInt(index, (Integer) javaValue);
            case DECIMAL_19_2 -> ps.setBigDecimal(index, (BigDecimal) javaValue);
            case BOOLEAN -> ps.setBoolean(index, (Boolean) javaValue);
            case DATE -> ps.setDate(index, Date.valueOf((LocalDate) javaValue));
            case DATETIME -> ps.setTimestamp(index, Timestamp.valueOf((LocalDateTime) javaValue));
            default -> ps.setString(index, javaValue.toString());
        }
    }

    private static int sqlType(FieldDefinition field) {
        return switch (field.type()) {
            case INTEGER -> Types.INTEGER;
            case DECIMAL_19_2 -> Types.DECIMAL;
            case BOOLEAN -> Types.BOOLEAN;
            case DATE -> Types.DATE;
            case DATETIME -> Types.TIMESTAMP;
            default -> Types.VARCHAR;
        };
    }

    public static Object read(ResultSet rs, String columnLabel, FieldDefinition field) throws SQLException {
        return switch (field.type()) {
            case INTEGER -> {
                int v = rs.getInt(columnLabel);
                yield rs.wasNull() ? null : v;
            }
            case DECIMAL_19_2 -> {
                BigDecimal v = rs.getBigDecimal(columnLabel);
                yield v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
            }
            case BOOLEAN -> {
                boolean v = rs.getBoolean(columnLabel);
                yield rs.wasNull() ? null : v;
            }
            case DATE -> {
                Date v = rs.getDate(columnLabel);
                yield v == null ? null : v.toLocalDate();
            }
            case DATETIME -> {
                Timestamp v = rs.getTimestamp(columnLabel);
                yield v == null ? null : v.toLocalDateTime();
            }
            default -> rs.getString(columnLabel);
        };
    }

    /** Canonical typed equality used by the acceptance-example protocol: DECIMAL_19_2 compares by numeric value at required scale 2, everything else by Java equals(). */
    public static boolean equalsCanonical(FieldDefinition field, Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (field.type() == FieldType.DECIMAL_19_2) {
            return ((BigDecimal) a).setScale(2, RoundingMode.HALF_UP)
                    .compareTo(((BigDecimal) b).setScale(2, RoundingMode.HALF_UP)) == 0;
        }
        return a.equals(b);
    }
}
