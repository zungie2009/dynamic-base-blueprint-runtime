package org.blueprintruntime.http;

import org.blueprintruntime.blueprint.ResolvedBusinessBlueprint;
import org.blueprintruntime.config.FieldDefinition;
import org.blueprintruntime.config.FieldType;
import org.blueprintruntime.config.TableDefinition;
import org.blueprintruntime.runtime.GenericRecord;
import org.blueprintruntime.runtime.RelationshipResolver;
import org.blueprintruntime.runtime.ValueCodec;
import org.blueprintruntime.uiconfig.UiProjectionSnapshot;
import org.blueprintruntime.uiconfig.UiSurfaceRule;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the resolved fourth-layer {@link UiProjectionSnapshot} as server-rendered
 * HTML: one left-navigation entry per configured table in {@code menuOrder}, and
 * list/create/view/edit/delete surfaces whose interaction mode, actions, and
 * navigation are all read from that table's resolved {@link UiSurfaceRule} rather than
 * one fixed pattern — per {@code uiContract.authority}, this class does not hardcode a
 * single list/form interaction pattern, and no table, field, or business name is ever
 * written into this class's Java source.
 */
public final class HtmlRenderer {

    private final ResolvedBusinessBlueprint blueprint;
    private final RelationshipResolver relationshipResolver;
    private final UiProjectionSnapshot uiProjection;

    public HtmlRenderer(ResolvedBusinessBlueprint blueprint, RelationshipResolver relationshipResolver,
                         UiProjectionSnapshot uiProjection) {
        this.blueprint = blueprint;
        this.relationshipResolver = relationshipResolver;
        this.uiProjection = uiProjection;
    }

    public String renderHome() {
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(escape(blueprint.configuration().businessName())).append("</h1>");
        body.append("<p>Select a table from the left navigation to begin.</p>");
        body.append("<p style='color:var(--text-secondary)'>Business id <code>").append(escape(blueprint.businessId()))
                .append("</code> &middot; schema version <code>").append(escape(blueprint.schemaVersion()))
                .append("</code> &middot; fingerprint <code>").append(escape(blueprint.schemaFingerprint())).append("</code>"
                        + (uiProjection != null ? " &middot; UI configuration <code>" + escape(uiProjection.uiConfigurationId()) + "</code>" : ""))
                .append("</p>");
        return layout(blueprint.configuration().businessName(), body.toString(), null);
    }

    /** Carries a failed inline row-edit's field errors and re-submitted values back into the list page. */
    public record RowEditState(String recordId, Map<String, String> fieldErrors, Map<String, String> submittedValues) {}

    /** A dismissible success message shown after a create/save that stayed on the list, per {@code feedback.success}. */
    public record FlashMessage(String recordId, String text) {}

    public String renderList(TableDefinition table, List<GenericRecord> records, Connection connection) throws SQLException {
        return renderList(table, records, connection, null, null, null, null, null);
    }

    public String renderList(TableDefinition table, List<GenericRecord> records, Connection connection,
                              Map<String, String> createFieldErrors, Map<String, String> createSubmittedValues) throws SQLException {
        return renderList(table, records, connection, createFieldErrors, createSubmittedValues, null, null, null);
    }

    public String renderList(TableDefinition table, List<GenericRecord> records, Connection connection,
                              RowEditState rowError) throws SQLException {
        return renderList(table, records, connection, null, null, rowError, null, null);
    }

    public String renderList(TableDefinition table, List<GenericRecord> records, Connection connection,
                              String bannerError) throws SQLException {
        return renderList(table, records, connection, null, null, null, bannerError, null);
    }

    public String renderList(TableDefinition table, List<GenericRecord> records, Connection connection,
                              FlashMessage flash) throws SQLException {
        return renderList(table, records, connection, null, null, null, null, flash);
    }

    /**
     * The list page for one table, shaped entirely by that table's resolved {@link
     * UiSurfaceRule}: {@code rowMode} decides whether existing rows are live editable
     * inputs or plain cells with navigation links, {@code rowActions} decides which
     * buttons/links a row actually gets, and {@code createSurface.placement} decides
     * whether the create form lives on this page at all. {@code createFieldErrors}/
     * {@code createSubmittedValues} re-render a failed inline create; {@code rowError}
     * does the same for one existing row after a failed inline update; {@code
     * bannerError} shows a page-level message (a RESTRICT delete rejection); {@code
     * flash} shows a dismissible success message after a save/create that stayed on
     * this page. At most one of these situations applies to any single render.
     */
    private String renderList(TableDefinition table, List<GenericRecord> records, Connection connection,
                               Map<String, String> createFieldErrors, Map<String, String> createSubmittedValues,
                               RowEditState rowError, String bannerError, FlashMessage flash) throws SQLException {
        UiSurfaceRule rule = uiProjection.forTable(table.tableId());
        StringBuilder body = new StringBuilder();
        body.append("<div class='page-header'><h1>").append(escape(table.menuLabel())).append("</h1>");
        if (rule.createInline()) {
            body.append("<a class='btn' href='#add-record'>Add ").append(escape(table.singularLabel())).append("</a></div>");
        } else {
            body.append("<a class='btn' href='/records/").append(escape(table.tableId())).append("/new'>Add ")
                    .append(escape(table.singularLabel())).append("</a></div>");
        }

        if (bannerError != null) {
            body.append("<p class='error-banner'>").append(escape(bannerError)).append("</p>");
        }
        if (flash != null && rule.successBannerEnabled()) {
            body.append("<p class='success-banner'>").append(escape(flash.text()))
                    .append(" <a class='dismiss' href='/records/").append(escape(table.tableId())).append("'>&times;</a></p>");
        }

        boolean emptyStateShouldFocusCreate = records.isEmpty() && rule.createInline();

        if (records.isEmpty()) {
            body.append("<p class='empty-state'>No ").append(escape(table.menuLabel())).append(" yet. Add the first one below.</p>");
        } else if (rule.isInlineEditable()) {
            body.append(renderInlineEditableTable(table, records, connection, rule, rowError, flash));
        } else {
            body.append(renderReadOnlyTable(table, records, connection, rule));
        }

        if (rule.createInline()) {
            body.append("<section id='add-record' class='inline-create'>");
            body.append("<h2>Add ").append(escape(table.singularLabel())).append("</h2>");
            body.append("<form method='post' action='/records/").append(escape(table.tableId())).append("' class='record-form'>");
            body.append(renderFieldRows(table, null, createFieldErrors, createSubmittedValues, connection, emptyStateShouldFocusCreate));
            body.append("<div class='form-actions'><button type='submit' class='btn'>Add ")
                    .append(escape(table.singularLabel())).append("</button></div>");
            body.append("</form></section>");
        }

        return layout(table.menuLabel(), body.toString(), table.tableId());
    }

    private String renderInlineEditableTable(TableDefinition table, List<GenericRecord> records, Connection connection,
                                              UiSurfaceRule rule, RowEditState rowError, FlashMessage flash) throws SQLException {
        StringBuilder out = new StringBuilder();
        StringBuilder rowForms = new StringBuilder();
        out.append("<table class='data-table'><thead><tr>");
        for (FieldDefinition field : table.fields()) {
            out.append("<th>").append(escape(field.label())).append("</th>");
        }
        out.append("<th></th></tr></thead><tbody>");
        for (GenericRecord record : records) {
            String id = String.valueOf(record.get(table.requirePrimaryKeyField().fieldId()));
            boolean hasRowError = rowError != null && rowError.recordId().equals(id);
            boolean justSaved = flash != null && flash.recordId().equals(id);
            String editFormId = "edit-" + safeId(table.tableId()) + "-" + safeId(id);
            String deleteFormId = "delete-" + safeId(table.tableId()) + "-" + safeId(id);

            // Each row's edit/delete controls submit to their own empty <form> declared separately
            // (below) and associated purely via the standard HTML5 "form" attribute — a <form>
            // cannot itself contain other <form>s or be a valid child of <tbody>, so the row's
            // <td> cells hold ordinary inputs that point at a same-page form by id instead of
            // wrapping them.
            if (rule.actionEnabled("SAVE")) {
                rowForms.append("<form id='").append(editFormId).append("' method='post' action='/records/")
                        .append(escape(table.tableId())).append('/').append(escape(id)).append("'></form>");
            }
            if (rule.actionEnabled("DELETE")) {
                rowForms.append("<form id='").append(deleteFormId).append("' method='post' action='/records/")
                        .append(escape(table.tableId())).append('/').append(escape(id)).append("/delete'></form>");
            }

            out.append("<tr id='row-").append(escape(id)).append('\'')
                    .append(hasRowError ? " class='row-error'" : justSaved ? " class='row-saved'" : "").append('>');
            for (FieldDefinition field : table.fields()) {
                String fieldId = field.fieldId();
                if (field.type() == FieldType.IDENTITY || !field.editable() || !rule.actionEnabled("SAVE")) {
                    // Generated identity and rule-writable/calculated fields are shown, never edited;
                    // if SAVE itself is disabled for this table, every field is shown read-only too.
                    out.append("<td>").append(escape(displayCell(field, record, connection))).append("</td>");
                    continue;
                }
                String rawValue = hasRowError && rowError.submittedValues().containsKey(fieldId)
                        ? rowError.submittedValues().get(fieldId)
                        : ValueCodec.toDisplayString(field, record.get(fieldId));
                out.append("<td>").append(renderControl(field, fieldId, rawValue, connection, "form='" + editFormId + "'", false));
                if (hasRowError && rowError.fieldErrors().containsKey(fieldId)) {
                    out.append("<div class='field-error'>").append(escape(rowError.fieldErrors().get(fieldId))).append("</div>");
                }
                out.append("</td>");
            }
            out.append("<td class='row-actions'>");
            if (rule.actionEnabled("SAVE")) {
                out.append("<button type='submit' form='").append(editFormId).append("' class='btn btn-sm'>Save</button> ");
            }
            if (rule.actionEnabled("DELETE")) {
                out.append("<button type='submit' form='").append(deleteFormId)
                        .append("' class='link-button' onclick=\"return confirm('Delete this record?');\">Delete</button>");
            }
            out.append("</td></tr>");
        }
        out.append("</tbody></table>");
        out.append(rowForms);
        return out.toString();
    }

    /** {@code rowMode: READ_ONLY} — plain cells, plus whichever of VIEW/EDIT/DELETE this table's rowActions enables. */
    private String renderReadOnlyTable(TableDefinition table, List<GenericRecord> records, Connection connection,
                                        UiSurfaceRule rule) throws SQLException {
        StringBuilder out = new StringBuilder();
        out.append("<table class='data-table'><thead><tr>");
        for (FieldDefinition field : table.fields()) {
            out.append("<th>").append(escape(field.label())).append("</th>");
        }
        out.append("<th></th></tr></thead><tbody>");
        for (GenericRecord record : records) {
            String id = String.valueOf(record.get(table.requirePrimaryKeyField().fieldId()));
            out.append("<tr id='row-").append(escape(id)).append("'>");
            for (FieldDefinition field : table.fields()) {
                out.append("<td>").append(escape(displayCell(field, record, connection))).append("</td>");
            }
            out.append("<td class='row-actions'>");
            if (rule.actionEnabled("VIEW")) {
                out.append("<a href='/records/").append(escape(table.tableId())).append('/').append(escape(id)).append("'>View</a> ");
            }
            if (rule.actionEnabled("EDIT")) {
                out.append("<a href='/records/").append(escape(table.tableId())).append('/').append(escape(id)).append("/edit'>Edit</a> ");
            }
            if (rule.actionEnabled("DELETE")) {
                out.append("<form method='post' action='/records/").append(escape(table.tableId())).append('/').append(escape(id))
                        .append("/delete' style='display:inline' onsubmit=\"return confirm('Delete this record?');\">")
                        .append("<button type='submit' class='link-button'>Delete</button></form>");
            }
            out.append("</td></tr>");
        }
        out.append("</tbody></table>");
        return out.toString();
    }

    public String renderView(TableDefinition table, GenericRecord record, Connection connection) throws SQLException {
        StringBuilder body = new StringBuilder();
        String id = String.valueOf(record.get(table.requirePrimaryKeyField().fieldId()));
        body.append("<div class='page-header'><h1>").append(escape(table.singularLabel())).append(": ")
                .append(escape(relationshipResolver.displayValue(table, record))).append("</h1>");
        body.append("<a class='btn' href='/records/").append(escape(table.tableId())).append('/').append(escape(id)).append("/edit'>Edit</a></div>");

        body.append("<table class='detail-table'>");
        for (FieldDefinition field : table.fields()) {
            body.append("<tr><th>").append(escape(field.label())).append("</th><td>")
                    .append(escape(displayCell(field, record, connection))).append("</td></tr>");
        }
        body.append("</table>");
        body.append("<p><a href='/records/").append(escape(table.tableId())).append("'>&larr; Back to ")
                .append(escape(table.menuLabel())).append("</a></p>");
        return layout(table.singularLabel(), body.toString(), table.tableId());
    }

    public String renderForm(TableDefinition table, GenericRecord existing, Map<String, String> fieldErrors,
                              Map<String, String> submittedValues, Connection connection) throws SQLException {
        boolean isCreate = existing == null;
        String action = isCreate ? "/records/" + table.tableId() : "/records/" + table.tableId() + "/"
                + existing.get(table.requirePrimaryKeyField().fieldId());

        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(isCreate ? "Add " : "Edit ").append(escape(table.singularLabel())).append("</h1>");
        body.append("<form method='post' action='").append(escape(action)).append("' class='record-form'>");
        body.append(renderFieldRows(table, existing, fieldErrors, submittedValues, connection, false));
        body.append("<div class='form-actions'>")
                .append("<button type='submit' class='btn'>Save</button> ")
                .append("<a href='/records/").append(escape(table.tableId())).append("'>Cancel</a></div>");
        body.append("</form>");
        return layout((isCreate ? "Add " : "Edit ") + table.singularLabel(), body.toString(), table.tableId());
    }

    /**
     * Every editable field's control (textbox, textarea, checkbox, select, date/number
     * input — derived from {@link FieldType} per {@code fieldTypeControlMap}), shared
     * by the standalone create/edit form and the list page's inline create section so
     * the two never drift apart. Generated identity fields and calculated
     * ({@code editable=false}) fields never get an input here, per {@code
     * fieldPresentation.identityOnCreate: HIDDEN} and {@code .calculatedFields:
     * READ_ONLY_FORMATTED}. {@code autofocusFirst} implements {@code
     * listSurface.emptyStateAction: FOCUS_INLINE_CREATE_FORM}.
     */
    private String renderFieldRows(TableDefinition table, GenericRecord existing, Map<String, String> fieldErrors,
                                    Map<String, String> submittedValues, Connection connection, boolean autofocusFirst) throws SQLException {
        StringBuilder rows = new StringBuilder();
        boolean first = true;
        for (FieldDefinition field : table.fields()) {
            if (field.type() == FieldType.IDENTITY) continue;
            if (!field.editable()) continue; // generated/rule-writable fields are never form inputs

            String fieldId = field.fieldId();
            String rawValue = submittedValues != null && submittedValues.containsKey(fieldId)
                    ? submittedValues.get(fieldId)
                    : (existing != null ? ValueCodec.toDisplayString(field, existing.get(fieldId)) : (field.defaultValue() != null ? field.defaultValue() : ""));

            rows.append("<div class='form-row'><label for='f_").append(escape(fieldId)).append("'>").append(escape(field.label()));
            if (field.required()) rows.append(" *");
            rows.append("</label>");
            rows.append(renderControl(field, fieldId, rawValue, connection, "id='f_" + escape(fieldId) + "'", autofocusFirst && first));
            first = false;
            if (fieldErrors != null && fieldErrors.containsKey(fieldId)) {
                rows.append("<div class='field-error'>").append(escape(fieldErrors.get(fieldId))).append("</div>");
            }
            rows.append("</div>");
        }
        return rows.toString();
    }

    public String renderError(String title, String message, int statusHint) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(escape(title)).append("</h1>");
        body.append("<p class='error-banner'>").append(escape(message)).append("</p>");
        body.append("<p><a href='/'>&larr; Home</a></p>");
        return layout(title, body.toString(), null);
    }

    // ----------------------------------------------------------- helpers

    private String displayCell(FieldDefinition field, GenericRecord record, Connection connection) throws SQLException {
        Object value = record.get(field.fieldId());
        if (field.type() == FieldType.REFERENCE && value != null) {
            TableDefinition referencedTable = blueprint.configuration().requireTable(field.reference().tableId());
            return relationshipResolver.resolveReferenceLabel(connection, referencedTable, value).orElse(String.valueOf(value));
        }
        return ValueCodec.toDisplayString(field, value);
    }

    private String renderControl(FieldDefinition field, String fieldId, String rawValue, Connection connection,
                                  String extraAttr, boolean autofocus) throws SQLException {
        String name = "name='" + escape(fieldId) + "' " + extraAttr + (autofocus ? " autofocus" : "");
        return switch (field.type()) {
            case TEXT -> "<textarea " + name + " rows='4'>" + escape(rawValue) + "</textarea>";
            case BOOLEAN -> "<input type='checkbox' " + name + " value='true'"
                    + ("true".equalsIgnoreCase(rawValue) ? " checked" : "") + ">";
            case ENUM -> renderEnumSelect(field, name, rawValue);
            case REFERENCE -> renderReferenceSelect(field, name, rawValue, connection);
            case DATE -> "<input type='date' " + name + " value='" + escape(rawValue) + "'>";
            case DATETIME -> "<input type='datetime-local' " + name + " value='" + escape(rawValue) + "'>";
            case EMAIL -> "<input type='email' " + name + " value='" + escape(rawValue) + "'>";
            case INTEGER -> "<input type='number' step='1' " + name + " value='" + escape(rawValue) + "'>";
            case DECIMAL_19_2 -> "<input type='number' step='0.01' " + name + " value='" + escape(rawValue) + "'>";
            default -> "<input type='text' " + name + " value='" + escape(rawValue) + "'"
                    + (field.length() != null ? " maxlength='" + field.length() + "'" : "") + ">";
        };
    }

    private String renderEnumSelect(FieldDefinition field, String name, String rawValue) {
        StringBuilder select = new StringBuilder("<select ").append(name).append(">");
        if (!field.required()) select.append("<option value=''></option>");
        for (String option : field.enumValues()) {
            select.append("<option value='").append(escape(option)).append('\'')
                    .append(option.equals(rawValue) ? " selected" : "").append('>').append(escape(option)).append("</option>");
        }
        select.append("</select>");
        return select.toString();
    }

    private String renderReferenceSelect(FieldDefinition field, String name, String rawValue, Connection connection) throws SQLException {
        TableDefinition referencedTable = blueprint.configuration().requireTable(field.reference().tableId());
        StringBuilder select = new StringBuilder("<select ").append(name).append(">");
        if (!field.required()) select.append("<option value=''></option>");
        for (RelationshipResolver.PickerOption option : relationshipResolver.pickerOptions(connection, referencedTable)) {
            select.append("<option value='").append(escape(option.value())).append('\'')
                    .append(option.value().equals(rawValue) ? " selected" : "").append('>')
                    .append(escape(option.label())).append("</option>");
        }
        select.append("</select>");
        return select.toString();
    }

    private String layout(String title, String bodyHtml, String activeTableId) {
        StringBuilder nav = new StringBuilder();
        for (TableDefinition table : blueprint.configuration().tablesByMenuOrder()) {
            boolean active = table.tableId().equals(activeTableId);
            nav.append("<a class='nav-link").append(active ? " active" : "").append("' href='/records/")
                    .append(escape(table.tableId())).append("'>").append(escape(table.menuLabel())).append("</a>");
        }
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <title>%s &middot; %s</title>
                <style>%s</style>
                </head>
                <body>
                <header class="top-header"><a href="/" class="brand">%s</a></header>
                <div class="layout">
                <nav class="side-nav">%s</nav>
                <main class="content">%s</main>
                </div>
                </body>
                </html>
                """.formatted(escape(title), escape(blueprint.configuration().businessName()), css(),
                escape(blueprint.configuration().businessName()), nav, bodyHtml);
    }

    private static String safeId(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '-');
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ----------------------------------------------------------- theme

    /**
     * The theme tokens this runtime falls back to when {@code business-ui.jsonc} omits
     * one — chosen to match the sample's own "Professional Blue" theme, so the shipped
     * application looks identical whether or not the file spells out every token.
     * Overridden per {@link #css()} by whatever {@code theme.tokens} the active UI
     * configuration actually declares.
     */
    private static final Map<String, String> BUILT_IN_THEME_TOKENS = Map.ofEntries(
            Map.entry("pageBackground", "#F4F7FB"),
            Map.entry("surface", "#FFFFFF"),
            Map.entry("surfaceMuted", "#F8FAFC"),
            Map.entry("textPrimary", "#172033"),
            Map.entry("textSecondary", "#5E6B82"),
            Map.entry("border", "#DCE3EE"),
            Map.entry("primary", "#2457D6"),
            Map.entry("primaryHover", "#1846B8"),
            Map.entry("primarySoft", "#EAF0FF"),
            Map.entry("danger", "#B42318"),
            Map.entry("dangerSoft", "#FEECEB"),
            Map.entry("success", "#067647"),
            Map.entry("successSoft", "#ECFDF3"),
            Map.entry("focusRing", "#84ADFF"),
            Map.entry("borderRadiusPx", "10"),
            Map.entry("cardShadow", "0 8px 24px rgba(23,32,51,0.08)")
    );
    private static final String BUILT_IN_FONT_FAMILY =
            "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif";

    private String token(String key) {
        String fromFile = uiProjection != null ? uiProjection.themeTokens().get(key) : null;
        String value = fromFile != null ? fromFile : BUILT_IN_THEME_TOKENS.get(key);
        return key.endsWith("Px") && !value.endsWith("px") ? value + "px" : value;
    }

    private String fontFamily() {
        String fromFile = uiProjection != null ? uiProjection.fontFamily() : null;
        return fromFile != null ? fromFile : BUILT_IN_FONT_FAMILY;
    }

    /**
     * Generated from {@code theme.tokens}/{@code theme.fontFamily} rather than
     * hardcoded, so that "changing a color or spacing token and restarting changes
     * presentation without changing the computed schema fingerprint or database
     * objects" (an {@code acceptanceScenarios} entry) is literally true: this method
     * is the only place those tokens are read, and nothing about schema provisioning
     * or the rule engine ever consults this class.
     */
    private String css() {
        return """
                * { box-sizing: border-box; }
                :root {
                  --page-bg: %s; --surface: %s; --surface-muted: %s;
                  --text-primary: %s; --text-secondary: %s; --border: %s;
                  --primary: %s; --primary-hover: %s; --primary-soft: %s;
                  --danger: %s; --danger-soft: %s; --success: %s; --success-soft: %s;
                  --focus-ring: %s; --radius: %s; --card-shadow: %s;
                }
                body { margin:0; font-family: %s; color:var(--text-primary); background:var(--page-bg); }
                .top-header { background:var(--text-primary); color:#fff; padding:0.75rem 1.25rem; }
                .top-header .brand { color:#fff; text-decoration:none; font-weight:600; font-size:1.1rem; }
                .layout { display:flex; min-height:calc(100vh - 48px); }
                .side-nav { width:220px; background:var(--surface); border-right:1px solid var(--border); padding:1rem 0; }
                .side-nav .nav-link { display:block; padding:0.5rem 1.25rem; color:var(--text-primary); text-decoration:none; }
                .side-nav .nav-link:hover { background:var(--surface-muted); }
                .side-nav .nav-link.active { background:var(--primary-soft); color:var(--primary); font-weight:600; }
                .content { flex:1; padding:1.5rem 2rem; min-width:0; }
                .page-header { display:flex; align-items:center; justify-content:space-between; margin-bottom:1rem; }
                h1 { font-size:1.4rem; margin:0 0 0.5rem 0; }
                .btn { display:inline-block; background:var(--primary); color:#fff; padding:0.45rem 0.9rem; border-radius:var(--radius); text-decoration:none; font-size:0.9rem; border:none; cursor:pointer; }
                .btn:hover { background:var(--primary-hover); }
                .btn-sm { padding:0.3rem 0.6rem; font-size:0.8rem; }
                .data-table { width:100%%; border-collapse: collapse; background:var(--surface); box-shadow:var(--card-shadow); border-radius:var(--radius); }
                .data-table th, .data-table td { padding:0.5rem 0.75rem; border-bottom:1px solid var(--border); text-align:left; font-size:0.9rem; }
                .data-table th { color:var(--text-secondary); font-weight:600; }
                .data-table input, .data-table select { width:100%%; padding:0.3rem 0.4rem; border:1px solid var(--border); border-radius:6px; font-size:0.85rem; }
                .row-actions a, .row-actions .link-button { margin-right:0.5rem; color:var(--primary); text-decoration:none; font-size:0.85rem; }
                .link-button { background:none; border:none; padding:0; cursor:pointer; font-family:inherit; }
                .row-error td { background:var(--danger-soft); }
                .row-saved td { background:var(--success-soft); }
                .detail-table th { text-align:left; color:var(--text-secondary); padding:0.4rem 1rem 0.4rem 0; vertical-align:top; }
                .detail-table td { padding:0.4rem 0; }
                .record-form { max-width:520px; background:var(--surface); padding:1.25rem; border-radius:var(--radius); border:1px solid var(--border); box-shadow:var(--card-shadow); }
                .form-row { margin-bottom:0.9rem; display:flex; flex-direction:column; gap:0.3rem; }
                .form-row label { font-size:0.85rem; color:var(--text-primary); font-weight:600; }
                .form-row input, .form-row select, .form-row textarea { padding:0.4rem 0.5rem; border:1px solid var(--border); border-radius:6px; font-size:0.95rem; }
                .form-row input:focus, .form-row select:focus, .form-row textarea:focus { outline:2px solid var(--focus-ring); }
                .field-error { color:var(--danger); font-size:0.8rem; }
                .form-actions { margin-top:1rem; display:flex; gap:1rem; align-items:center; }
                .empty-state { color:var(--text-secondary); }
                .inline-create { margin-top:2rem; padding-top:1.5rem; border-top:1px solid var(--border); }
                .inline-create h2 { font-size:1.05rem; margin:0 0 0.75rem 0; }
                .error-banner { background:var(--danger-soft); color:var(--danger); padding:0.75rem 1rem; border-radius:var(--radius); }
                .success-banner { background:var(--success-soft); color:var(--success); padding:0.75rem 1rem; border-radius:var(--radius); display:flex; justify-content:space-between; }
                .success-banner .dismiss { color:var(--success); text-decoration:none; font-weight:700; }
                @media (max-width: 900px) {
                  .layout { flex-direction:column; }
                  .side-nav { width:100%%; display:flex; overflow-x:auto; border-right:none; border-bottom:1px solid var(--border); }
                  .side-nav .nav-link { white-space:nowrap; }
                  .data-table { display:block; overflow-x:auto; }
                }
                """.formatted(
                token("pageBackground"), token("surface"), token("surfaceMuted"),
                token("textPrimary"), token("textSecondary"), token("border"),
                token("primary"), token("primaryHover"), token("primarySoft"),
                token("danger"), token("dangerSoft"), token("success"), token("successSoft"),
                token("focusRing"), token("borderRadiusPx"), token("cardShadow"),
                fontFamily());
    }
}
