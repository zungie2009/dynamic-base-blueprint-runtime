package org.blueprintruntime;

import com.sun.net.httpserver.HttpServer;
import org.blueprintruntime.blueprint.BlueprintResolver;
import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.ConfigurationException;
import org.blueprintruntime.config.ConfigurationLoader;
import org.blueprintruntime.db.Database;
import org.blueprintruntime.http.RouteDispatcher;
import org.blueprintruntime.rules.AcceptanceExampleRunner;
import org.blueprintruntime.rules.ActiveRuleSetSnapshot;
import org.blueprintruntime.rules.BusinessRuleSet;
import org.blueprintruntime.rules.RuleSetLoader;
import org.blueprintruntime.rules.RuleSetValidator;
import org.blueprintruntime.runtime.BusinessContext;
import org.blueprintruntime.schema.SchemaMigrationRequiredException;
import org.blueprintruntime.schema.SchemaProvisioner;
import org.blueprintruntime.uiconfig.UiConfiguration;
import org.blueprintruntime.uiconfig.UiConfigurationLoader;
import org.blueprintruntime.uiconfig.UiConfigurationValidator;
import org.blueprintruntime.uiconfig.UiProjectionResolver;
import org.blueprintruntime.uiconfig.UiProjectionSnapshot;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * CLI entry point and startup orchestration, per {@code runtimeInvocation} and {@code
 * inputContract.failurePolicy}: parse {@code --config}/{@code --rules}/{@code --ui}/
 * {@code --bind}/{@code --port}, load and validate the external schema configuration,
 * provision (or verify) the business schema, load and activate the initial rule set,
 * load and validate the fourth-layer UI configuration and resolve its projection, then
 * serve. Any failure along the way prints a useful report and exits without starting a
 * partial UI.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (StartupFailure failure) {
            System.err.println(failure.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws StartupFailure {
        Arguments arguments = Arguments.parse(args);

        BusinessConfiguration config;
        try {
            config = ConfigurationLoader.loadFromFile(Path.of(arguments.configPath));
        } catch (ConfigurationException e) {
            throw new StartupFailure(validationReport("Configuration is invalid; the business UI was not started.", e));
        }

        ResolvedBusinessBlueprint blueprint = BlueprintResolver.resolve(config);
        System.out.println("Loaded configuration '" + blueprint.applicationId() + "' for business '"
                + blueprint.businessId() + "' (schema " + blueprint.schemaVersion() + ", fingerprint "
                + blueprint.schemaFingerprint() + ")");

        Path dataPath = Path.of(arguments.databaseFile).toAbsolutePath();
        if (dataPath.getParent() != null) {
            try {
                Files.createDirectories(dataPath.getParent());
            } catch (IOException e) {
                throw new StartupFailure("Could not create data directory '" + dataPath.getParent() + "': " + e.getMessage());
            }
        }
        Database database = Database.embeddedFile(dataPath.toString());

        try (Connection connection = database.getConnection()) {
            boolean created = new SchemaProvisioner().provision(connection, blueprint);
            System.out.println(created
                    ? "Provisioned new schema '" + blueprint.databaseSchemaName() + "'."
                    : "Reusing installed schema '" + blueprint.databaseSchemaName() + "' (fingerprint unchanged).");
        } catch (SchemaMigrationRequiredException e) {
            throw new StartupFailure(validationReport("The installed schema no longer matches the active configuration; "
                    + "no automatic migration was performed and the business UI was not started.", e));
        } catch (SQLException e) {
            throw new StartupFailure("Could not provision the database schema: " + e.getMessage());
        }

        UiConfiguration uiConfig;
        try {
            uiConfig = UiConfigurationLoader.loadFromFile(Path.of(arguments.uiPath));
        } catch (ConfigurationException e) {
            throw new StartupFailure(validationReport("UI configuration is invalid; the business UI was not started.", e));
        }
        List<String> uiProblems = UiConfigurationValidator.validate(uiConfig, blueprint);
        if (!uiProblems.isEmpty()) {
            throw new StartupFailure(validationReport(
                    "UI configuration does not bind to the active schema, or contradicts itself; the business UI was not started.",
                    uiProblems));
        }
        UiProjectionSnapshot uiProjection = UiProjectionResolver.resolve(uiConfig, config.tables());
        System.out.println("Activated UI configuration '" + uiConfig.uiConfigurationId() + "'.");

        BusinessContext context = new BusinessContext(blueprint, database, Path.of(arguments.configPath), Path.of(arguments.rulesPath),
                uiProjection, Path.of(arguments.uiPath));

        try (Connection connection = database.getConnection()) {
            context.auditService().ensureTable(connection);
        } catch (SQLException e) {
            throw new StartupFailure("Could not initialize the audit table: " + e.getMessage());
        }

        BusinessRuleSet initialRuleSet;
        try {
            initialRuleSet = RuleSetLoader.loadFromFile(Path.of(arguments.rulesPath));
        } catch (ConfigurationException e) {
            throw new StartupFailure(validationReport("Rule file is invalid; the business UI was not started.", e));
        }
        List<String> ruleProblems = RuleSetValidator.validate(initialRuleSet, blueprint);
        if (!ruleProblems.isEmpty()) {
            throw new StartupFailure(validationReport("Rule file does not bind to the active schema; the business UI was not started.", ruleProblems));
        }
        AcceptanceExampleRunner.Result acceptance = new AcceptanceExampleRunner().runAll(blueprint, initialRuleSet);
        if (!acceptance.passed()) {
            StringBuilder report = new StringBuilder("Rule set failed its declared acceptance examples; the business UI was not started.\n");
            for (var failure : acceptance.failures()) {
                report.append(" - ").append(failure.exampleName()).append(": ").append(failure.message()).append('\n');
            }
            throw new StartupFailure(report.toString());
        }
        context.setActiveRuleSet(ActiveRuleSetSnapshot.activate(initialRuleSet, arguments.rulesPath));
        context.setLastRuleReloadReport("Initial rule set '" + initialRuleSet.ruleSetId() + " " + initialRuleSet.version()
                + "' activated at startup after " + initialRuleSet.acceptanceExamples().size() + " acceptance example(s) passed.");
        System.out.println("Activated rule set '" + initialRuleSet.ruleSetId() + " " + initialRuleSet.version()
                + "' (" + initialRuleSet.acceptanceExamples().size() + " acceptance example(s) passed).");

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(arguments.bind, arguments.port), 0);
        } catch (BindException e) {
            throw new StartupFailure("Port " + arguments.port + " on " + arguments.bind + " is already in use. "
                    + "Choose a different --port; this runtime never silently selects another one.");
        } catch (IOException e) {
            throw new StartupFailure("Could not start the HTTP server: " + e.getMessage());
        }
        server.createContext("/", new RouteDispatcher(context));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Listening on http://" + arguments.bind + ":" + arguments.port + "/");
    }

    private static String validationReport(String headline, ConfigurationException e) {
        StringBuilder sb = new StringBuilder(headline).append('\n').append(e.getMessage()).append('\n');
        for (String problem : e.problems()) sb.append(" - ").append(problem).append('\n');
        return sb.toString();
    }

    private static String validationReport(String headline, List<String> problems) {
        StringBuilder sb = new StringBuilder(headline).append('\n');
        for (String problem : problems) sb.append(" - ").append(problem).append('\n');
        return sb.toString();
    }

    private static String validationReport(String headline, SchemaMigrationRequiredException e) {
        return headline + "\n" + e.getMessage() + "\n";
    }

    private static final class StartupFailure extends Exception {
        StartupFailure(String message) {
            super(message);
        }
    }

    private static final class Arguments {
        String configPath = "./business-schema-config.jsonc";
        String rulesPath = "./business-rules.jsonc";
        String uiPath = "./business-ui.jsonc";
        String bind = "127.0.0.1";
        int port = 8091;
        String databaseFile = "data/business-runtime";

        static Arguments parse(String[] args) throws StartupFailure {
            Arguments a = new Arguments();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                String value = (i + 1 < args.length) ? args[i + 1] : null;
                switch (arg) {
                    case "--config" -> a.configPath = require(arg, value, i++);
                    case "--rules" -> a.rulesPath = require(arg, value, i++);
                    case "--ui" -> a.uiPath = require(arg, value, i++);
                    case "--bind" -> a.bind = require(arg, value, i++);
                    case "--port" -> {
                        String v = require(arg, value, i++);
                        try {
                            a.port = Integer.parseInt(v);
                        } catch (NumberFormatException e) {
                            throw new StartupFailure("--port requires a numeric value, got '" + v + "'");
                        }
                    }
                    case "--database" -> a.databaseFile = require(arg, value, i++);
                    default -> throw new StartupFailure("Unknown argument '" + arg + "'");
                }
            }
            return a;
        }

        private static String require(String flag, String value, int index) throws StartupFailure {
            if (value == null) throw new StartupFailure(flag + " requires a value");
            return value;
        }
    }
}
