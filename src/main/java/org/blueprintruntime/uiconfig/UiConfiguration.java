package org.blueprintruntime.uiconfig;

import java.util.Map;

/**
 * The fully parsed external {@code business-ui.jsonc} — the fourth construction layer
 * per {@code uiConfigurationContract}: identity/schema binding, the {@code defaults}
 * every table inherits, and any {@code perTable} overrides, keyed by {@code tableId}.
 * Purely presentation/interaction authority: this type has no way to declare a table,
 * a field, or a calculation, so it cannot redefine schema or rule authority by
 * construction, per {@code binding.authorityBoundary}.
 */
public record UiConfiguration(
        String uiConfigurationId,
        String businessId,
        String schemaConfigurationId,
        String schemaVersion,
        String expectedSchemaFingerprint,
        UiSurfaceRule defaults,
        Map<String, UiSurfaceRule> perTable,
        Map<String, String> themeTokens,
        String fontFamily
) {
    public UiConfiguration {
        perTable = perTable == null ? Map.of() : Map.copyOf(perTable);
        themeTokens = themeTokens == null ? Map.of() : Map.copyOf(themeTokens);
    }
}
