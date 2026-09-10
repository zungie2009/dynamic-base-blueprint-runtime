package org.blueprintruntime.uiconfig;

import java.util.Map;

/**
 * The immutable, fully-resolved UI projection for the active business: one
 * {@link UiSurfaceRule} per configured table, with every property populated (no more
 * inheritance left to resolve). {@code uiReloadPolicy: RESTART_REQUIRED_FOR_V1_5}
 * means exactly one of these is built at startup and held for the life of the process.
 */
public record UiProjectionSnapshot(String uiConfigurationId, Map<String, UiSurfaceRule> perTable,
                                    Map<String, String> themeTokens, String fontFamily) {
    public UiProjectionSnapshot {
        perTable = Map.copyOf(perTable);
        themeTokens = themeTokens == null ? Map.of() : Map.copyOf(themeTokens);
    }

    public UiSurfaceRule forTable(String tableId) {
        UiSurfaceRule rule = perTable.get(tableId);
        if (rule == null) {
            throw new IllegalArgumentException("No resolved UI projection for table '" + tableId + "'");
        }
        return rule;
    }
}
