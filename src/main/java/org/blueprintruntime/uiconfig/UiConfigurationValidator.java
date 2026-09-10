package org.blueprintruntime.uiconfig;

import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.TableDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a {@link UiConfiguration} against the active {@link ResolvedBusinessBlueprint}
 * before its {@link UiProjectionSnapshot} may be resolved and the business UI started,
 * per {@code uiConfigurationContract.binding} and {@code .semanticRules}: identity/
 * schema-fingerprint binding, every {@code perTable} entry names a real table at most
 * once, every configured vocabulary value is one this format recognizes, and no
 * resolved per-table projection contradicts itself.
 */
public final class UiConfigurationValidator {

    private static final Set<String> ROW_MODES = Set.of("INLINE_EDITABLE", "READ_ONLY");
    private static final Set<String> ROW_ACTIONS = Set.of("VIEW", "EDIT", "SAVE", "DELETE");
    private static final Set<String> CREATE_PLACEMENTS = Set.of("INLINE_BELOW_LIST", "SEPARATE_ROUTE");
    private static final Set<String> SUCCESS_NAVIGATION = Set.of("STAY_ON_LIST", "GO_TO_VIEW");
    private static final Set<String> BLOCKED_DELETE_PRESENTATION = Set.of("INLINE_BANNER", "SEPARATE_ERROR_PAGE");
    private static final Set<String> STANDALONE_ROUTES = Set.of("KEEP_AS_FALLBACK", "REMOVE");
    private static final Set<String> SUCCESS_PRESENTATION = Set.of("INLINE_BANNER");

    private UiConfigurationValidator() {
    }

    public static List<String> validate(UiConfiguration ui, ResolvedBusinessBlueprint blueprint) {
        List<String> problems = new ArrayList<>();
        BusinessConfiguration config = blueprint.configuration();

        if (!ui.businessId().equals(blueprint.businessId())) {
            problems.add("UI configuration binding.businessId '" + ui.businessId()
                    + "' does not match active business '" + blueprint.businessId() + "'");
        }
        if (!ui.schemaConfigurationId().equals(config.configurationId())) {
            problems.add("UI configuration binding.schemaConfigurationId '" + ui.schemaConfigurationId()
                    + "' does not match active configurationId '" + config.configurationId() + "'");
        }
        if (!ui.schemaVersion().equals(config.schemaVersion())) {
            problems.add("UI configuration binding.schemaVersion '" + ui.schemaVersion()
                    + "' does not match active schemaVersion '" + config.schemaVersion() + "'");
        }
        if (!ui.expectedSchemaFingerprint().equals(blueprint.schemaFingerprint())) {
            problems.add("UI configuration binding.expectedSchemaFingerprint does not match the active schema's "
                    + "fingerprint (expected " + ui.expectedSchemaFingerprint() + ", active " + blueprint.schemaFingerprint() + ")");
        }

        validateVocabulary(ui.defaults(), "defaults", problems);

        Set<String> seenTables = new HashSet<>();
        for (var entry : ui.perTable().entrySet()) {
            String tableId = entry.getKey();
            if (!seenTables.add(tableId)) {
                problems.add("perTable override for '" + tableId + "' is declared more than once");
            }
            if (config.table(tableId).isEmpty()) {
                problems.add("perTable override references unknown table '" + tableId + "'");
            }
            validateVocabulary(entry.getValue(), "perTable[" + tableId + "]", problems);
        }

        // Contradiction-checking needs a fully resolved per-table projection, which is only
        // meaningful once every declared value is already known-recognized.
        if (problems.isEmpty()) {
            for (TableDefinition table : config.tables()) {
                validateResolvedContradictions(ui, table.tableId(), problems);
            }
        }
        return problems;
    }

    private static void validateVocabulary(UiSurfaceRule rule, String label, List<String> problems) {
        checkEnum(rule.rowMode(), ROW_MODES, label + ".listSurface.rowMode", problems);
        if (rule.rowActions() != null) {
            for (String action : rule.rowActions()) {
                checkEnum(action, ROW_ACTIONS, label + ".listSurface.rowActions", problems);
            }
        }
        checkEnum(rule.onSaveSuccess(), SUCCESS_NAVIGATION, label + ".listSurface.onSaveSuccess", problems);
        checkEnum(rule.createPlacement(), CREATE_PLACEMENTS, label + ".createSurface.placement", problems);
        checkEnum(rule.createOnSuccess(), SUCCESS_NAVIGATION, label + ".createSurface.onSuccess", problems);
        checkEnum(rule.standaloneViewEditRoutes(), STANDALONE_ROUTES, label + ".standaloneViewEditRoutes", problems);
        checkEnum(rule.blockedDeletePresentation(), BLOCKED_DELETE_PRESENTATION, label + ".feedback.blockedDelete", problems);
        checkEnum(rule.successPresentation(), SUCCESS_PRESENTATION, label + ".feedback.success", problems);
    }

    private static void checkEnum(String value, Set<String> allowed, String path, List<String> problems) {
        if (value != null && !allowed.contains(value)) {
            problems.add(path + ": unrecognized value '" + value + "' (allowed: " + allowed + ")");
        }
    }

    private static void validateResolvedContradictions(UiConfiguration ui, String tableId, List<String> problems) {
        UiSurfaceRule resolved = UiProjectionResolver.resolveTable(ui, tableId);
        String prefix = "table '" + tableId + "' resolved UI";

        if (resolved.isInlineEditable() && !resolved.actionEnabled("SAVE")) {
            problems.add(prefix + ": rowMode INLINE_EDITABLE must declare rowActions including SAVE");
        }
        if (!resolved.isInlineEditable() && resolved.actionEnabled("SAVE")) {
            problems.add(prefix + ": rowMode READ_ONLY must not declare rowActions including SAVE");
        }
        if (resolved.standaloneRoutesRemoved()) {
            if (!resolved.isInlineEditable()) {
                problems.add(prefix + ": standaloneViewEditRoutes REMOVE is invalid with rowMode READ_ONLY, "
                        + "which navigates to those routes");
            }
            if (resolved.actionEnabled("VIEW") || resolved.actionEnabled("EDIT")) {
                problems.add(prefix + ": standaloneViewEditRoutes REMOVE is invalid while rowActions still enables VIEW or EDIT");
            }
            if (!resolved.createInline()) {
                problems.add(prefix + ": standaloneViewEditRoutes REMOVE is invalid with createSurface.placement SEPARATE_ROUTE");
            }
        }
    }
}
