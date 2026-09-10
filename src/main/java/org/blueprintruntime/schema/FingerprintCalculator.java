package org.blueprintruntime.schema;

import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.FieldDefinition;
import org.blueprintruntime.config.TableDefinition;
import org.blueprintruntime.json.JsonValue;
import org.blueprintruntime.json.JsonValue.JArray;
import org.blueprintruntime.json.JsonValue.JBool;
import org.blueprintruntime.json.JsonValue.JNull;
import org.blueprintruntime.json.JsonValue.JObject;
import org.blueprintruntime.json.JsonValue.JString;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements {@code schemaFingerprintCanonicalization} exactly: projects a
 * {@link BusinessConfiguration} into the documented canonical shape (only structural
 * properties, excluding presentation such as labels, menu order, and locale), sorts
 * tables by tableId and fields by fieldId, serializes as compact comment-free UTF-8
 * JSON with lexicographically sorted object keys, and hashes with SHA-256.
 *
 * <p>Presentation-only changes must never change this value; structural changes
 * always must. Two independent implementations of this exact algorithm over the same
 * configuration must produce byte-identical output and therefore the same hash — that
 * is what lets a rule file pin an {@code expectedSchemaFingerprint} and have it mean
 * something.</p>
 */
public final class FingerprintCalculator {

    private FingerprintCalculator() {
    }

    public static String compute(BusinessConfiguration config) {
        JObject canonical = toCanonicalProjection(config);
        String serialized = canonical.toCanonicalJson();
        return sha256Hex(serialized);
    }

    public static JObject toCanonicalProjection(BusinessConfiguration config) {
        List<TableDefinition> sortedTables = new ArrayList<>(config.tables());
        sortedTables.sort((a, b) -> a.tableId().compareTo(b.tableId()));

        List<JsonValue> tableNodes = new ArrayList<>();
        for (TableDefinition table : sortedTables) {
            tableNodes.add(tableProjection(table));
        }

        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("businessId", new JString(config.businessId()));
        root.put("schemaVersion", new JString(config.schemaVersion()));
        root.put("tables", new JArray(tableNodes));
        return new JObject(root);
    }

    private static JObject tableProjection(TableDefinition table) {
        List<FieldDefinition> sortedFields = new ArrayList<>(table.fields());
        sortedFields.sort((a, b) -> a.fieldId().compareTo(b.fieldId()));

        List<JsonValue> fieldNodes = new ArrayList<>();
        for (FieldDefinition field : sortedFields) {
            fieldNodes.add(fieldProjection(field));
        }

        Map<String, JsonValue> node = new LinkedHashMap<>();
        node.put("tableId", new JString(table.tableId()));
        node.put("fields", new JArray(fieldNodes));
        return new JObject(node);
    }

    private static JObject fieldProjection(FieldDefinition field) {
        Map<String, JsonValue> node = new LinkedHashMap<>();
        node.put("blankPolicy", stringOrNull(field.blankPolicy()));
        node.put("defaultValue", stringOrNull(field.defaultValue()));
        node.put("editable", new JBool(field.editable()));
        node.put("fieldId", new JString(field.fieldId()));
        node.put("length", field.length() == null ? JNull.INSTANCE : new JsonValue.JNumber(java.math.BigDecimal.valueOf(field.length())));
        node.put("onDelete", stringOrNull(field.reference() == null ? null : field.reference().onDelete()));
        node.put("primaryKey", new JBool(field.primaryKey()));
        node.put("referenceFieldId", stringOrNull(field.reference() == null ? null : field.reference().fieldId()));
        node.put("referenceTableId", stringOrNull(field.reference() == null ? null : field.reference().tableId()));
        node.put("required", new JBool(field.required()));
        node.put("ruleWritable", new JBool(field.ruleWritable()));
        node.put("type", new JString(field.type().name()));
        node.put("unique", new JBool(field.unique()));
        node.put("uniqueComparison", stringOrNull(field.uniqueComparison()));
        return new JObject(node);
    }

    private static JsonValue stringOrNull(String s) {
        return s == null ? JNull.INSTANCE : new JString(s);
    }

    private static String sha256Hex(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK implementation", e);
        }
    }
}
