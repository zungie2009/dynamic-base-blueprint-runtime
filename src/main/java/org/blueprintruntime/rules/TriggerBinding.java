package org.blueprintruntime.rules;

import java.util.List;

/**
 * Cross-table event propagation binding: the {@code eventTable} whose mutations
 * invoke this rule, which events fire it, and how to resolve the affected
 * {@code targetTable} record(s) via the {@code RELATED_PARENT} strategy.
 */
public record TriggerBinding(String eventTable, List<String> events, String referenceField,
                              String targetTable, String targetKeyField) {
}
