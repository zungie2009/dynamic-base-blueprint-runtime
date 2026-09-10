package org.blueprintruntime.rules;

import java.util.List;

/**
 * One declared rule: what it reads, what it writes, when it fires (either same-table
 * {@code triggers} or a cross-table {@code triggerBinding}), and the calculation it
 * performs. A rule is same-table when {@code triggerBinding} is null; cross-table
 * otherwise — never both.
 */
public record RuleDefinition(
        String ruleId,
        String description,
        String targetTable,
        List<String> triggers,
        TriggerBinding triggerBinding,
        List<FieldRef> reads,
        List<FieldRef> writes,
        List<String> dependsOn,
        RuleExpression expression
) {
    public RuleDefinition {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        reads = List.copyOf(reads);
        writes = List.copyOf(writes);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        if (triggers.isEmpty() && triggerBinding == null) {
            throw new IllegalArgumentException("Rule '" + ruleId + "' declares neither triggers nor a triggerBinding");
        }
        if (!triggers.isEmpty() && triggerBinding != null) {
            throw new IllegalArgumentException("Rule '" + ruleId + "' declares both triggers and a triggerBinding");
        }
    }

    public boolean isCrossTable() {
        return triggerBinding != null;
    }

    /** The table whose mutation should be checked against this rule's trigger set: the eventTable for cross-table rules, otherwise the targetTable itself. */
    public String eventTable() {
        return isCrossTable() ? triggerBinding.eventTable() : targetTable;
    }

    public List<String> firingEvents() {
        return isCrossTable() ? triggerBinding.events() : triggers;
    }
}
