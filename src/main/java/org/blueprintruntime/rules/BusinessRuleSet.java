package org.blueprintruntime.rules;

import org.blueprintruntime.json.JsonValue.JObject;

import java.util.List;

/**
 * A fully parsed external {@code business-rules.jsonc}: identity, the schema it is
 * pinned to (by configurationId, schemaVersion, and an expected structural
 * fingerprint — all three must match before any rule is trusted), its rules, and its
 * acceptance examples.
 */
public record BusinessRuleSet(
        String ruleSetId,
        String version,
        String businessId,
        String schemaBindingConfigurationId,
        String schemaBindingSchemaVersion,
        String expectedSchemaFingerprint,
        int maxPasses,
        List<RuleDefinition> rules,
        List<JObject> acceptanceExamples
) {
    public BusinessRuleSet {
        rules = List.copyOf(rules);
        acceptanceExamples = List.copyOf(acceptanceExamples);
        if (maxPasses <= 0) maxPasses = 8;
    }

    public RuleDefinition rule(String ruleId) {
        return rules.stream().filter(r -> r.ruleId().equals(ruleId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown ruleId '" + ruleId + "'"));
    }
}
