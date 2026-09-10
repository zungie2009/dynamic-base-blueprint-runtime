package org.blueprintruntime.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.config.FieldDefinition;
import org.blueprintruntime.config.TableDefinition;
import org.blueprintruntime.rules.AcceptanceExampleRunner;
import org.blueprintruntime.rules.ActiveRuleSetSnapshot;
import org.blueprintruntime.rules.RuleDefinition;
import org.blueprintruntime.rules.RuleSetLoader;
import org.blueprintruntime.rules.RuleSetValidator;
import org.blueprintruntime.runtime.BusinessContext;
import org.blueprintruntime.runtime.ForeignKeyRestrictionException;
import org.blueprintruntime.runtime.GenericRecord;
import org.blueprintruntime.runtime.NoSuchRecordException;
import org.blueprintruntime.runtime.ValidationException;
import org.blueprintruntime.uiconfig.UiSurfaceRule;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implements every {@code genericRoutes} entry over the JDK's {@code
 * com.sun.net.httpserver}, parameterized entirely by the runtime {@code tableId} path
 * segment — this class contains no table-, field-, or business-specific branching.
 */
public final class RouteDispatcher implements HttpHandler {

    private final BusinessContext context;
    private final HtmlRenderer renderer;

    public RouteDispatcher(BusinessContext context) {
        this.context = context;
        this.renderer = new HtmlRenderer(context.blueprint(), context.relationshipResolver(), context.uiProjection());
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (NoSuchRecordException e) {
            respondHtml(exchange, 404, renderer.renderError("Not found", e.getMessage(), 404));
        } catch (ForeignKeyRestrictionException e) {
            respondHtml(exchange, 409, renderer.renderError("Cannot delete", e.getMessage(), 409));
        } catch (RuntimeException | SQLException e) {
            respondHtml(exchange, 500, renderer.renderError("Unexpected error", String.valueOf(e.getMessage()), 500));
        }
    }

    private void route(HttpExchange exchange) throws IOException, SQLException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        BusinessConfiguration config = context.blueprint().configuration();

        if (path.equals("/") && method.equals("GET")) {
            respondHtml(exchange, 200, renderer.renderHome());
            return;
        }
        if (path.equals("/health") && method.equals("GET")) {
            respondJson(exchange, 200, "{\"status\":\"UP\"}");
            return;
        }
        if (path.equals("/api/blueprint") && method.equals("GET")) {
            respondJson(exchange, 200, blueprintIntrospectionJson());
            return;
        }
        if (path.equals("/api/rules") && method.equals("GET")) {
            respondJson(exchange, 200, ruleIntrospectionJson());
            return;
        }
        if (path.equals("/administration/rules/reload") && method.equals("POST")) {
            handleRuleReload(exchange);
            return;
        }

        if (path.startsWith("/records/")) {
            String[] segments = path.substring("/records/".length()).split("/", -1);
            String tableId = segments[0];
            if (config.table(tableId).isEmpty()) {
                respondHtml(exchange, 404, renderer.renderError("Unknown table", "No configured table '" + tableId + "'", 404));
                return;
            }
            TableDefinition table = config.requireTable(tableId);
            UiSurfaceRule rule = context.uiProjection().forTable(tableId);

            if (segments.length == 1 && method.equals("GET")) {
                handleList(exchange, table);
                return;
            }
            if (segments.length == 1 && method.equals("POST")) {
                handleCreate(exchange, table, rule);
                return;
            }
            if (segments.length == 2 && segments[1].equals("new") && method.equals("GET")) {
                if (rule.standaloneRoutesRemoved()) {
                    respondHtml(exchange, 404, renderer.renderError("Not found", "No route matches " + method + " " + path, 404));
                    return;
                }
                handleNewForm(exchange, table);
                return;
            }
            if (segments.length == 2 && method.equals("GET")) {
                if (rule.standaloneRoutesRemoved()) {
                    respondHtml(exchange, 404, renderer.renderError("Not found", "No route matches " + method + " " + path, 404));
                    return;
                }
                handleView(exchange, table, segments[1]);
                return;
            }
            if (segments.length == 3 && segments[2].equals("edit") && method.equals("GET")) {
                if (rule.standaloneRoutesRemoved()) {
                    respondHtml(exchange, 404, renderer.renderError("Not found", "No route matches " + method + " " + path, 404));
                    return;
                }
                handleEditForm(exchange, table, segments[1]);
                return;
            }
            if (segments.length == 2 && method.equals("POST")) {
                handleUpdate(exchange, table, segments[1], rule);
                return;
            }
            if (segments.length == 3 && segments[2].equals("delete") && method.equals("POST")) {
                handleDelete(exchange, table, segments[1], rule);
                return;
            }
        }

        respondHtml(exchange, 404, renderer.renderError("Not found", "No route matches " + method + " " + path, 404));
    }

    // -------------------------------------------------------------- handlers

    private void handleList(HttpExchange exchange, TableDefinition table) throws IOException, SQLException {
        List<GenericRecord> records = context.crudService().list(table.tableId());
        HtmlRenderer.FlashMessage flash = resolveFlash(exchange, table, records);
        try (Connection connection = context.database().getConnection()) {
            respondHtml(exchange, 200, flash != null
                    ? renderer.renderList(table, records, connection, flash)
                    : renderer.renderList(table, records, connection));
        }
    }

    /** Reads {@code ?flash=created|saved&id={recordId}} (see {@link #successRedirect}) into a banner, per {@code feedback.success}. */
    private HtmlRenderer.FlashMessage resolveFlash(HttpExchange exchange, TableDefinition table, List<GenericRecord> records) {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String flashKind = query.get("flash");
        String recordId = query.get("id");
        if (flashKind == null || recordId == null) return null;
        for (GenericRecord record : records) {
            if (recordId.equals(String.valueOf(record.get(table.requirePrimaryKeyField().fieldId())))) {
                String display = relationshipResolverDisplay(table, record);
                String verb = "created".equals(flashKind) ? "added" : "saved";
                return new HtmlRenderer.FlashMessage(recordId, table.singularLabel() + " \"" + display + "\" " + verb + ".");
            }
        }
        return null;
    }

    private String relationshipResolverDisplay(TableDefinition table, GenericRecord record) {
        return context.relationshipResolver().displayValue(table, record);
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return values;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            values.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    /** Where a create/update redirects on success, per that table's resolved {@code onSuccess}/{@code onSaveSuccess}. */
    private String successRedirect(TableDefinition table, String recordId, boolean stayOnList, boolean created) {
        return stayOnList
                ? "/records/" + table.tableId() + "?flash=" + (created ? "created" : "saved") + "&id=" + recordId + "#row-" + recordId
                : "/records/" + table.tableId() + "/" + recordId;
    }

    private void handleView(HttpExchange exchange, TableDefinition table, String recordId) throws IOException, SQLException {
        Optional<GenericRecord> record = context.crudService().view(table.tableId(), recordId);
        if (record.isEmpty()) {
            respondHtml(exchange, 404, renderer.renderError("Not found", "No record '" + recordId + "' in " + table.menuLabel(), 404));
            return;
        }
        try (Connection connection = context.database().getConnection()) {
            respondHtml(exchange, 200, renderer.renderView(table, record.get(), connection));
        }
    }

    private void handleNewForm(HttpExchange exchange, TableDefinition table) throws IOException, SQLException {
        try (Connection connection = context.database().getConnection()) {
            respondHtml(exchange, 200, renderer.renderForm(table, null, null, null, connection));
        }
    }

    private void handleEditForm(HttpExchange exchange, TableDefinition table, String recordId) throws IOException, SQLException {
        Optional<GenericRecord> record = context.crudService().view(table.tableId(), recordId);
        if (record.isEmpty()) {
            respondHtml(exchange, 404, renderer.renderError("Not found", "No record '" + recordId + "' in " + table.menuLabel(), 404));
            return;
        }
        try (Connection connection = context.database().getConnection()) {
            respondHtml(exchange, 200, renderer.renderForm(table, record.get(), null, null, connection));
        }
    }

    private void handleCreate(HttpExchange exchange, TableDefinition table, UiSurfaceRule rule) throws IOException, SQLException {
        Map<String, String> form = parseForm(exchange);
        try {
            GenericRecord created = context.crudService().createFromForm(table.tableId(), form);
            String recordId = String.valueOf(created.get(table.requirePrimaryKeyField().fieldId()));
            redirect(exchange, successRedirect(table, recordId, rule.staysOnListAfterCreate(), true));
        } catch (ValidationException e) {
            if (rule.createInline()) {
                // The create form lives inline on the list page, so a failed submission re-renders
                // that same list page with the inline form's errors and input intact.
                List<GenericRecord> records = context.crudService().list(table.tableId());
                try (Connection connection = context.database().getConnection()) {
                    respondHtml(exchange, 422, renderer.renderList(table, records, connection, e.fieldErrors(), form));
                }
            } else {
                try (Connection connection = context.database().getConnection()) {
                    respondHtml(exchange, 422, renderer.renderForm(table, null, e.fieldErrors(), form, connection));
                }
            }
        }
    }

    private void handleUpdate(HttpExchange exchange, TableDefinition table, String recordId, UiSurfaceRule rule) throws IOException, SQLException {
        Map<String, String> form = parseForm(exchange);
        try {
            context.crudService().updateFromForm(table.tableId(), recordId, form);
            redirect(exchange, successRedirect(table, recordId, rule.staysOnListAfterSave(), false));
        } catch (ValidationException e) {
            if (rule.isInlineEditable()) {
                // Editing happens inline in the list's own row, so a failed inline update re-renders
                // that same list page with this row's errors and edited input intact.
                List<GenericRecord> records = context.crudService().list(table.tableId());
                try (Connection connection = context.database().getConnection()) {
                    respondHtml(exchange, 422, renderer.renderList(table, records, connection,
                            new HtmlRenderer.RowEditState(recordId, e.fieldErrors(), form)));
                }
            } else {
                Optional<GenericRecord> existing = context.crudService().view(table.tableId(), recordId);
                try (Connection connection = context.database().getConnection()) {
                    respondHtml(exchange, 422, renderer.renderForm(table, existing.orElse(null), e.fieldErrors(), form, connection));
                }
            }
        }
    }

    private void handleDelete(HttpExchange exchange, TableDefinition table, String recordId, UiSurfaceRule rule) throws IOException, SQLException {
        try {
            context.crudService().deleteFromRoute(table.tableId(), recordId);
            redirect(exchange, "/records/" + table.tableId());
        } catch (ForeignKeyRestrictionException e) {
            if (rule.blockedDeleteIsInlineBanner()) {
                // A RESTRICT failure stays on the same list with the configured inline banner instead
                // of a standalone error page, so the rest of the table's records remain visible.
                List<GenericRecord> records = context.crudService().list(table.tableId());
                try (Connection connection = context.database().getConnection()) {
                    respondHtml(exchange, 409, renderer.renderList(table, records, connection, e.getMessage()));
                }
            } else {
                respondHtml(exchange, 409, renderer.renderError("Cannot delete", e.getMessage(), 409));
            }
        }
    }

    private void handleRuleReload(HttpExchange exchange) throws IOException {
        Path ruleFilePath = context.ruleFilePath();
        StringBuilder report = new StringBuilder();
        boolean activated = false;
        try {
            var candidate = RuleSetLoader.loadFromFile(ruleFilePath);
            var problems = RuleSetValidator.validate(candidate, context.blueprint());
            if (!problems.isEmpty()) {
                report.append("Rule set rejected: schema/type binding failed:\n");
                problems.forEach(p -> report.append(" - ").append(p).append('\n'));
            } else {
                var result = new AcceptanceExampleRunner().runAll(context.blueprint(), candidate);
                if (result.passed()) {
                    context.setActiveRuleSet(ActiveRuleSetSnapshot.activate(candidate, ruleFilePath.toString()));
                    activated = true;
                    report.append("Rule set '").append(candidate.ruleSetId()).append(' ').append(candidate.version())
                            .append("' activated at ").append(Instant.now()).append(" after ")
                            .append(candidate.acceptanceExamples().size()).append(" acceptance example(s) passed.\n");
                } else {
                    report.append("Rule set rejected: ").append(result.failures().size()).append(" acceptance example failure(s):\n");
                    for (var failure : result.failures()) {
                        report.append(" - ").append(failure.exampleName()).append(": ").append(failure.message()).append('\n');
                    }
                }
            }
        } catch (RuntimeException e) {
            report.append("Rule set rejected: ").append(e.getMessage()).append('\n');
        }
        context.setLastRuleReloadReport(report.toString());

        String json = "{\"activated\":" + activated + ",\"report\":" + jsonString(report.toString()) + "}";
        respondJson(exchange, activated ? 200 : 422, json);
    }

    // -------------------------------------------------------------- introspection

    private String blueprintIntrospectionJson() {
        BusinessConfiguration config = context.blueprint().configuration();
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"businessId\":").append(jsonString(config.businessId())).append(',');
        json.append("\"businessName\":").append(jsonString(config.businessName())).append(',');
        json.append("\"configurationId\":").append(jsonString(config.configurationId())).append(',');
        json.append("\"schemaVersion\":").append(jsonString(config.schemaVersion())).append(',');
        json.append("\"schemaFingerprint\":").append(jsonString(context.blueprint().schemaFingerprint())).append(',');
        json.append("\"databaseSchemaName\":").append(jsonString(context.blueprint().databaseSchemaName())).append(',');
        json.append("\"tables\":[");
        List<TableDefinition> tables = config.tablesByMenuOrder();
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) json.append(',');
            TableDefinition table = tables.get(i);
            json.append("{\"tableId\":").append(jsonString(table.tableId()))
                    .append(",\"menuLabel\":").append(jsonString(table.menuLabel()))
                    .append(",\"menuOrder\":").append(table.menuOrder())
                    .append(",\"displayField\":").append(jsonString(table.displayField()))
                    .append(",\"fields\":[");
            List<FieldDefinition> fields = table.fields();
            for (int j = 0; j < fields.size(); j++) {
                if (j > 0) json.append(',');
                FieldDefinition field = fields.get(j);
                json.append("{\"fieldId\":").append(jsonString(field.fieldId()))
                        .append(",\"type\":").append(jsonString(field.type().name()))
                        .append(",\"primaryKey\":").append(field.primaryKey())
                        .append(",\"required\":").append(field.required())
                        .append(",\"editable\":").append(field.editable())
                        .append(",\"unique\":").append(field.unique())
                        .append(",\"ruleWritable\":").append(field.ruleWritable());
                if (field.reference() != null) {
                    json.append(",\"referenceTable\":").append(jsonString(field.reference().tableId()))
                            .append(",\"referenceField\":").append(jsonString(field.reference().fieldId()));
                }
                json.append('}');
            }
            json.append("]}");
        }
        json.append("],\"uiConfiguration\":").append(uiIntrospectionJson(tables));
        json.append('}');
        return json.toString();
    }

    /** Part of {@code introspectionContract.mustInclude}: "resolved UI configuration with inheritance provenance." */
    private String uiIntrospectionJson(List<TableDefinition> tables) {
        var uiProjection = context.uiProjection();
        if (uiProjection == null) return "null";
        StringBuilder json = new StringBuilder();
        json.append("{\"uiConfigurationId\":").append(jsonString(uiProjection.uiConfigurationId())).append(",\"tables\":{");
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) json.append(',');
            TableDefinition table = tables.get(i);
            UiSurfaceRule rule = uiProjection.forTable(table.tableId());
            json.append(jsonString(table.tableId())).append(":{")
                    .append("\"rowMode\":").append(jsonString(rule.rowMode())).append(',')
                    .append("\"rowActions\":[");
            List<String> actions = rule.rowActions();
            for (int a = 0; a < actions.size(); a++) {
                if (a > 0) json.append(',');
                json.append(jsonString(actions.get(a)));
            }
            json.append("],")
                    .append("\"onSaveSuccess\":").append(jsonString(rule.onSaveSuccess())).append(',')
                    .append("\"createPlacement\":").append(jsonString(rule.createPlacement())).append(',')
                    .append("\"createOnSuccess\":").append(jsonString(rule.createOnSuccess())).append(',')
                    .append("\"standaloneViewEditRoutes\":").append(jsonString(rule.standaloneViewEditRoutes())).append(',')
                    .append("\"blockedDeletePresentation\":").append(jsonString(rule.blockedDeletePresentation()))
                    .append('}');
        }
        json.append("}}");
        return json.toString();
    }

    private String ruleIntrospectionJson() {
        ActiveRuleSetSnapshot snapshot = context.activeRuleSet();
        if (snapshot == null) {
            return "{\"active\":false,\"lastReloadReport\":" + jsonString(context.lastRuleReloadReport()) + "}";
        }
        StringBuilder json = new StringBuilder();
        json.append("{\"active\":true,");
        json.append("\"ruleSetId\":").append(jsonString(snapshot.ruleSet().ruleSetId())).append(',');
        json.append("\"version\":").append(jsonString(snapshot.ruleSet().version())).append(',');
        json.append("\"activatedAt\":").append(jsonString(snapshot.activatedAt().toString())).append(',');
        json.append("\"sourcePath\":").append(jsonString(snapshot.sourcePath())).append(',');
        json.append("\"dependencyOrder\":[");
        for (int i = 0; i < snapshot.topologicalOrder().size(); i++) {
            if (i > 0) json.append(',');
            json.append(jsonString(snapshot.topologicalOrder().get(i)));
        }
        json.append("],\"rules\":[");
        List<RuleDefinition> rules = snapshot.rulesInOrder();
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) json.append(',');
            RuleDefinition rule = rules.get(i);
            json.append("{\"ruleId\":").append(jsonString(rule.ruleId()))
                    .append(",\"targetTable\":").append(jsonString(rule.targetTable()))
                    .append(",\"crossTable\":").append(rule.isCrossTable())
                    .append(",\"operator\":").append(jsonString(rule.expression().operator()))
                    .append('}');
        }
        json.append("],\"lastReloadReport\":").append(jsonString(context.lastRuleReloadReport()));
        json.append('}');
        return json.toString();
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    // -------------------------------------------------------------- plumbing

    private Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        InputStream body = exchange.getRequestBody();
        String raw = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();
        if (raw.isBlank()) return values;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            values.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private void respondHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }
}
