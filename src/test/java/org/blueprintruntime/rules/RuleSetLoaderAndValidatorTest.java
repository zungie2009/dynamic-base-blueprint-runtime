package org.blueprintruntime.rules;

import org.blueprintruntime.blueprint.BlueprintResolver;
import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.ConfigurationLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code dynamicRuleContract.binding}: a valid rule set binds only to declared
 * schema fields and its dependency graph has a valid topological order; a rule that
 * targets an undeclared field is rejected before it could ever be activated.
 */
class RuleSetLoaderAndValidatorTest {

    private static ResolvedBusinessBlueprint loadBlueprint() {
        BusinessConfiguration config = ConfigurationLoader.loadFromFile(Path.of("samples/business-schema-config.jsonc"));
        return BlueprintResolver.resolve(config);
    }

    @Test
    void sampleRuleSetBindsCleanlyAndOrdersItsDependencies() {
        ResolvedBusinessBlueprint blueprint = loadBlueprint();
        BusinessRuleSet ruleSet = RuleSetLoader.loadFromFile(Path.of("samples/business-rules.jsonc"));

        List<String> problems = RuleSetValidator.validate(ruleSet, blueprint);
        assertTrue(problems.isEmpty(), "expected no validation problems but found: " + problems);

        ActiveRuleSetSnapshot snapshot = ActiveRuleSetSnapshot.activate(ruleSet, "test");
        List<String> order = snapshot.topologicalOrder();
        assertEquals(4, order.size());
        // INVOICE_SUBTOTAL must precede INVOICE_TAX, which must precede INVOICE_TOTAL (declared dependsOn).
        assertTrue(order.indexOf("INVOICE_SUBTOTAL") < order.indexOf("INVOICE_TAX"));
        assertTrue(order.indexOf("INVOICE_TAX") < order.indexOf("INVOICE_TOTAL"));
    }

    @Test
    void ruleReferencingAnUndeclaredFieldIsRejected() {
        ResolvedBusinessBlueprint blueprint = loadBlueprint();
        String rulesText = """
                {
                  "ruleSet": {
                    "ruleSetId": "BAD_RULES", "version": "1", "businessId": "CONSULTING_DEMO",
                    "schemaBinding": {
                      "configurationId": "CONSULTING_SCHEMA_DEMO_V1_1",
                      "schemaVersion": "1",
                      "expectedSchemaFingerprint": "%s"
                    }
                  },
                  "rules": [
                    {
                      "ruleId": "BAD_RULE",
                      "targetTable": "invoices",
                      "triggers": ["AFTER_CREATE"],
                      "reads": [{ "table": "invoices", "field": "doesNotExist" }],
                      "writes": [{ "table": "invoices", "field": "subtotal" }],
                      "expression": { "operator": "ADD", "arguments": ["invoices.doesNotExist"], "scale": 2, "roundingMode": "HALF_UP" }
                    }
                  ]
                }
                """.formatted(blueprint.schemaFingerprint());

        BusinessRuleSet badRuleSet = RuleSetLoader.loadFromText(rulesText, "bad-rules-inline");
        List<String> problems = RuleSetValidator.validate(badRuleSet, blueprint);
        assertTrue(problems.stream().anyMatch(p -> p.contains("doesNotExist")),
                "expected a problem naming the unknown field 'doesNotExist' but got: " + problems);
    }

    @Test
    void ruleSetBoundToAStaleFingerprintIsRejected() {
        ResolvedBusinessBlueprint blueprint = loadBlueprint();
        String rulesText = """
                {
                  "ruleSet": {
                    "ruleSetId": "STALE_RULES", "version": "1", "businessId": "CONSULTING_DEMO",
                    "schemaBinding": {
                      "configurationId": "CONSULTING_SCHEMA_DEMO_V1_1",
                      "schemaVersion": "1",
                      "expectedSchemaFingerprint": "0000000000000000000000000000000000000000000000000000000000000000"
                    }
                  },
                  "rules": []
                }
                """;
        BusinessRuleSet staleRuleSet = RuleSetLoader.loadFromText(rulesText, "stale-rules-inline");
        List<String> problems = RuleSetValidator.validate(staleRuleSet, blueprint);
        assertTrue(problems.stream().anyMatch(p -> p.toLowerCase().contains("fingerprint")));
    }

    @Test
    void malformedRuleFileIsRejected() {
        assertThrows(RuntimeException.class, () -> RuleSetLoader.loadFromText("{ not valid json", "malformed"));
    }
}
