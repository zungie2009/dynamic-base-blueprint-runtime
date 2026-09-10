package org.blueprintruntime.config;

import java.util.List;
import java.util.Optional;

/**
 * The fully parsed external {@code business-schema-config.jsonc}: business identity
 * plus every table it declares. This object, and nothing hardcoded in Java, is the
 * single source of truth for what tables, fields, menus, and relationships exist.
 */
public record BusinessConfiguration(
        String configurationId,
        String businessId,
        String businessName,
        String schemaVersion,
        String locale,
        List<TableDefinition> tables
) {
    public BusinessConfiguration {
        if (configurationId == null || configurationId.isBlank())
            throw new IllegalArgumentException("configurationId is required");
        if (businessId == null || businessId.isBlank())
            throw new IllegalArgumentException("business.businessId is required");
        if (schemaVersion == null || schemaVersion.isBlank())
            throw new IllegalArgumentException("business.schemaVersion is required");
        if (tables == null || tables.isEmpty())
            throw new IllegalArgumentException("configuration must declare at least one table");
        tables = List.copyOf(tables);
        if (businessName == null || businessName.isBlank()) businessName = businessId;
        if (locale == null || locale.isBlank()) locale = "en-US";
    }

    public Optional<TableDefinition> table(String tableId) {
        return tables.stream().filter(t -> t.tableId().equals(tableId)).findFirst();
    }

    public TableDefinition requireTable(String tableId) {
        return table(tableId).orElseThrow(() ->
                new IllegalArgumentException("Configuration declares no table '" + tableId + "'"));
    }

    public List<TableDefinition> tablesByMenuOrder() {
        return tables.stream()
                .sorted((a, b) -> Integer.compare(a.menuOrder(), b.menuOrder()))
                .toList();
    }
}
