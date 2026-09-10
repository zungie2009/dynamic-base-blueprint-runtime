package org.blueprintruntime.uiconfig;

import org.blueprintruntime.config.TableDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges the built-in safe fallback, the file's {@code defaults}, and any matching
 * {@code perTable} override into one fully-resolved {@link UiSurfaceRule} per
 * configured table, per {@code composition.resolutionOrder}: {@code
 * BUILT_IN_SAFE_FALLBACK}, then {@code UI_CONFIGURATION_DEFAULTS}, then {@code
 * PER_TABLE_OVERRIDE}.
 */
public final class UiProjectionResolver {

    /**
     * The behavior this runtime had before the UI file existed at all: inline-editable
     * rows with Save/Delete, an inline-below-list create form, staying on the list
     * after either succeeds, standalone view/edit routes always reachable, and a
     * RESTRICT delete failure shown as an inline banner. A {@code business-ui.jsonc}
     * that omits a property gets this rather than an undefined value.
     */
    public static final UiSurfaceRule BUILT_IN_SAFE_FALLBACK = new UiSurfaceRule(
            "INLINE_EDITABLE", List.of("SAVE", "DELETE"), "STAY_ON_LIST",
            "INLINE_BELOW_LIST", "GO_TO_VIEW", "KEEP_AS_FALLBACK",
            "INLINE_BANNER", "This record cannot be deleted because other records still reference it.",
            null
    );

    private UiProjectionResolver() {
    }

    public static UiSurfaceRule resolveTable(UiConfiguration ui, String tableId) {
        return BUILT_IN_SAFE_FALLBACK.overrideWith(ui.defaults()).overrideWith(ui.perTable().get(tableId));
    }

    public static UiProjectionSnapshot resolve(UiConfiguration ui, Iterable<TableDefinition> tables) {
        Map<String, UiSurfaceRule> resolved = new LinkedHashMap<>();
        for (TableDefinition table : tables) {
            resolved.put(table.tableId(), resolveTable(ui, table.tableId()));
        }
        return new UiProjectionSnapshot(ui.uiConfigurationId(), resolved, ui.themeTokens(), ui.fontFamily());
    }
}
