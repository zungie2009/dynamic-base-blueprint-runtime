package org.blueprintruntime.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cross-referential structural checks that a single {@link TableDefinition} or
 * {@link FieldDefinition} cannot verify on its own: duplicate identifiers, dangling
 * foreign keys, a missing or wrongly-typed primary key, and an unresolved
 * {@code displayField}. Collects every problem instead of stopping at the first, so
 * the failure report is actually useful.
 *
 * <p>A cyclic relationship graph is deliberately NOT rejected here — the
 * automatic-schema-provisioning contract explicitly tolerates cycles by creating all
 * tables before adding any foreign key constraint, so that is a planning concern for
 * {@link org.blueprintruntime.schema.SchemaPlanner}, not a validation failure.</p>
 */
public final class ConfigurationValidator {

    private ConfigurationValidator() {
    }

    public static List<String> validate(BusinessConfiguration config) {
        List<String> problems = new ArrayList<>();
        Set<String> tableIds = new HashSet<>();
        for (TableDefinition table : config.tables()) {
            if (!tableIds.add(table.tableId())) {
                problems.add("duplicate tableId '" + table.tableId() + "'");
            }
        }

        for (TableDefinition table : config.tables()) {
            validateTable(config, table, problems);
        }
        return problems;
    }

    private static void validateTable(BusinessConfiguration config, TableDefinition table, List<String> problems) {
        String tid = table.tableId();
        Set<String> fieldIds = new HashSet<>();
        int primaryKeyCount = 0;

        for (FieldDefinition field : table.fields()) {
            if (!fieldIds.add(field.fieldId())) {
                problems.add("table '" + tid + "': duplicate fieldId '" + field.fieldId() + "'");
            }
            if (field.primaryKey()) {
                primaryKeyCount++;
                if (field.type() != FieldType.IDENTITY) {
                    problems.add("table '" + tid + "': primary key field '" + field.fieldId() + "' must be type IDENTITY");
                }
            }
            if ((field.type() == FieldType.STRING || field.type() == FieldType.EMAIL)
                    && field.length() != null && field.length() <= 0) {
                problems.add("table '" + tid + "': field '" + field.fieldId() + "' has non-positive length");
            }
            if (field.type() == FieldType.REFERENCE) {
                validateReference(config, tid, field, problems);
            }
            if (field.ruleWritable() && field.editable()) {
                problems.add("table '" + tid + "': field '" + field.fieldId()
                        + "' is ruleWritable but also user-editable; a calculated output must be editable=false");
            }
        }

        if (primaryKeyCount == 0) {
            problems.add("table '" + tid + "': no primary key field declared");
        } else if (primaryKeyCount > 1) {
            problems.add("table '" + tid + "': more than one primary key field declared");
        }

        if (table.displayField() == null || table.displayField().isBlank()) {
            problems.add("table '" + tid + "': displayField is required");
        } else if (!fieldIds.contains(table.displayField())) {
            problems.add("table '" + tid + "': displayField '" + table.displayField() + "' is not a declared field");
        }
    }

    private static void validateReference(BusinessConfiguration config, String tableId, FieldDefinition field, List<String> problems) {
        ReferenceDefinition ref = field.reference();
        var targetTable = config.table(ref.tableId());
        if (targetTable.isEmpty()) {
            problems.add("table '" + tableId + "': field '" + field.fieldId()
                    + "' references unknown table '" + ref.tableId() + "'");
            return;
        }
        var targetField = targetTable.get().field(ref.fieldId());
        if (targetField.isEmpty()) {
            problems.add("table '" + tableId + "': field '" + field.fieldId()
                    + "' references unknown field '" + ref.tableId() + "." + ref.fieldId() + "'");
        } else if (!(targetField.get().primaryKey() || targetField.get().unique())) {
            problems.add("table '" + tableId + "': field '" + field.fieldId()
                    + "' references '" + ref.tableId() + "." + ref.fieldId() + "' which is neither a primary key nor unique");
        }
        if (!"RESTRICT".equals(ref.onDelete())) {
            problems.add("table '" + tableId + "': field '" + field.fieldId()
                    + "' declares unsupported onDelete policy '" + ref.onDelete() + "' (only RESTRICT is supported in V1)");
        }
    }
}
