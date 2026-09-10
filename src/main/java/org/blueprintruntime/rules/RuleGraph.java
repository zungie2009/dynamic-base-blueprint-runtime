package org.blueprintruntime.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the rule dependency graph from declared {@code reads}/{@code writes}
 * overlaps plus explicit {@code dependsOn} edges, and produces one fixed topological
 * evaluation order. An undeclared cycle is rejected outright, per {@code cyclePolicy}
 * — this runtime never attempts arbitrary fixed-point iteration to work around one.
 */
public final class RuleGraph {

    private final List<String> topologicalOrder;

    private RuleGraph(List<String> topologicalOrder) {
        this.topologicalOrder = topologicalOrder;
    }

    public List<String> topologicalOrder() {
        return topologicalOrder;
    }

    public static RuleGraph build(List<RuleDefinition> rules) {
        Map<String, RuleDefinition> byId = new HashMap<>();
        for (RuleDefinition r : rules) byId.put(r.ruleId(), r);

        Map<String, Set<String>> edges = new HashMap<>(); // from -> {to}
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : byId.keySet()) {
            edges.put(id, new HashSet<>());
            inDegree.put(id, 0);
        }

        // Explicit dependsOn: dependency must run before this rule.
        for (RuleDefinition r : rules) {
            for (String dep : r.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    throw new IllegalArgumentException("Rule '" + r.ruleId() + "' depends on unknown rule '" + dep + "'");
                }
                addEdge(edges, inDegree, dep, r.ruleId());
            }
        }

        // Implicit: a write of rule A that some rule B reads means A must run before B.
        for (RuleDefinition writer : rules) {
            for (FieldRef write : writer.writes()) {
                for (RuleDefinition reader : rules) {
                    if (reader == writer) continue;
                    if (reader.reads().contains(write)) {
                        addEdge(edges, inDegree, writer.ruleId(), reader.ruleId());
                    }
                }
            }
        }

        List<String> order = new ArrayList<>();
        Deque<String> ready = new ArrayDeque<>();
        for (var e : inDegree.entrySet()) {
            if (e.getValue() == 0) ready.add(e.getKey());
        }
        // Deterministic order for ties: sort the initial ready set.
        List<String> initial = new ArrayList<>(ready);
        initial.sort(String::compareTo);
        ready.clear();
        ready.addAll(initial);

        while (!ready.isEmpty()) {
            String id = ready.pollFirst();
            order.add(id);
            List<String> next = new ArrayList<>(edges.get(id));
            next.sort(String::compareTo);
            for (String to : next) {
                int remaining = inDegree.merge(to, -1, Integer::sum);
                if (remaining == 0) ready.addLast(to);
            }
        }

        if (order.size() != byId.size()) {
            Set<String> unresolved = new HashSet<>(byId.keySet());
            unresolved.removeAll(order);
            throw new IllegalArgumentException("Rule set contains an undeclared dependency cycle involving: " + unresolved);
        }
        return new RuleGraph(order);
    }

    private static void addEdge(Map<String, Set<String>> edges, Map<String, Integer> inDegree, String from, String to) {
        if (edges.get(from).add(to)) {
            inDegree.merge(to, 1, Integer::sum);
        }
    }
}
