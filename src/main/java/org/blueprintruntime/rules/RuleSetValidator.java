package org.blueprintruntime.rules;

import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.FieldDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Validates a {@link BusinessRuleSet} against the currently active
 * {@link ResolvedBusinessBlueprint} before it may become the active snapshot:
 * schema-fingerprint binding, every read/write/target/trigger/operator resolves
 * against the introspected schema, and every write targets a field declared
 * {@code ruleWritable=true} and {@code editable=false}.
 */
public final class RuleSetValidator {

    private static final Set<String> SUPPORTED_TRIGGERS = Set.of(
            "BEFORE_CREATE", "BEFORE_UPDATE", "AFTER_CREATE", "AFTER_UPDATE", "AFTER_DELETE");
    private static final Set<String> SUPPORTED_OPERATORS = Set.of(
            "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "SUM_RELATED");

    private RuleSetValidator() {
    }

    public static List<String> validate(BusinessRuleSet ruleSet, ResolvedBusinessBlueprint blueprint) {
        List<String> problems = new ArrayList<>();
        BusinessConfiguration config = blueprint.configuration();

        if (!ruleSet.businessId().equals(blueprint.businessId())) {
            problems.add("rule set businessId '" + ruleSet.businessId() + "' does not match active business '" + blueprint.businessId() + "'");
        }
        if (!ruleSet.schemaBindingConfigurationId().equals(config.configurationId())) {
            problems.add("rule set schemaBinding.configurationId '" + ruleSet.schemaBindingConfigurationId()
                    + "' does not match active configurationId '" + config.configurationId() + "'");
        }
        if (!ruleSet.schemaBindingSchemaVersion().equals(config.schemaVersion())) {
            problems.add("rule set schemaBinding.schemaVersion '" + ruleSet.schemaBindingSchemaVersion()
                    + "' does not match active schemaVersion '" + config.schemaVersion() + "'");
        }
        if (!ruleSet.expectedSchemaFingerprint().equals(blueprint.schemaFingerprint())) {
            problems.add("rule set expectedSchemaFingerprint does not match the active schema's fingerprint "
                    + "(expected " + ruleSet.expectedSchemaFingerprint() + ", active " + blueprint.schemaFingerprint() + ")");
        }

        for (RuleDefinition rule : ruleSet.rules()) {
            validateRule(config, rule, problems);
        }

        if (problems.isEmpty()) {
            try {
                RuleGraph.build(ruleSet.rules());
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        }
        return problems;
    }

    private static void validateRule(BusinessConfiguration config, RuleDefinition rule, List<String> problems) {
        String prefix = "rule '" + rule.ruleId() + "'";

        if (config.table(rule.targetTable()).isEmpty()) {
            problems.add(prefix + ": unknown targetTable '" + rule.targetTable() + "'");
        }
        for (String trigger : rule.firingEvents()) {
            if (!SUPPORTED_TRIGGERS.contains(trigger)) {
                problems.add(prefix + ": unsupported trigger '" + trigger + "'");
            }
        }
        if (rule.isCrossTable()) {
            TriggerBinding tb = rule.triggerBinding();
            var eventTable = config.table(tb.eventTable());
            if (eventTable.isEmpty()) {
                problems.add(prefix + ": triggerBinding.eventTable '" + tb.eventTable() + "' is unknown");
            } else {
                var refField = eventTable.get().field(tb.referenceField());
                if (refField.isEmpty()) {
                    problems.add(prefix + ": triggerBinding.referenceField '" + tb.referenceField() + "' is unknown on '" + tb.eventTable() + "'");
                }
            }
            if (config.table(tb.targetTable()).isEmpty()) {
                problems.add(prefix + ": triggerBinding.targetTable '" + tb.targetTable() + "' is unknown");
            }
        }
        if (!SUPPORTED_OPERATORS.contains(rule.expression().operator())) {
            problems.add(prefix + ": unsupported operator '" + rule.expression().operator() + "'");
        }

        for (FieldRef ref : rule.reads()) validateFieldRef(config, prefix, "reads", ref, problems);
        for (FieldRef ref : rule.writes()) {
            validateFieldRef(config, prefix, "writes", ref, problems);
            config.table(ref.table()).ifPresent(t -> t.field(ref.field()).ifPresent(f -> {
                if (!f.ruleWritable() || f.editable()) {
                    problems.add(prefix + ": writes '" + ref + "' which is not declared ruleWritable=true, editable=false");
                }
            }));
        }
    }

    private static void validateFieldRef(BusinessConfiguration config, String prefix, String kind, FieldRef ref, List<String> problems) {
        var table = config.table(ref.table());
        if (table.isEmpty()) {
            problems.add(prefix + ": " + kind + " references unknown table '" + ref.table() + "'");
            return;
        }
        Optional<FieldDefinition> field = table.get().field(ref.field());
        if (field.isEmpty()) {
            problems.add(prefix + ": " + kind + " references unknown field '" + ref + "'");
        }
    }
}
