package org.blueprintruntime.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A schema-agnostic row: a table id plus a {@code fieldId -> typed Java value} map.
 * The genericCrudContract requires exactly this representation — never a generated,
 * table-specific entity class — so the same runtime code handles every configured
 * table uniformly.
 */
public final class GenericRecord {

    private final String tableId;
    private final Map<String, Object> values;

    public GenericRecord(String tableId, Map<String, Object> values) {
        this.tableId = tableId;
        this.values = new LinkedHashMap<>(values);
    }

    public static GenericRecord empty(String tableId) {
        return new GenericRecord(tableId, new LinkedHashMap<>());
    }

    public String tableId() {
        return tableId;
    }

    public Object get(String fieldId) {
        return values.get(fieldId);
    }

    public void set(String fieldId, Object value) {
        values.put(fieldId, value);
    }

    public boolean has(String fieldId) {
        return values.containsKey(fieldId);
    }

    public Map<String, Object> values() {
        return values;
    }

    public GenericRecord copy() {
        return new GenericRecord(tableId, values);
    }

    @Override
    public String toString() {
        return tableId + values;
    }
}
