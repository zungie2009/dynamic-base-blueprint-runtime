package org.blueprintruntime.uiconfig;

import org.blueprintruntime.config.ConfigurationException;
import org.blueprintruntime.json.JsonParser;
import org.blueprintruntime.json.JsonValue;
import org.blueprintruntime.json.JsonValue.JObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses an external {@code business-ui.jsonc} file — the fourth construction layer,
 * per {@code uiConfigurationContract} — into a {@link UiConfiguration}. Purely
 * structural parsing: schema/rule binding and vocabulary/contradiction checking
 * happen in {@link UiConfigurationValidator}, not here.
 */
public final class UiConfigurationLoader {

    private UiConfigurationLoader() {
    }

    public static UiConfiguration loadFromFile(Path path) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new ConfigurationException("Could not read UI file '" + path + "': " + e.getMessage());
        }
        return loadFromText(text, path.toString());
    }

    public static UiConfiguration loadFromText(String text, String sourceName) {
        JsonValue root;
        try {
            root = JsonParser.parse(text);
        } catch (JsonValue.JsonException e) {
            throw new ConfigurationException("UI file '" + sourceName + "' is not valid JSON: " + e.getMessage());
        }
        if (!(root instanceof JObject obj)) {
            throw new ConfigurationException("UI file '" + sourceName + "' must be a JSON object");
        }
        try {
            JObject binding = obj.getObject("binding");
            UiSurfaceRule defaults = parseSurfaceRule(obj.getObjectOrEmpty("defaults"), sourceName, "defaults");

            Map<String, UiSurfaceRule> perTable = new LinkedHashMap<>();
            for (JObject entry : obj.getArrayOrEmpty("perTable").asObjectList()) {
                String tableId = entry.getString("tableId");
                if (perTable.containsKey(tableId)) {
                    throw new ConfigurationException("UI file '" + sourceName + "' declares a perTable override for '"
                            + tableId + "' more than once");
                }
                perTable.put(tableId, parseSurfaceRule(entry, sourceName, "perTable[" + tableId + "]"));
            }

            JObject theme = obj.getObjectOrEmpty("theme");
            JObject tokensObj = theme.getObjectOrEmpty("tokens");
            Map<String, String> themeTokens = new LinkedHashMap<>();
            for (String key : tokensObj.keysSorted()) {
                themeTokens.put(key, scalarToString(tokensObj.get(key)));
            }
            String fontFamily = theme.getString("fontFamily", null);

            return new UiConfiguration(
                    obj.getString("uiConfigurationId"),
                    binding.getString("businessId"),
                    binding.getString("schemaConfigurationId"),
                    binding.getString("schemaVersion"),
                    binding.getString("expectedSchemaFingerprint"),
                    defaults,
                    perTable,
                    themeTokens,
                    fontFamily
            );
        } catch (ConfigurationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ConfigurationException("UI file '" + sourceName + "' is semantically invalid", List.of(e.getMessage()));
        }
    }

    /**
     * Reads one layer's overridable properties from wherever this format places them
     * ({@code listSurface}/{@code createSurface}/{@code standaloneViewEditRoutes}/
     * {@code feedback}), leaving a property {@code null} when this layer does not
     * mention it at all — per {@code composition.resolutionOrder}, {@code null} here
     * means "inherit from the layer beneath," resolved later by {@link
     * UiProjectionResolver}. A property present with a literal JSON {@code null} is
     * rejected outright instead of being treated as "inherit," per {@code
     * composition.nullRule}: "null is not an implicit deletion instruction."
     */
    private static UiSurfaceRule parseSurfaceRule(JObject layer, String sourceName, String layerName) {
        JObject listSurface = layer.getObjectOrEmpty("listSurface");
        JObject createSurface = layer.getObjectOrEmpty("createSurface");
        JObject feedback = layer.getObjectOrEmpty("feedback");

        rejectExplicitNull(listSurface, "rowMode", sourceName, layerName + ".listSurface");
        rejectExplicitNull(listSurface, "rowActions", sourceName, layerName + ".listSurface");
        rejectExplicitNull(listSurface, "onSaveSuccess", sourceName, layerName + ".listSurface");
        rejectExplicitNull(createSurface, "placement", sourceName, layerName + ".createSurface");
        rejectExplicitNull(createSurface, "onSuccess", sourceName, layerName + ".createSurface");
        rejectExplicitNull(layer, "standaloneViewEditRoutes", sourceName, layerName);
        rejectExplicitNull(feedback, "blockedDelete", sourceName, layerName + ".feedback");
        rejectExplicitNull(feedback, "blockedDeleteMessage", sourceName, layerName + ".feedback");
        rejectExplicitNull(feedback, "success", sourceName, layerName + ".feedback");

        return new UiSurfaceRule(
                listSurface.getString("rowMode", null),
                listSurface.has("rowActions") ? listSurface.getArray("rowActions").asStringList() : null,
                listSurface.getString("onSaveSuccess", null),
                createSurface.getString("placement", null),
                createSurface.getString("onSuccess", null),
                layer.getString("standaloneViewEditRoutes", null),
                feedback.getString("blockedDelete", null),
                feedback.getString("blockedDeleteMessage", null),
                feedback.getString("success", null)
        );
    }

    private static void rejectExplicitNull(JObject obj, String key, String sourceName, String path) {
        if (obj.members().containsKey(key) && obj.get(key).isNull()) {
            throw new ConfigurationException("UI file '" + sourceName + "' sets '" + path + "." + key
                    + "' to null; omit the property to inherit instead, per composition.nullRule");
        }
    }

    /** A theme token value may be authored as a JSON string ({@code "#2457D6"}) or a bare number ({@code 10}). */
    private static String scalarToString(JsonValue value) {
        if (value instanceof JsonValue.JString s) return s.value();
        if (value instanceof JsonValue.JNumber n) return n.value().toPlainString();
        if (value instanceof JsonValue.JBool b) return String.valueOf(b.value());
        throw new ConfigurationException("Theme token has unsupported value type " + value.typeName());
    }
}
