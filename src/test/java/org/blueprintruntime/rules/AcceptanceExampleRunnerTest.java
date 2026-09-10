package org.blueprintruntime.rules;

import org.blueprintruntime.blueprint.BlueprintResolver;
import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.ConfigurationLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every one of the sample rule set's declared acceptance examples — line-amount
 * arithmetic, cross-table aggregation, reparenting recalculating both former and new
 * parents, an in-place edit recalculating an unchanged parent chain, and delete
 * propagation — through the exact production {@link RuleEngine}/{@code
 * GenericCrudService} code paths, per {@code ruleAcceptanceExampleProtocol}. This is
 * the test the {@code deliveryGate} means by "all tests pass" for the rule engine: it
 * requires the real H2 driver on the test classpath (declared as a runtime dependency
 * in pom.xml, which Maven also places on the test classpath).
 */
class AcceptanceExampleRunnerTest {

    @Test
    void everySampleAcceptanceExamplePasses() {
        BusinessConfiguration config = ConfigurationLoader.loadFromFile(Path.of("samples/business-schema-config.jsonc"));
        ResolvedBusinessBlueprint blueprint = BlueprintResolver.resolve(config);
        BusinessRuleSet ruleSet = RuleSetLoader.loadFromFile(Path.of("samples/business-rules.jsonc"));

        assertTrue(RuleSetValidator.validate(ruleSet, blueprint).isEmpty(), "rule set must bind cleanly before acceptance examples run");
        assertTrue(ruleSet.acceptanceExamples().size() >= 5, "expected at least the 5 declared consulting examples");

        AcceptanceExampleRunner.Result result = new AcceptanceExampleRunner().runAll(blueprint, ruleSet);

        if (!result.passed()) {
            StringBuilder report = new StringBuilder("Acceptance example failures:\n");
            for (var failure : result.failures()) {
                report.append(" - ").append(failure.exampleName()).append(": ").append(failure.message()).append('\n');
            }
            throw new AssertionError(report.toString());
        }
    }
}
