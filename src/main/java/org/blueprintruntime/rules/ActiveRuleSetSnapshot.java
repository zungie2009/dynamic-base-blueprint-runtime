package org.blueprintruntime.rules;

import java.time.Instant;
import java.util.List;

/**
 * The immutable, validated rule set currently governing mutations, per {@code
 * candidateActivation}: replacing it is always an atomic swap of this whole object,
 * never a partial or in-place edit. Carries the fixed topological evaluation order
 * computed once at activation time so every mutation reuses it without recomputing.
 */
public record ActiveRuleSetSnapshot(BusinessRuleSet ruleSet, List<String> topologicalOrder, Instant activatedAt, String sourcePath) {

    public static ActiveRuleSetSnapshot activate(BusinessRuleSet ruleSet, String sourcePath) {
        RuleGraph graph = RuleGraph.build(ruleSet.rules());
        return new ActiveRuleSetSnapshot(ruleSet, graph.topologicalOrder(), Instant.now(), sourcePath);
    }

    public List<RuleDefinition> rulesInOrder() {
        return topologicalOrder.stream().map(ruleSet::rule).toList();
    }
}
