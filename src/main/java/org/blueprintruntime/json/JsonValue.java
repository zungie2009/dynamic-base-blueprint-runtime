package org.blueprintruntime.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A minimal, dependency-free JSON value tree used to represent every configuration,
 * rule, and fixture document this runtime reads. The Base Blueprint contract forbids
 * any runtime dependency beyond the reference database, so this replaces a JSON
 * library rather than adding one.
 *
 * <p>Numbers are always represented internally as {@link BigDecimal} so that decimal
 * money values (which this project always encodes as JSON strings, e.g. {@code "2.00"})
 * and integral JSON number literals (menu order, scale, maxPasses) are both handled
 * without precision loss.</p>
 */
public sealed interface JsonValue {

    record JObject(Map<String, JsonValue> members) implements JsonValue {
        public JObject {
            members = new LinkedHashMap<>(members);
        }

        public boolean has(String key) {
            return members.containsKey(key) && !(members.get(key) instanceof JNull);
        }

        public JsonValue get(String key) {
            JsonValue v = members.get(key);
            return v == null ? JNull.INSTANCE : v;
        }

        public Optional<JsonValue> find(String key) {
            return has(key) ? Optional.of(get(key)) : Optional.empty();
        }

        public String getString(String key) {
            return get(key).asString();
        }

        public String getString(String key, String defaultValue) {
            return has(key) ? get(key).asString() : defaultValue;
        }

        public boolean getBool(String key, boolean defaultValue) {
            return has(key) ? get(key).asBoolean() : defaultValue;
        }

        public int getInt(String key, int defaultValue) {
            return has(key) ? get(key).asBigDecimal().intValueExact() : defaultValue;
        }

        public Integer getIntOrNull(String key) {
            return has(key) ? get(key).asBigDecimal().intValueExact() : null;
        }

        public JObject getObject(String key) {
            JsonValue v = get(key);
            if (v instanceof JObject o) return o;
            throw new JsonException("Expected object at '" + key + "' but found " + v.typeName());
        }

        public JObject getObjectOrEmpty(String key) {
            return has(key) ? getObject(key) : new JObject(Map.of());
        }

        public JArray getArray(String key) {
            JsonValue v = get(key);
            if (v instanceof JArray a) return a;
            throw new JsonException("Expected array at '" + key + "' but found " + v.typeName());
        }

        public JArray getArrayOrEmpty(String key) {
            return has(key) ? getArray(key) : new JArray(List.of());
        }

        public List<String> keysSorted() {
            List<String> keys = new ArrayList<>(members.keySet());
            keys.sort(String::compareTo);
            return keys;
        }
    }

    record JArray(List<JsonValue> items) implements JsonValue {
        public JArray {
            items = new ArrayList<>(items);
        }

        public int size() {
            return items.size();
        }

        public JsonValue get(int i) {
            return items.get(i);
        }

        public List<JObject> asObjectList() {
            List<JObject> result = new ArrayList<>();
            for (JsonValue v : items) {
                if (v instanceof JObject o) result.add(o);
                else throw new JsonException("Expected array of objects, found " + v.typeName());
            }
            return result;
        }

        public List<String> asStringList() {
            List<String> result = new ArrayList<>();
            for (JsonValue v : items) result.add(v.asString());
            return result;
        }
    }

    record JString(String value) implements JsonValue {
    }

    record JNumber(BigDecimal value) implements JsonValue {
    }

    record JBool(boolean value) implements JsonValue {
    }

    final class JNull implements JsonValue {
        public static final JNull INSTANCE = new JNull();

        private JNull() {
        }
    }

    default String typeName() {
        return switch (this) {
            case JObject ignored -> "object";
            case JArray ignored -> "array";
            case JString ignored -> "string";
            case JNumber ignored -> "number";
            case JBool ignored -> "boolean";
            case JNull ignored -> "null";
        };
    }

    default boolean isNull() {
        return this instanceof JNull;
    }

    default String asString() {
        if (this instanceof JString s) return s.value();
        if (this instanceof JNull) return null;
        throw new JsonException("Expected string but found " + typeName());
    }

    default String asStringOrNull() {
        return isNull() ? null : asString();
    }

    default boolean asBoolean() {
        if (this instanceof JBool b) return b.value();
        throw new JsonException("Expected boolean but found " + typeName());
    }

    default BigDecimal asBigDecimal() {
        if (this instanceof JNumber n) return n.value();
        if (this instanceof JString s) return new BigDecimal(s.value());
        throw new JsonException("Expected number but found " + typeName());
    }

    default int asInt() {
        return asBigDecimal().intValueExact();
    }

    /** Renders this value as canonical, comment-free, whitespace-free JSON text (used for fingerprinting and audit detail blobs). */
    default String toCanonicalJson() {
        StringBuilder sb = new StringBuilder();
        writeCanonical(this, sb);
        return sb.toString();
    }

    private static void writeCanonical(JsonValue v, StringBuilder sb) {
        switch (v) {
            case JObject o -> {
                sb.append('{');
                List<String> keys = o.keysSorted();
                for (int i = 0; i < keys.size(); i++) {
                    if (i > 0) sb.append(',');
                    writeJsonString(keys.get(i), sb);
                    sb.append(':');
                    writeCanonical(o.get(keys.get(i)), sb);
                }
                sb.append('}');
            }
            case JArray a -> {
                sb.append('[');
                for (int i = 0; i < a.size(); i++) {
                    if (i > 0) sb.append(',');
                    writeCanonical(a.get(i), sb);
                }
                sb.append(']');
            }
            case JString s -> writeJsonString(s.value(), sb);
            case JNumber n -> sb.append(n.value().stripTrailingZeros().toPlainString());
            case JBool b -> sb.append(b.value() ? "true" : "false");
            case JNull ignored -> sb.append("null");
        }
    }

    private static void writeJsonString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }
}
