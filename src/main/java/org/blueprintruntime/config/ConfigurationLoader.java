package org.blueprintruntime.config;

import org.blueprintruntime.json.JsonParser;
import org.blueprintruntime.json.JsonValue;
import org.blueprintruntime.json.JsonValue.JObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the external {@code business-schema-config.jsonc} file and parses it into a
 * {@link BusinessConfiguration}. Parsing failures (malformed JSON, missing required
 * fields, unknown field types) are collected into one {@link ConfigurationException}
 * rather than surfacing a bare stack trace, per the Base Blueprint's failure policy:
 * never provision a partial schema or start the UI from a broken configuration.
 */
public final class ConfigurationLoader {

    private ConfigurationLoader() {
    }

    public static BusinessConfiguration loadFromFile(Path path) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new ConfigurationException("Could not read configuration file '" + path + "': " + e.getMessage());
        } catch (UncheckedIOException e) {
            throw new ConfigurationException("Could not read configuration file '" + path + "': " + e.getMessage());
        }
        return loadFromText(text, path.toString());
    }

    public static BusinessConfiguration loadFromText(String text, String sourceName) {
        JsonValue root;
        try {
            root = JsonParser.parse(text);
        } catch (JsonValue.JsonException e) {
            throw new ConfigurationException("Configuration file '" + sourceName + "' is not valid JSON: " + e.getMessage());
        }
        if (!(root instanceof JObject obj)) {
            throw new ConfigurationException("Configuration file '" + sourceName + "' must be a JSON object");
        }
        List<String> problems = new ArrayList<>();
        BusinessConfiguration config = parse(obj, problems);
        if (!problems.isEmpty() || config == null) {
            throw new ConfigurationException("Configuration file '" + sourceName + "' is semantically invalid", problems);
        }
        List<String> structural = ConfigurationValidator.validate(config);
        if (!structural.isEmpty()) {
            throw new ConfigurationException("Configuration file '" + sourceName + "' failed structural validation", structural);
        }
        return config;
    }

    private static BusinessConfiguration parse(JObject root, List<String> problems) {
        try {
            JObject business = root.getObject("business");
            List<TableDefinition> tables = new ArrayList<>();
            for (JObject tableJson : root.getArrayOrEmpty("tables").asObjectList()) {
                try {
                    tables.add(parseTable(tableJson));
                } catch (RuntimeException e) {
                    problems.add("table '" + tableJson.getString("tableId", "?") + "': " + e.getMessage());
                }
            }
            if (!problems.isEmpty()) return null;
            return new BusinessConfiguration(
                    root.getString("configurationId"),
                    business.getString("businessId"),
                    business.getString("businessName", null),
                    business.getString("schemaVersion"),
                    business.getString("locale", null),
                    tables
            );
        } catch (RuntimeException e) {
            problems.add(e.getMessage());
            return null;
        }
    }

    private static TableDefinition parseTable(JObject t) {
        List<FieldDefinition> fields = new ArrayList<>();
        for (JObject f : t.getArrayOrEmpty("fields").asObjectList()) {
            fields.add(parseField(f));
        }
        return new TableDefinition(
                t.getString("tableId"),
                t.getString("menuLabel", null),
                t.getInt("menuOrder", 0),
                t.getString("singularLabel", null),
                t.getString("displayField"),
                fields
        );
    }

    private static FieldDefinition parseField(JObject f) {
        FieldType type = FieldType.fromConfig(f.getString("type"));
        ReferenceDefinition ref = null;
        if (f.has("references")) {
            JObject r = f.getObject("references");
            ref = new ReferenceDefinition(r.getString("tableId"), r.getString("fieldId"), r.getString("onDelete", "RESTRICT"));
        }
        List<String> enumValues = f.has("enumValues") ? f.getArray("enumValues").asStringList() : List.of();
        return new FieldDefinition(
                f.getString("fieldId"),
                f.getString("label", null),
                type,
                f.getIntOrNull("length"),
                f.getBool("primaryKey", false),
                f.getBool("required", false),
                f.getBool("editable", true),
                f.getBool("unique", false),
                f.getString("blankPolicy", null),
                f.getString("uniqueComparison", null),
                f.getString("defaultValue", null),
                f.getBool("ruleWritable", false),
                enumValues,
                ref
        );
    }
}
