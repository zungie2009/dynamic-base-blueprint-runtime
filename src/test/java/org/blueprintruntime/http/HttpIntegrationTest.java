package org.blueprintruntime.http;

import com.sun.net.httpserver.HttpServer;
import org.blueprintruntime.blueprint.BlueprintResolver;
import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.ConfigurationLoader;
import org.blueprintruntime.db.Database;
import org.blueprintruntime.rules.AcceptanceExampleRunner;
import org.blueprintruntime.rules.ActiveRuleSetSnapshot;
import org.blueprintruntime.rules.BusinessRuleSet;
import org.blueprintruntime.rules.RuleSetLoader;
import org.blueprintruntime.rules.RuleSetValidator;
import org.blueprintruntime.runtime.BusinessContext;
import org.blueprintruntime.runtime.GenericRecord;
import org.blueprintruntime.schema.SchemaProvisioner;
import org.blueprintruntime.uiconfig.UiConfiguration;
import org.blueprintruntime.uiconfig.UiConfigurationLoader;
import org.blueprintruntime.uiconfig.UiConfigurationValidator;
import org.blueprintruntime.uiconfig.UiProjectionResolver;
import org.blueprintruntime.uiconfig.UiProjectionSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end HTTP tests over the JDK {@code com.sun.net.httpserver} runtime, per
 * {@code experimentAcceptance.phaseOne} and the {@code deliveryGate}: schema
 * provisioning, all five generic CRUD operations, reference pickers, RESTRICT
 * foreign-key enforcement, validation, introspection, rule reload (both a rejected
 * candidate and one that changes subsequent calculations), navigation-per-table, and
 * restart persistence against the same on-disk database file.
 */
class HttpIntegrationTest {

    private Path dataDir;
    private Path rulesFile;
    private BusinessContext context;
    private HttpServer server;
    private HttpClient client;
    private int port;

    @BeforeEach
    void startServer(@TempDir Path tempDir) throws IOException, SQLException {
        dataDir = tempDir;
        rulesFile = tempDir.resolve("business-rules.jsonc");
        Files.copy(Path.of("samples/business-rules.jsonc"), rulesFile);

        context = buildAndStartContext();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private BusinessContext buildAndStartContext() throws IOException, SQLException {
        BusinessConfiguration config = ConfigurationLoader.loadFromFile(Path.of("samples/business-schema-config.jsonc"));
        ResolvedBusinessBlueprint blueprint = BlueprintResolver.resolve(config);
        Database database = Database.embeddedFile(dataDir.resolve("business-runtime").toString());

        try (Connection connection = database.getConnection()) {
            new SchemaProvisioner().provision(connection, blueprint);
        }
        Path uiFile = Path.of("samples/business-ui.jsonc");
        UiConfiguration uiConfig = UiConfigurationLoader.loadFromFile(uiFile);
        assertTrue(UiConfigurationValidator.validate(uiConfig, blueprint).isEmpty());
        UiProjectionSnapshot uiProjection = UiProjectionResolver.resolve(uiConfig, config.tables());

        BusinessContext ctx = new BusinessContext(blueprint, database, Path.of("samples/business-schema-config.jsonc"), rulesFile,
                uiProjection, uiFile);
        try (Connection connection = database.getConnection()) {
            ctx.auditService().ensureTable(connection);
        }
        BusinessRuleSet ruleSet = RuleSetLoader.loadFromFile(rulesFile);
        assertTrue(RuleSetValidator.validate(ruleSet, blueprint).isEmpty());
        AcceptanceExampleRunner.Result result = new AcceptanceExampleRunner().runAll(blueprint, ruleSet);
        assertTrue(result.passed(), () -> "sample rule set must pass its own acceptance examples: " + result.failures());
        ctx.setActiveRuleSet(ActiveRuleSetSnapshot.activate(ruleSet, rulesFile.toString()));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new RouteDispatcher(ctx));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();
        return ctx;
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(url(path))).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, Map<String, String> form) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        for (var entry : form.entrySet()) {
            if (!body.isEmpty()) body.append('&');
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The sample UI's {@code onSaveSuccess}/{@code createSurface.onSuccess} is
     * STAY_ON_LIST, so a successful create/update redirects to {@code
     * /records/{tableId}?flash=...&id={recordId}#row-{recordId}} rather than the
     * standalone view page — this reads the id out of either shape.
     */
    private String idFromRedirect(HttpResponse<String> response) {
        String location = response.headers().firstValue("Location").orElseThrow();
        int idParam = location.indexOf("id=");
        if (idParam < 0) {
            return location.substring(location.lastIndexOf('/') + 1);
        }
        String rest = location.substring(idParam + 3);
        int end = rest.length();
        int amp = rest.indexOf('&');
        if (amp >= 0) end = Math.min(end, amp);
        int hash = rest.indexOf('#');
        if (hash >= 0) end = Math.min(end, hash);
        return rest.substring(0, end);
    }

    @Test
    void healthAndHomeRespond() throws Exception {
        assertEquals(200, get("/health").statusCode());
        HttpResponse<String> home = get("/");
        assertEquals(200, home.statusCode());
        assertTrue(home.body().contains("Consulting Company"));
    }

    @Test
    void oneNavigationEntryPerConfiguredTable() throws Exception {
        HttpResponse<String> list = get("/records/clients");
        assertEquals(200, list.statusCode());
        int navLinks = list.body().split("class='nav-link", -1).length - 1;
        // menuLabels: Clients, Engagements, Invoices, Invoice Lines
        assertTrue(list.body().contains(">Clients<") && list.body().contains(">Engagements<")
                && list.body().contains(">Invoices<") && list.body().contains(">Invoice Lines<"));
        assertEquals(4, navLinks, "expected exactly one nav link per configured table");
    }

    @Test
    void unknownTableReturnsNotFound() throws Exception {
        assertEquals(404, get("/records/doesNotExist").statusCode());
    }

    @Test
    void emptyTableListPageShowsInlineCreateFormAndCanCreateFromIt() throws Exception {
        // Before any record exists, the list page itself must already show the create form's
        // controls (a textbox per STRING/EMAIL field here) and a Save button — not merely a link
        // to a separate page — so a table can be populated without ever navigating away from it.
        HttpResponse<String> empty = get("/records/clients");
        assertEquals(200, empty.statusCode());
        assertTrue(empty.body().contains("No Clients yet"));
        assertTrue(empty.body().contains("action='/records/clients'"), "the inline form must post to the generic create route");
        assertTrue(empty.body().contains("name='name'") && empty.body().contains("name='email'"),
                "every editable field must have its own input control on the list page");
        assertTrue(empty.body().contains("type='submit'"));

        // Submitting that inline form (a plain POST to /records/clients, exactly like the standalone
        // create form) must behave identically: it creates the record and redirects to its view page.
        HttpResponse<String> created = postForm("/records/clients", Map.of("name", "Inline Co", "email", ""));
        assertEquals(303, created.statusCode());
        String clientId = idFromRedirect(created);
        assertTrue(get("/records/clients/" + clientId).body().contains("Inline Co"));

        // Once a record exists, the list page shows the data table AND still keeps the same inline form below it.
        HttpResponse<String> nonEmpty = get("/records/clients");
        assertTrue(nonEmpty.body().contains("Inline Co"));
        assertTrue(nonEmpty.body().contains("name='name'"), "the inline create form must remain available even when the list has records");
    }

    @Test
    void invalidInlineSubmissionRerendersListPageWithErrorAndKeepsSubmittedInput() throws Exception {
        HttpResponse<String> response = postForm("/records/clients", Map.of("email", "someone@example.com")); // missing required "name"
        assertEquals(422, response.statusCode());
        assertTrue(response.body().toLowerCase().contains("required"));
        // Still the list page (not a separate form-only page): the "No Clients yet" empty state and
        // the inline form's action are both present, and the value the user already typed survives.
        assertTrue(response.body().contains("action='/records/clients'"));
        assertTrue(response.body().contains("value='someone@example.com'"));
    }

    @Test
    void fullCrudLifecycleAndForeignKeyRestriction() throws Exception {
        // CREATE a client.
        HttpResponse<String> createClient = postForm("/records/clients", Map.of("name", "Acme Corp", "email", "ap@acme.example"));
        assertEquals(303, createClient.statusCode());
        String clientId = idFromRedirect(createClient);

        // VIEW it.
        HttpResponse<String> view = get("/records/clients/" + clientId);
        assertEquals(200, view.statusCode());
        assertTrue(view.body().contains("Acme Corp"));

        // The reference picker on the engagements "new" form must show the client's displayField.
        HttpResponse<String> engagementNewForm = get("/records/engagements/new");
        assertTrue(engagementNewForm.body().contains("Acme Corp"));

        // CREATE an engagement referencing that client.
        Map<String, String> engagementForm = new LinkedHashMap<>();
        engagementForm.put("clientId", clientId);
        engagementForm.put("title", "Website redesign");
        engagementForm.put("startDate", "2026-09-01");
        HttpResponse<String> createEngagement = postForm("/records/engagements", engagementForm);
        assertEquals(303, createEngagement.statusCode());
        String engagementId = idFromRedirect(createEngagement);

        // LIST shows it.
        HttpResponse<String> engagementList = get("/records/engagements");
        assertTrue(engagementList.body().contains("Website redesign"));

        // UPDATE the engagement's title.
        Map<String, String> updateForm = new LinkedHashMap<>();
        updateForm.put("clientId", clientId);
        updateForm.put("title", "Website redesign phase 2");
        updateForm.put("startDate", "2026-09-01");
        HttpResponse<String> update = postForm("/records/engagements/" + engagementId, updateForm);
        assertEquals(303, update.statusCode());
        assertTrue(get("/records/engagements/" + engagementId).body().contains("Website redesign phase 2"));

        // Deleting the referenced client must be rejected under RESTRICT.
        HttpResponse<String> deleteReferencedClient = postForm("/records/clients/" + clientId + "/delete", Map.of());
        assertEquals(409, deleteReferencedClient.statusCode());
        assertEquals(200, get("/records/clients/" + clientId).statusCode()); // client still exists

        // DELETE the engagement (nothing references it), then the client, both should succeed.
        assertEquals(303, postForm("/records/engagements/" + engagementId + "/delete", Map.of()).statusCode());
        assertEquals(404, get("/records/engagements/" + engagementId).statusCode());
        assertEquals(303, postForm("/records/clients/" + clientId + "/delete", Map.of()).statusCode());
        assertEquals(404, get("/records/clients/" + clientId).statusCode());
    }

    @Test
    void createAndSaveSuccessShowBannerAndHighlightTheRowOnTheList() throws Exception {
        // The sample UI's feedback.success is INLINE_BANNER and onSuccess/onSaveSuccess are both
        // STAY_ON_LIST, so both a create and a save land back on the list with a dismissible banner
        // and the affected row anchored/highlighted — never a separate confirmation page.
        HttpResponse<String> created = postForm("/records/clients", Map.of("name", "Banner Co", "email", ""));
        assertEquals(303, created.statusCode());
        String location = created.headers().firstValue("Location").orElseThrow();
        assertTrue(location.startsWith("/records/clients?flash=created&id="), "create must stay on the list per createSurface.onSuccess");
        assertTrue(location.contains("#row-"), "the redirect must anchor to the saved row per listSurface.savedRowAnchor");
        String clientId = idFromRedirect(created);

        HttpResponse<String> afterCreate = get("/records/clients?flash=created&id=" + clientId);
        assertTrue(afterCreate.body().contains("success-banner"), "a dismissible success banner must appear after create");
        assertTrue(afterCreate.body().contains("Banner Co") && afterCreate.body().contains("added"));
        assertTrue(afterCreate.body().contains("row-saved"), "the just-created row must be visually highlighted");

        HttpResponse<String> saved = postForm("/records/clients/" + clientId, Map.of("name", "Banner Co Updated", "email", ""));
        assertEquals(303, saved.statusCode());
        String saveLocation = saved.headers().firstValue("Location").orElseThrow();
        assertTrue(saveLocation.startsWith("/records/clients?flash=saved&id=" + clientId), "save must stay on the list per listSurface.onSaveSuccess");

        HttpResponse<String> afterSave = get(saveLocation);
        assertTrue(afterSave.body().contains("Banner Co Updated") && afterSave.body().contains("saved."));
    }

    @Test
    void blockedDeleteRendersInlineBannerOnTheSameListWithOtherRecordsStillVisible() throws Exception {
        String clientId = idFromRedirect(postForm("/records/clients", Map.of("name", "Referenced Co", "email", "")));
        String otherClientId = idFromRedirect(postForm("/records/clients", Map.of("name", "Untouched Co", "email", "")));
        Map<String, String> engagementForm = new LinkedHashMap<>();
        engagementForm.put("clientId", clientId);
        engagementForm.put("title", "Keeps client referenced");
        engagementForm.put("startDate", "2026-09-09");
        postForm("/records/engagements", engagementForm);

        HttpResponse<String> blockedDelete = postForm("/records/clients/" + clientId + "/delete", Map.of());
        assertEquals(409, blockedDelete.statusCode());
        // Per feedback.blockedDelete: INLINE_BANNER, this is the list page itself (not a standalone
        // error page), so both the danger banner and every other record are still right there.
        assertTrue(blockedDelete.body().contains("error-banner"));
        assertTrue(blockedDelete.body().contains("Referenced Co"), "the record whose delete was blocked must still be visible");
        assertTrue(blockedDelete.body().contains("Untouched Co"), "unrelated records must remain visible on the same page");
    }

    @Test
    void requiredFieldValidationRerendersFormWithFieldError() throws Exception {
        HttpResponse<String> response = postForm("/records/clients", Map.of("email", "someone@example.com")); // missing required "name"
        assertEquals(422, response.statusCode());
        assertTrue(response.body().toLowerCase().contains("required"));
    }

    @Test
    void invalidEmailIsRejected() throws Exception {
        HttpResponse<String> response = postForm("/records/clients", Map.of("name", "Bad Email Co", "email", "not-an-email"));
        assertEquals(422, response.statusCode());
        assertTrue(response.body().toLowerCase().contains("email"));
    }

    @Test
    void invoiceCalculationsCascadeThroughFullMutationPipeline() throws Exception {
        String clientId = idFromRedirect(postForm("/records/clients", Map.of("name", "Cascade Test Co", "email", "")));
        Map<String, String> engagementForm = new LinkedHashMap<>();
        engagementForm.put("clientId", clientId);
        engagementForm.put("title", "Cascade Engagement");
        engagementForm.put("startDate", "2026-09-09");
        String engagementId = idFromRedirect(postForm("/records/engagements", engagementForm));

        Map<String, String> invoiceForm = new LinkedHashMap<>();
        invoiceForm.put("engagementId", engagementId);
        invoiceForm.put("invoiceNumber", "INV-HTTP-1");
        invoiceForm.put("invoiceDate", "2026-09-09");
        String invoiceId = idFromRedirect(postForm("/records/invoices", invoiceForm));

        Map<String, String> lineForm = new LinkedHashMap<>();
        lineForm.put("invoiceId", invoiceId);
        lineForm.put("description", "Consulting hours");
        lineForm.put("quantity", "4");
        lineForm.put("unitPrice", "150.00");
        assertEquals(303, postForm("/records/invoiceLines", lineForm).statusCode());

        Optional<GenericRecord> invoice = context.crudService().view("invoices", invoiceId);
        assertTrue(invoice.isPresent());
        assertEquals(new java.math.BigDecimal("600.00"), invoice.get().get("subtotal"));
        assertEquals(new java.math.BigDecimal("36.00"), invoice.get().get("tax")); // 6% of 600.00
        assertEquals(new java.math.BigDecimal("636.00"), invoice.get().get("total"));
    }

    @Test
    void introspectionEndpointMatchesActiveConfiguration() throws Exception {
        HttpResponse<String> blueprintJson = get("/api/blueprint");
        assertEquals(200, blueprintJson.statusCode());
        assertTrue(blueprintJson.body().contains("\"businessId\":\"CONSULTING_DEMO\""));
        assertTrue(blueprintJson.body().contains(context.blueprint().schemaFingerprint()));
        assertTrue(blueprintJson.body().contains("\"invoiceLines\""));

        HttpResponse<String> rulesJson = get("/api/rules");
        assertEquals(200, rulesJson.statusCode());
        assertTrue(rulesJson.body().contains("\"active\":true"));
        assertTrue(rulesJson.body().contains("CONSULTING_INVOICE_CALCULATIONS"));
    }

    @Test
    void invalidCandidateRuleReloadIsRejectedAndPriorRuleSetStaysActive() throws Exception {
        Files.writeString(rulesFile, "{ this is not valid json");
        HttpResponse<String> reload = postForm("/administration/rules/reload", Map.of());
        assertEquals(422, reload.statusCode());
        assertTrue(reload.body().contains("\"activated\":false"));

        HttpResponse<String> rulesJson = get("/api/rules");
        assertTrue(rulesJson.body().contains("CONSULTING_INVOICE_CALCULATIONS"), "the previously active rule set must remain active");
    }

    @Test
    void reloadingAChangedRuleFileChangesSubsequentCalculationsWithoutRestart() throws Exception {
        // A same-shape candidate with the tax rate changed from 6% to 10%. Its own acceptanceExamples
        // are declared empty here (rather than reusing the sample file's, which assert 6%-based numbers
        // and would now legitimately fail) so this test isolates exactly what it means to verify: that
        // POST /administration/rules/reload changes subsequent calculations without a JAR restart.
        String changed = """
                {
                  "ruleSet": {
                    "ruleSetId": "CONSULTING_INVOICE_CALCULATIONS", "version": "2", "businessId": "CONSULTING_DEMO",
                    "schemaBinding": {
                      "configurationId": "CONSULTING_SCHEMA_DEMO_V1_1",
                      "schemaVersion": "1",
                      "expectedSchemaFingerprint": "%s"
                    },
                    "maxPasses": 8
                  },
                  "rules": [
                    {
                      "ruleId": "INVOICE_LINE_AMOUNT", "targetTable": "invoiceLines",
                      "triggers": ["BEFORE_CREATE", "BEFORE_UPDATE"],
                      "reads": [{ "table": "invoiceLines", "field": "quantity" }, { "table": "invoiceLines", "field": "unitPrice" }],
                      "writes": [{ "table": "invoiceLines", "field": "lineAmount" }],
                      "expression": { "operator": "MULTIPLY", "arguments": ["invoiceLines.quantity", "invoiceLines.unitPrice"], "scale": 2, "roundingMode": "HALF_UP" }
                    },
                    {
                      "ruleId": "INVOICE_SUBTOTAL", "targetTable": "invoices",
                      "triggerBinding": {
                        "eventTable": "invoiceLines", "events": ["AFTER_CREATE", "AFTER_UPDATE", "AFTER_DELETE"],
                        "affectedRecords": { "strategy": "RELATED_PARENT", "referenceField": "invoiceId", "targetTable": "invoices", "targetKeyField": "id" }
                      },
                      "reads": [{ "table": "invoiceLines", "field": "invoiceId" }, { "table": "invoiceLines", "field": "lineAmount" }],
                      "writes": [{ "table": "invoices", "field": "subtotal" }],
                      "expression": { "operator": "SUM_RELATED", "source": "invoiceLines.lineAmount", "where": "invoiceLines.invoiceId = invoices.id", "emptyResult": "0.00", "scale": 2, "roundingMode": "HALF_UP" }
                    },
                    {
                      "ruleId": "INVOICE_TAX", "targetTable": "invoices",
                      "triggers": ["AFTER_CREATE", "AFTER_UPDATE"], "dependsOn": ["INVOICE_SUBTOTAL"],
                      "reads": [{ "table": "invoices", "field": "subtotal" }],
                      "writes": [{ "table": "invoices", "field": "tax" }],
                      "expression": { "operator": "MULTIPLY", "arguments": ["invoices.subtotal", { "constant": "0.10" }], "scale": 2, "roundingMode": "HALF_UP" }
                    },
                    {
                      "ruleId": "INVOICE_TOTAL", "targetTable": "invoices",
                      "triggers": ["AFTER_CREATE", "AFTER_UPDATE"], "dependsOn": ["INVOICE_SUBTOTAL", "INVOICE_TAX"],
                      "reads": [{ "table": "invoices", "field": "subtotal" }, { "table": "invoices", "field": "tax" }],
                      "writes": [{ "table": "invoices", "field": "total" }],
                      "expression": { "operator": "ADD", "arguments": ["invoices.subtotal", "invoices.tax"], "scale": 2, "roundingMode": "HALF_UP" }
                    }
                  ],
                  "acceptanceExamples": []
                }
                """.formatted(context.blueprint().schemaFingerprint());
        Files.writeString(rulesFile, changed);

        HttpResponse<String> reload = postForm("/administration/rules/reload", Map.of());
        assertEquals(200, reload.statusCode());
        assertTrue(reload.body().contains("\"activated\":true"));

        String clientId = idFromRedirect(postForm("/records/clients", Map.of("name", "Reload Test Co", "email", "")));
        Map<String, String> engagementForm = new LinkedHashMap<>();
        engagementForm.put("clientId", clientId);
        engagementForm.put("title", "Reload Engagement");
        engagementForm.put("startDate", "2026-09-09");
        String engagementId = idFromRedirect(postForm("/records/engagements", engagementForm));
        Map<String, String> invoiceForm = new LinkedHashMap<>();
        invoiceForm.put("engagementId", engagementId);
        invoiceForm.put("invoiceNumber", "INV-RELOAD-1");
        invoiceForm.put("invoiceDate", "2026-09-09");
        String invoiceId = idFromRedirect(postForm("/records/invoices", invoiceForm));
        Map<String, String> lineForm = new LinkedHashMap<>();
        lineForm.put("invoiceId", invoiceId);
        lineForm.put("description", "Consulting hours");
        lineForm.put("quantity", "1");
        lineForm.put("unitPrice", "100.00");
        postForm("/records/invoiceLines", lineForm);

        Optional<GenericRecord> invoice = context.crudService().view("invoices", invoiceId);
        assertEquals(new java.math.BigDecimal("10.00"), invoice.get().get("tax"), "reload must change subsequent calculations without a JAR restart");
    }

    @Test
    void restartingWithUnchangedConfigurationReusesSchemaAndPreservesRecords() throws Exception {
        String clientId = idFromRedirect(postForm("/records/clients", Map.of("name", "Persistent Co", "email", "")));
        stopServer();

        // Simulate a process restart: fresh BusinessContext/Database objects, same on-disk data directory.
        BusinessContext restarted = buildAndStartContext();
        Optional<GenericRecord> survived = restarted.crudService().view("clients", clientId);
        assertTrue(survived.isPresent(), "a record created before restart must still exist after reopening the same data directory");
        assertEquals("Persistent Co", survived.get().get("name"));
    }

    @Test
    void perTableOverrideSwitchesOnlyThatTableToReadOnlyRowsWithViewAndEditLinks(@TempDir Path altDir) throws Exception {
        // A perTable override changes exactly the table it names; every other table keeps
        // inheriting the file's defaults, per composition.resolutionOrder.
        BusinessConfiguration config = ConfigurationLoader.loadFromFile(Path.of("samples/business-schema-config.jsonc"));
        ResolvedBusinessBlueprint blueprint = BlueprintResolver.resolve(config);
        Database altDatabase = Database.embeddedFile(altDir.resolve("alt-business-runtime").toString());
        try (Connection connection = altDatabase.getConnection()) {
            new SchemaProvisioner().provision(connection, blueprint);
        }

        Path altUiFile = altDir.resolve("business-ui.jsonc");
        Files.writeString(altUiFile, """
                {
                  "uiConfigurationId": "CONSULTING_UI_ALT_TEST",
                  "binding": {
                    "businessId": "CONSULTING_DEMO",
                    "schemaConfigurationId": "CONSULTING_SCHEMA_DEMO_V1_1",
                    "schemaVersion": "1",
                    "expectedSchemaFingerprint": "%s"
                  },
                  "defaults": {
                    "listSurface": { "rowMode": "INLINE_EDITABLE", "rowActions": ["SAVE", "DELETE"] },
                    "createSurface": { "placement": "INLINE_BELOW_LIST", "onSuccess": "STAY_ON_LIST" },
                    "standaloneViewEditRoutes": "KEEP_AS_FALLBACK",
                    "feedback": { "blockedDelete": "INLINE_BANNER", "success": "INLINE_BANNER" }
                  },
                  "perTable": [
                    { "tableId": "invoiceLines", "listSurface": { "rowMode": "READ_ONLY", "rowActions": ["VIEW", "EDIT", "DELETE"] } }
                  ]
                }
                """.formatted(blueprint.schemaFingerprint()));
        UiConfiguration altUiConfig = UiConfigurationLoader.loadFromFile(altUiFile);
        assertTrue(UiConfigurationValidator.validate(altUiConfig, blueprint).isEmpty());
        UiProjectionSnapshot altProjection = UiProjectionResolver.resolve(altUiConfig, config.tables());

        BusinessContext altContext = new BusinessContext(blueprint, altDatabase, Path.of("samples/business-schema-config.jsonc"),
                altDir.resolve("business-rules.jsonc"), altProjection, altUiFile);
        try (Connection connection = altDatabase.getConnection()) {
            altContext.auditService().ensureTable(connection);
        }

        HttpServer altServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        altServer.createContext("/", new RouteDispatcher(altContext));
        altServer.setExecutor(Executors.newCachedThreadPool());
        altServer.start();
        int altPort = altServer.getAddress().getPort();
        HttpClient altClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            HttpResponse<String> invoiceLinesList = altClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + altPort + "/records/invoiceLines")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, invoiceLinesList.statusCode());
            assertTrue(invoiceLinesList.body().contains(">View<") && invoiceLinesList.body().contains(">Edit<"),
                    "a READ_ONLY override must render View/Edit links");
            assertTrue(!invoiceLinesList.body().contains("form='edit-invoiceLines-"),
                    "a READ_ONLY table must not render live edit inputs");

            HttpResponse<String> clientsList = altClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + altPort + "/records/clients")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(clientsList.body().contains("No Clients yet") || clientsList.body().contains("form='edit-clients-"),
                    "a table with no override must still inherit INLINE_EDITABLE from defaults");
        } finally {
            altServer.stop(0);
        }
    }
}
