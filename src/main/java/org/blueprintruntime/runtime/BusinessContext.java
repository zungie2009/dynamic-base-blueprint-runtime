package org.blueprintruntime.runtime;

import org.blueprintruntime.audit.AuditService;
import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.db.Database;
import org.blueprintruntime.rules.ActiveRuleSetSnapshot;
import org.blueprintruntime.rules.RuleEngine;
import org.blueprintruntime.uiconfig.UiProjectionSnapshot;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The composition root for one running business: its resolved blueprint, its database,
 * and every generic runtime object ({@code genericRuntimeObjects}/{@code
 * genericServices}) wired together once at startup. Holds the currently active {@link
 * ActiveRuleSetSnapshot} behind an atomic reference so {@code POST
 * /administration/rules/reload} can swap it without restarting the process.
 */
public final class BusinessContext {

    private final ResolvedBusinessBlueprint blueprint;
    private final Database database;
    private final RecordStore recordStore;
    private final RecordValidator recordValidator;
    private final RelationshipResolver relationshipResolver;
    private final AuditService auditService;
    private final RuleEngine ruleEngine;
    private final GenericCrudService crudService;
    private final Path configFilePath;
    private final Path ruleFilePath;
    private final Path uiFilePath;
    private final UiProjectionSnapshot uiProjection;
    private final AtomicReference<ActiveRuleSetSnapshot> activeRuleSet = new AtomicReference<>();
    private volatile String lastRuleReloadReport = "No rule set has been loaded yet.";

    public BusinessContext(ResolvedBusinessBlueprint blueprint, Database database, Path configFilePath, Path ruleFilePath) {
        this(blueprint, database, configFilePath, ruleFilePath, null, null);
    }

    /**
     * {@code uiProjection} is the resolved fourth-layer UI snapshot ({@code
     * uiConfigurationContract}); it is {@code null} only for contexts that never serve
     * HTTP (e.g. the isolated acceptance-example harness), since {@code
     * uiReloadPolicy: RESTART_REQUIRED_FOR_V1_5} means it never changes after construction.
     */
    public BusinessContext(ResolvedBusinessBlueprint blueprint, Database database, Path configFilePath, Path ruleFilePath,
                            UiProjectionSnapshot uiProjection, Path uiFilePath) {
        this.blueprint = blueprint;
        this.database = database;
        this.configFilePath = configFilePath;
        this.ruleFilePath = ruleFilePath;
        this.uiProjection = uiProjection;
        this.uiFilePath = uiFilePath;
        this.recordStore = new RecordStore(blueprint);
        this.recordValidator = new RecordValidator();
        this.relationshipResolver = new RelationshipResolver(recordStore);
        this.auditService = new AuditService();
        this.ruleEngine = new RuleEngine(recordStore, auditService);
        this.crudService = new GenericCrudService(blueprint, recordStore, recordValidator, ruleEngine, auditService, this);
    }

    public ResolvedBusinessBlueprint blueprint() {
        return blueprint;
    }

    public Database database() {
        return database;
    }

    public RecordStore recordStore() {
        return recordStore;
    }

    public RecordValidator recordValidator() {
        return recordValidator;
    }

    public RelationshipResolver relationshipResolver() {
        return relationshipResolver;
    }

    public AuditService auditService() {
        return auditService;
    }

    public RuleEngine ruleEngine() {
        return ruleEngine;
    }

    public GenericCrudService crudService() {
        return crudService;
    }

    public Path configFilePath() {
        return configFilePath;
    }

    public Path ruleFilePath() {
        return ruleFilePath;
    }

    public Path uiFilePath() {
        return uiFilePath;
    }

    public UiProjectionSnapshot uiProjection() {
        return uiProjection;
    }

    public ActiveRuleSetSnapshot activeRuleSet() {
        return activeRuleSet.get();
    }

    public void setActiveRuleSet(ActiveRuleSetSnapshot snapshot) {
        activeRuleSet.set(snapshot);
    }

    public String lastRuleReloadReport() {
        return lastRuleReloadReport;
    }

    public void setLastRuleReloadReport(String report) {
        this.lastRuleReloadReport = report;
    }
}
