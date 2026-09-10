package org.blueprintruntime.rules;

import org.blueprintruntime.config.ConfigurationException;
import org.blueprintruntime.json.JsonParser;
import org.blueprintruntime.json.JsonValue;
import org.blueprintruntime.json.JsonValue.JObject;
import org.blueprintruntime.rules.RuleExpression.Argument;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parses an external {@code business-rules.jsonc} file into a {@link BusinessRuleSet}. */
public final class RuleSetLoader {

    private RuleSetLoader() {
    }

    public static BusinessRuleSet loadFromFile(Path path) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new ConfigurationException("Could not read rule file '" + path + "': " + e.getMessage());
        }
        return loadFromText(text, path.toString());
    }

    public static BusinessRuleSet loadFromText(String text, String sourceName) {
        JsonValue root;
        try {
            root = JsonParser.parse(text);
        } catch (JsonValue.JsonException e) {
            throw new ConfigurationException("Rule file '" + sourceName + "' is not valid JSON: " + e.getMessage());
        }
        if (!(root instanceof JObject obj)) {
            throw new ConfigurationException("Rule file '" + sourceName + "' must be a JSON object");
        }
        List<String> problems = new ArrayList<>();
        try {
            JObject ruleSet = obj.getObject("ruleSet");
            JObject binding = ruleSet.getObject("schemaBinding");
            List<RuleDefinition> rules = new ArrayList<>();
            for (JObject r : obj.getArrayOrEmpty("rules").asObjectList()) {
                try {
                    rules.add(parseRule(r));
                } catch (RuntimeException e) {
                    problems.add("rule '" + r.getString("ruleId", "?") + "': " + e.getMessage());
                }
            }
            if (!problems.isEmpty()) {
                throw new ConfigurationException("Rule file '" + sourceName + "' is semantically invalid", problems);
            }
            return new BusinessRuleSet(
                    ruleSet.getString("ruleSetId"),
                    ruleSet.getString("version"),
                    ruleSet.getString("businessId"),
                    binding.getString("configurationId"),
                    binding.getString("schemaVersion"),
                    binding.getString("expectedSchemaFingerprint"),
                    ruleSet.getInt("maxPasses", 8),
                    rules,
                    obj.getArrayOrEmpty("acceptanceExamples").asObjectList()
            );
        } catch (ConfigurationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ConfigurationException("Rule file '" + sourceName + "' is semantically invalid", List.of(e.getMessage()));
        }
    }

    private static RuleDefinition parseRule(JObject r) {
        List<FieldRef> reads = parseFieldRefs(r.getArrayOrEmpty("reads"));
        List<FieldRef> writes = parseFieldRefs(r.getArrayOrEmpty("writes"));
        List<String> dependsOn = r.has("dependsOn") ? r.getArray("dependsOn").asStringList() : List.of();

        TriggerBinding binding = null;
        List<String> triggers = List.of();
        if (r.has("triggerBinding")) {
            JObject tb = r.getObject("triggerBinding");
            JObject affected = tb.getObject("affectedRecords");
            if (!"RELATED_PARENT".equals(affected.getString("strategy"))) {
                throw new IllegalArgumentException("Unsupported affectedRecords.strategy '" + affected.getString("strategy") + "'");
            }
            binding = new TriggerBinding(
                    tb.getString("eventTable"),
                    tb.getArray("events").asStringList(),
                    affected.getString("referenceField"),
                    affected.getString("targetTable"),
                    affected.getString("targetKeyField")
            );
        } else {
            triggers = r.getArray("triggers").asStringList();
        }

        JObject expr = r.getObject("expression");
        RuleExpression expression = parseExpression(expr);

        return new RuleDefinition(
                r.getString("ruleId"),
                r.getString("description", null),
                r.getString("targetTable"),
                triggers,
                binding,
                reads,
                writes,
                dependsOn,
                expression
        );
    }

    private static List<FieldRef> parseFieldRefs(JsonValue.JArray array) {
        List<FieldRef> refs = new ArrayList<>();
        for (JObject o : array.asObjectList()) {
            refs.add(new FieldRef(o.getString("table"), o.getString("field")));
        }
        return refs;
    }

    private static RuleExpression parseExpression(JObject expr) {
        String operator = expr.getString("operator");
        int scale = expr.getInt("scale", 2);
        RoundingMode roundingMode = RoundingMode.valueOf(expr.getString("roundingMode", "HALF_UP"));
        if ("SUM_RELATED".equals(operator)) {
            BigDecimal emptyResult = expr.has("emptyResult") ? new BigDecimal(expr.getString("emptyResult")) : BigDecimal.ZERO;
            return new RuleExpression(operator, List.of(), expr.getString("source"), expr.getString("where"), emptyResult, scale, roundingMode);
        }
        List<Argument> arguments = new ArrayList<>();
        for (JsonValue argValue : expr.getArray("arguments").items()) {
            if (argValue instanceof JsonValue.JString s) {
                arguments.add(new Argument.FieldArgument(FieldRef.parseDotted(s.value())));
            } else if (argValue instanceof JObject o) {
                arguments.add(new Argument.ConstantArgument(new BigDecimal(o.getString("constant"))));
            } else {
                throw new IllegalArgumentException("Unsupported argument shape: " + argValue.typeName());
            }
        }
        return new RuleExpression(operator, arguments, null, null, null, scale, roundingMode);
    }
}
