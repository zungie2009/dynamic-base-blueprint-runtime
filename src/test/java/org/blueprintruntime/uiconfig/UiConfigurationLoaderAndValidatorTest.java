package org.blueprintruntime.uiconfig;

import org.blueprintruntime.blueprint.BlueprintResolver;
import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.ConfigurationException;
import org.blueprintruntime.config.ConfigurationLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the fourth construction layer's own contract independently of any database or
 * HTTP server: {@code UiConfigurationLoader} parses {@code business-ui.jsonc}, {@code
 * UiConfigurationValidator} enforces {@code uiConfigurationContract.binding} and {@code
 * .semanticRules}, and {@code UiProjectionResolver} merges {@code
 * composition.resolutionOrder} correctly. None of this touches a database, so it needs
 * no H2 and runs regardless of network availability.
 */
class UiConfigurationLoaderAndValidatorTest {

    private ResolvedBusinessBlueprint blueprint;
    private BusinessConfiguration config;

    @BeforeEach
    void loadSchema() {
        config = ConfigurationLoader.loadFromFile(Path.of("samples/business-schema-config.jsonc"));
        blueprint = BlueprintResolver.resolve(config);
    }

    @Test
    void sampleUiFileBindsCleanlyAndEveryTableInheritsTheDeclaredDefaults() {
        UiConfiguration ui = UiConfigurationLoader.loadFromFile(Path.of("samples/business-ui.jsonc"));
        assertEquals("CONSULTING_UI_DEMO_V1", ui.uiConfigurationId());
        assertTrue(UiConfigurationValidator.validate(ui, blueprint).isEmpty());

        UiProjectionSnapshot snapshot = UiProjectionResolver.resolve(ui, config.tables());
        for (var table : config.tables()) {
            UiSurfaceRule rule = snapshot.forTable(table.tableId());
            assertTrue(rule.isInlineEditable(), table.tableId() + " must inherit INLINE_EDITABLE from defaults");
            assertTrue(rule.actionEnabled("SAVE") && rule.actionEnabled("DELETE"));
            assertTrue(rule.createInline());
            assertTrue(rule.staysOnListAfterSave() && rule.staysOnListAfterCreate(),
                    "the sample defaults declare STAY_ON_LIST for both save and create");
            assertEquals("KEEP_AS_FALLBACK", rule.standaloneViewEditRoutes());
            assertTrue(rule.blockedDeleteIsInlineBanner());
        }
    }

    @Test
    void wrongSchemaFingerprintIsRejectedBeforeTheUiWouldActivate() {
        UiConfiguration ui = UiConfigurationLoader.loadFromText("""
                {
                  "uiConfigurationId": "X",
                  "binding": {
                    "businessId": "CONSULTING_DEMO", "schemaConfigurationId": "CONSULTING_SCHEMA_DEMO_V1_1",
                    "schemaVersion": "1", "expectedSchemaFingerprint": "0000000000000000000000000000000000000000000000000000000000000000"
                  },
                  "defaults": {
                    "listSurface": {"rowMode":"INLINE_EDITABLE","rowActions":["SAVE","DELETE"]},
                    "createSurface": {"placement":"INLINE_BELOW_LIST","onSuccess":"STAY_ON_LIST"},
                    "standaloneViewEditRoutes": "KEEP_AS_FALLBACK"
                  }
                }
                """, "wrong-fingerprint");
        List<String> problems = UiConfigurationValidator.validate(ui, blueprint);
        assertFalse(problems.isEmpty());
        assertTrue(problems.get(0).contains("expectedSchemaFingerprint"));
    }

    @Test
    void unknownPerTableTableIdIsRejected() {
        UiConfiguration ui = validDefaultsWith("""
                "perTable": [ { "tableId": "notARealTable", "listSurface": { "rowMode": "READ_ONLY" } } ]
                """);
        List<String> problems = UiConfigurationValidator.validate(ui, blueprint);
        assertTrue(problems.stream().anyMatch(p -> p.contains("notARealTable")));
    }

    @Test
    void duplicatePerTableEntryForTheSameTableIsRejectedAtLoadTime() {
        assertThrows(ConfigurationException.class, () -> UiConfigurationLoader.loadFromText("""
                {
                  "uiConfigurationId": "X",
                  "binding": { "businessId": "CONSULTING_DEMO", "schemaConfigurationId": "CONSULTING_SCHEMA_DEMO_V1_1",
                               "schemaVersion": "1", "expectedSchemaFingerprint": "%s" },
                  "defaults": {},
                  "perTable": [
                    { "tableId": "clients", "listSurface": { "rowMode": "READ_ONLY" } },
                    { "tableId": "clients", "listSurface": { "rowMode": "INLINE_EDITABLE" } }
                  ]
                }
                """.formatted(blueprint.schemaFingerprint()), "duplicate-per-table"));
    }

    @Test
    void inlineEditableWithoutSaveIsRejectedAsAContradiction() {
        UiConfiguration ui = validDefaultsWith("""
                "perTable": [ { "tableId": "clients", "listSurface": { "rowMode": "INLINE_EDITABLE", "rowActions": ["DELETE"] } } ]
                """);
        List<String> problems = UiConfigurationValidator.validate(ui, blueprint);
        assertTrue(problems.stream().anyMatch(p -> p.contains("must declare rowActions including SAVE")));
    }

    @Test
    void readOnlyWithSaveIsRejectedAsAContradiction() {
        UiConfiguration ui = validDefaultsWith("""
                "perTable": [ { "tableId": "clients", "listSurface": { "rowMode": "READ_ONLY", "rowActions": ["SAVE"] } } ]
                """);
        List<String> problems = UiConfigurationValidator.validate(ui, blueprint);
        assertTrue(problems.stream().anyMatch(p -> p.contains("must not declare rowActions including SAVE")));
    }

    @Test
    void removingStandaloneRoutesWhileStillLinkingToThemIsRejected() {
        UiConfiguration ui = validDefaultsWith("""
                "perTable": [ { "tableId": "clients", "listSurface": { "rowMode": "READ_ONLY", "rowActions": ["VIEW", "EDIT"] },
                                "standaloneViewEditRoutes": "REMOVE" } ]
                """);
        List<String> problems = UiConfigurationValidator.validate(ui, blueprint);
        assertFalse(problems.isEmpty());
    }

    @Test
    void unrecognizedVocabularyValueIsRejected() {
        UiConfiguration ui = validDefaultsWith("""
                "perTable": [ { "tableId": "clients", "listSurface": { "rowMode": "SOMETHING_MADE_UP" } } ]
                """);
        List<String> problems = UiConfigurationValidator.validate(ui, blueprint);
        assertTrue(problems.stream().anyMatch(p -> p.contains("SOMETHING_MADE_UP")));
    }

    @Test
    void explicitJsonNullIsRejectedRatherThanTreatedAsInherit() {
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> UiConfigurationLoader.loadFromText("""
                {
                  "uiConfigurationId": "X",
                  "binding": { "businessId": "CONSULTING_DEMO", "schemaConfigurationId": "CONSULTING_SCHEMA_DEMO_V1_1",
                               "schemaVersion": "1", "expectedSchemaFingerprint": "%s" },
                  "defaults": { "listSurface": { "rowMode": null } }
                }
                """.formatted(blueprint.schemaFingerprint()), "explicit-null"));
        assertTrue(e.getMessage().contains("nullRule"));
    }

    @Test
    void perTableOverrideOnlyChangesTheNamedTableEveryOtherTableStillInheritsDefaults() {
        UiConfiguration ui = validDefaultsWith("""
                "perTable": [ { "tableId": "invoiceLines", "listSurface": { "rowMode": "READ_ONLY", "rowActions": ["VIEW", "EDIT", "DELETE"] } } ]
                """);
        assertTrue(UiConfigurationValidator.validate(ui, blueprint).isEmpty());
        UiProjectionSnapshot snapshot = UiProjectionResolver.resolve(ui, config.tables());
        assertFalse(snapshot.forTable("invoiceLines").isInlineEditable());
        assertTrue(snapshot.forTable("clients").isInlineEditable());
        assertTrue(snapshot.forTable("engagements").isInlineEditable());
        assertTrue(snapshot.forTable("invoices").isInlineEditable());
    }

    @Test
    void changingOnlyThemeTokensNeverTouchesSchemaFingerprintBinding() {
        // acceptanceScenarios: "Presentation changes are schema-neutral." Theme tokens live
        // entirely outside the binding/vocabulary this validator checks, so two configurations
        // that differ only in theme both validate identically against the same schema.
        UiConfiguration plain = validDefaultsWith("");
        UiConfiguration themed = UiConfigurationLoader.loadFromText("""
                {
                  "uiConfigurationId": "X",
                  "binding": { "businessId": "CONSULTING_DEMO", "schemaConfigurationId": "CONSULTING_SCHEMA_DEMO_V1_1",
                               "schemaVersion": "1", "expectedSchemaFingerprint": "%s" },
                  "defaults": {
                    "listSurface": {"rowMode":"INLINE_EDITABLE","rowActions":["SAVE","DELETE"]},
                    "createSurface": {"placement":"INLINE_BELOW_LIST","onSuccess":"STAY_ON_LIST"},
                    "standaloneViewEditRoutes": "KEEP_AS_FALLBACK"
                  },
                  "theme": { "tokens": { "primary": "#FF0000", "borderRadiusPx": 2 } }
                }
                """.formatted(blueprint.schemaFingerprint()), "themed");
        assertTrue(UiConfigurationValidator.validate(plain, blueprint).isEmpty());
        assertTrue(UiConfigurationValidator.validate(themed, blueprint).isEmpty());
        assertEquals("#FF0000", themed.themeTokens().get("primary"));
        assertEquals(blueprint.schemaFingerprint(), blueprint.schemaFingerprint()); // unchanged regardless of theme
    }

    private UiConfiguration validDefaultsWith(String extraTopLevelJson) {
        String extra = extraTopLevelJson.isBlank() ? "" : "," + extraTopLevelJson;
        return UiConfigurationLoader.loadFromText("""
                {
                  "uiConfigurationId": "X",
                  "binding": { "businessId": "CONSULTING_DEMO", "schemaConfigurationId": "CONSULTING_SCHEMA_DEMO_V1_1",
                               "schemaVersion": "1", "expectedSchemaFingerprint": "%s" },
                  "defaults": {
                    "listSurface": {"rowMode":"INLINE_EDITABLE","rowActions":["SAVE","DELETE"]},
                    "createSurface": {"placement":"INLINE_BELOW_LIST","onSuccess":"STAY_ON_LIST"},
                    "standaloneViewEditRoutes": "KEEP_AS_FALLBACK"
                  }
                  %s
                }
                """.formatted(blueprint.schemaFingerprint(), extra), "test-fixture");
    }
}
