# Base Blueprint Runtime

A domain-neutral runtime that reconstructs a business application from four external,
JSONC-encoded layers: a static Java contract (this codebase), a semi-static
**schema** configuration, a hot-reloadable **business rules** file, and — new in
v1.5 — a restart-reloadable **UI configuration**. This project implements the
`LLM_BASE_BLUEPRINT_CONSTRUCTION_CONTRACT_V1_5` contract in full: the Java source
never mentions a business, table, or field name, and it never hardcodes one
list/form interaction pattern either — every table, field, form, menu, foreign key,
calculation, and interaction behavior is derived at runtime from
`business-schema-config.jsonc`, `business-rules.jsonc`, and `business-ui.jsonc`.

The included sample files describe a small consulting business (clients, engagements,
invoices, invoice lines with a subtotal/tax/total calculation), but nothing about that
domain is compiled into the JAR. Swapping in a different, compatible set of the three
files and restarting reconstructs a different application — schema, rules, *and*
look/interaction — from the same unmodified JAR.

## What's here

```
pom.xml                              Maven project descriptor
src/main/java/org/blueprintruntime/  Complete runtime source (53 files)
src/test/java/org/blueprintruntime/  Unit + HTTP integration tests (10 files)
samples/business-schema-config.jsonc External sample schema configuration
samples/business-rules.jsonc         External sample business rule set
samples/business-ui.jsonc            External sample UI configuration (the fourth layer)
```

Package layout:

| Package                          | Responsibility                                                             |
|-----------------------------------|-----------------------------------------------------------------------------|
| `json`                            | Hand-rolled JSONC parser (no external JSON library)                       |
| `config`                          | Parsed schema configuration model, loader, structural validator          |
| `blueprint`                       | Resolves a configuration into the active `ResolvedBusinessBlueprint`      |
| `schema`                          | Schema fingerprinting, DDL planning, provisioning, the schema registry    |
| `runtime`                         | Generic CRUD, record validation, value coercion, relationship resolution |
| `rules`                           | The dynamic rule engine, rule loading/validation, acceptance-example runner |
| `uiconfig`                        | The fourth layer: UI configuration model, loader, validator, projection resolver |
| `audit`                           | The `DI_RUNTIME_AUDIT` audit trail                                        |
| `http`                            | The JDK `com.sun.net.httpserver`-based routes and server-rendered HTML UI |

## Requirements

- Java 21 (JDK, not just a JRE)
- Maven 3.9+
- Internet access the *first* time you build, to download the two declared
  dependencies: `com.h2database:h2:2.3.232` (runtime only — the main source imports
  only `java.sql`, never `org.h2.*`) and `org.junit.jupiter:junit-jupiter:5.10.2`
  (test scope only). No other dependency exists anywhere in this project — no Spring,
  no Node, no application server, per the contract's `frameworkPolicy`.

## Build

```
mvn clean verify
```

This compiles the runtime, compiles and runs the full test suite (unit tests plus the
HTTP integration test, which boots a real embedded H2-backed instance of the server on
an ephemeral loopback port), and packages `target/base-blueprint-runtime-1.5.0.jar` as
a single runnable JAR (via `maven-shade-plugin`) with the H2 driver merged in.

To build without running tests:

```
mvn clean package -DskipTests
```

## Run

```
java -jar target/base-blueprint-runtime-1.5.0.jar \
  --config ./business-schema-config.jsonc \
  --rules  ./business-rules.jsonc \
  --ui     ./business-ui.jsonc
```

Copy the three sample files from `samples/` next to the JAR first (or pass `--config`,
`--rules`, and `--ui` pointing directly at the files under `samples/`). Then open
<http://127.0.0.1:8080/>.

Command-line flags (all optional):

| Flag         | Default                          | Meaning                                |
|--------------|-----------------------------------|-----------------------------------------|
| `--config`   | `./business-schema-config.jsonc` | Path to the business schema configuration |
| `--rules`    | `./business-rules.jsonc`         | Path to the business rule set            |
| `--ui`       | `./business-ui.jsonc`            | Path to the UI configuration (the fourth layer) |
| `--bind`     | `127.0.0.1`                      | Address to listen on                     |
| `--port`     | `8080`                           | Port to listen on (fails loudly if taken; never silently picks another) |
| `--database` | `data/business-runtime`          | H2 embedded-file database path (H2 appends its own suffix) |

On startup the runtime loads and validates the schema configuration, provisions the
business's database schema if it is not already installed (or verifies the installed
schema's structural fingerprint still matches, refusing to proceed with a silent
migration if it does not), loads and validates the rule set and runs every declared
acceptance example against an isolated in-memory schema, then loads and validates the
UI configuration and resolves it into a `UiProjectionSnapshot`, and only then starts
serving. Any failure along the way — in any of the three files — prints a validation
report to stderr and exits without starting a partial UI.

### Reloading rules without restarting

```
curl -X POST http://127.0.0.1:8080/administration/rules/reload
```

Edit `business-rules.jsonc` on disk, then POST to this route (bound to loopback by
default — this is a demonstration, not production authentication). The candidate rule
set is validated against the active schema and must pass every one of its own declared
acceptance examples before it atomically replaces the active rule set; otherwise the
previously active rule set stays in force and the response reports why the candidate
was rejected. The compiled JAR is never touched.

There is no equivalent reload route for the UI configuration: per
`uiReloadPolicy: RESTART_REQUIRED_FOR_V1_5`, a UI-file change takes effect on the next
restart of the process (never a recompilation, and never a database change — see
"The fourth layer" below).

### Generic routes

| Method + path                              | Purpose                          |
|---------------------------------------------|-----------------------------------|
| `GET /`                                     | Home page                        |
| `GET /records/{tableId}`                    | List (the primary workspace for a table — see below) |
| `GET /records/{tableId}/new`                | Standalone create form            |
| `POST /records/{tableId}`                   | Create                            |
| `GET /records/{tableId}/{recordId}`         | Standalone view                   |
| `GET /records/{tableId}/{recordId}/edit`    | Standalone edit form              |
| `POST /records/{tableId}/{recordId}`        | Update                            |
| `POST /records/{tableId}/{recordId}/delete` | Delete                            |
| `GET /health`                               | Liveness check                    |
| `GET /api/blueprint`                        | Resolved configuration, including the resolved UI projection, as JSON |
| `GET /api/rules`                            | Active rule set introspection     |
| `POST /administration/rules/reload`         | Reload the rule file at runtime   |

The standalone view/new/edit routes 404 if the active UI configuration sets that
table's `standaloneViewEditRoutes` to `REMOVE` (the sample keeps `KEEP_AS_FALLBACK`,
so they stay reachable even though nothing in the sample's UI links to them).

## The fourth layer: `business-ui.jsonc`

Per `uiConfigurationContract`, UI behavior is now its own external, independently
validated layer — not a Java default and not something baked into the schema file.
`business-ui.jsonc` declares, per table (with a `defaults` block every table inherits
and optional `perTable` overrides that replace only the properties they name):

- `listSurface.rowMode` — `INLINE_EDITABLE` (existing rows are live inputs you edit
  and save in place — view and edit are the same action) or `READ_ONLY` (plain cells
  plus `View`/`Edit` links to the standalone routes).
- `listSurface.rowActions` — which of `VIEW`/`EDIT`/`SAVE`/`DELETE` a row actually gets.
- `listSurface.onSaveSuccess` / `createSurface.onSuccess` — `STAY_ON_LIST` (redirect
  back to the list, with a `#row-{id}` anchor and, if `feedback.success` is
  `INLINE_BANNER`, a dismissible success banner) or `GO_TO_VIEW` (redirect to the
  record's standalone page).
- `createSurface.placement` — `INLINE_BELOW_LIST` (the create form lives under the
  table on the same page) or `SEPARATE_ROUTE` (only a link to `/new`).
- `standaloneViewEditRoutes` — `KEEP_AS_FALLBACK` or `REMOVE` (404 the view/new/edit
  routes entirely; rejected at validation time if anything — a `READ_ONLY` row, an
  enabled `VIEW`/`EDIT` action, or a `SEPARATE_ROUTE` create — still needs them).
- `feedback.blockedDelete` — `INLINE_BANNER` (a `RESTRICT` delete failure re-renders
  the same list with a red banner; every other record stays visible) or
  `SEPARATE_ERROR_PAGE` (the old standalone error page).
- `theme.tokens` / `theme.fontFamily` — colors, radius, shadow, and font, read live
  into the generated CSS custom properties; changing one and restarting changes the
  rendered page without touching the schema fingerprint, the schema registry, or any
  database object, because `UiConfigurationLoader`/`Validator`/`Resolver` never call
  anything in the `schema` or `rules` packages.

`UiConfigurationValidator` checks identity/schema-fingerprint binding (the same
three-part binding pattern as the rule file), that every `perTable.tableId` names a
real table at most once, that every declared value is one of this format's
recognized literals, and the contradiction rules the contract states explicitly
(`INLINE_EDITABLE` without `SAVE`, `READ_ONLY` with `SAVE`, `REMOVE` while something
still points at the removed routes). A file that fails any of these is reported and
rejected before the business UI starts, exactly like an invalid schema or rule file.

**Scope decision, stated plainly:** the contract's `allowedVocabulary` and the sample
file both include a number of presentation properties this project does not thread
through as live, per-request-varying behavior — `fieldPresentation.decimalScale`,
`.dateFormat`, `.referenceOptionValue/Label`; `validation.focusFirstInvalidField`;
`createSurface.titlePattern/submitLabelPattern/container/resetAfterSuccess`;
`listSurface.container/actionPlacement/savedRowAnchor` (beyond the `#row-{id}` anchor
itself); `feedback.blockedDeleteTone/dismissible`. These are accepted without error
(the loader simply doesn't read those keys) and the runtime's fixed, generic
rendering already produces output consistent with the sample's declared values for
all of them (money is always `BigDecimal` at scale 2 regardless of a `decimalScale`
token; a native `<input type=date>` is locale-aware by the browser, not by this code;
the create button is already labeled `"Add {singularLabel}"`, matching
`submitLabelPattern` literally). Changing one of those specific properties in the
file will not currently change the rendered output. What *is* live and
config-driven, and does change output when edited: `rowMode`, `rowActions`,
`onSaveSuccess`/`onSuccess`, `createPlacement`, `standaloneViewEditRoutes`,
`feedback.blockedDelete`/`feedback.success`, and every `theme.tokens`/`fontFamily`
value.

## Trying a second business without recompiling

Per the contract's `experimentAcceptance.phaseTwo`: write a second, compatible set of
`business-schema-config.jsonc` / `business-rules.jsonc` / `business-ui.jsonc`, point
`--config`/`--rules`/`--ui` at it, and restart the same JAR. The new schema, rules,
and UI are all derived from the new configuration; the JAR's SHA-256 is unchanged
because nothing about the previous business was ever compiled into it.

## Tests

`mvn clean verify` runs all of these:

- **`JsonParserTest`, `ValueCodecTest`** — the JSONC parser and the per-field-type
  value coercion rules (`BigDecimal` for money, never a double round-trip; integers
  normalized identically from form and JSON input), independent of any database.
- **`ConfigurationLoaderTest`** — a valid configuration loads and resolves; a
  programmatically-constructed malformed JSON file and a programmatically-constructed
  semantically invalid configuration (duplicate field id, no primary key) are both
  rejected before any database mutation is attempted.
- **`FingerprintCalculatorTest`** — the schema fingerprint SHA-256 exactly matches the
  value independently computed during this project's design review and declared in
  the sample rule and UI files' `expectedSchemaFingerprint`; the fingerprint is
  deterministic and changes when a field is renamed.
- **`RuleSetLoaderAndValidatorTest`** — the sample rule set binds cleanly to the
  sample schema and produces a valid topological evaluation order; a rule referencing
  an undeclared field, and a rule set bound to a stale fingerprint, are both rejected.
- **`UiConfigurationLoaderAndValidatorTest`** — the sample UI file binds cleanly and
  every table resolves to the declared inline-editable/save-and-delete/inline-create/
  stay-on-list defaults; a wrong fingerprint, an unknown `perTable` table, a duplicate
  `perTable` entry, an `INLINE_EDITABLE` table without `SAVE`, a `READ_ONLY` table
  with `SAVE`, a `REMOVE` that would orphan a link, an unrecognized vocabulary value,
  and an explicit JSON `null` (rejected rather than treated as "inherit," per
  `composition.nullRule`) are each rejected; a real `perTable` override changes only
  its own table; two configurations differing only in theme tokens both validate
  identically. **This entire test class needs no database and was executed for
  real in this sandbox** (see "How this was built and verified" below) — every one
  of its cases genuinely passed, not merely compiled.
- **`SchemaProvisionerTest`** — a missing schema is created on first activation; an
  unchanged configuration reuses the installed schema; a changed structural
  fingerprint is rejected as migration-required, leaving the installed schema
  untouched.
- **`AcceptanceExampleRunnerTest`** — runs all five of the sample rule set's declared
  acceptance examples (line-amount arithmetic, cross-table aggregation, reparenting
  recalculating both the former and new parent, an in-place edit recalculating an
  unchanged parent chain, and delete propagation) through the exact production rule
  engine and CRUD service, including the exact declared audit-event and
  rule-evaluation counts and causation lineage.
- **`HttpIntegrationTest`** — boots the real HTTP server against a temporary embedded
  H2 database and exercises it as a client would: schema provisioning, one
  navigation entry per configured table, all five CRUD operations, a reference
  picker populated from the referenced table's `displayField`, required/email
  validation producing field-level errors, `ON DELETE RESTRICT` rejecting deletion of
  a referenced parent, the introspection endpoints, both a rejected and an accepted
  rule reload, restart persistence, the inline create form on an empty and a
  populated list, a create/save success banner with the row-anchor redirect, a
  blocked delete's inline banner (with every other record still visible on the same
  page), and — via a second, independently started server bound to a temporary
  `business-ui.jsonc` with a `perTable` override — that overriding one table to
  `READ_ONLY` renders `View`/`Edit` links for that table alone while every other
  table keeps inheriting `INLINE_EDITABLE` from `defaults`.
- **`SourceScanTest`** — a literal scan of every file under `src/main/java` confirms
  none of the sample configuration's business, table, field, or UI-configuration
  identifiers appear anywhere in the runtime's Java source.

## A note on how this was built and verified

This project was generated and verified inside a network-restricted sandbox that
blocks Maven Central, npm, PyPI, and GitHub outright (every attempt returns HTTP 403
from the outbound proxy — a persistent policy, not a transient outage, reconfirmed
during this v1.5 round). That shaped three things worth knowing about:

1. **The entire main source tree compiles with plain `javac` and zero external
   dependencies.** No file imports `org.h2.*`; every database access goes through
   `java.sql` interfaces only, so H2 is needed at run time (and at test time, since
   Maven puts a `runtime`-scope dependency on the test classpath) but never at
   compile time. Verified directly in this sandbox on every round of changes,
   including this one: `javac -d out $(find src/main/java -name "*.java")` compiles
   cleanly with no classpath at all.

2. **Every test file — all 10, including the two new to v1.5
   (`UiConfigurationLoaderAndValidatorTest` and the new methods in
   `HttpIntegrationTest`) — compiles successfully against the real compiled runtime
   classes plus a set of minimal, locally-authored stand-ins for the JUnit 5
   annotations and `Assertions`/`assertThrows` methods actually used** (created
   solely to type-check the test source in this sandbox; discarded immediately after
   and never part of this delivery).

3. **Going further than a type-check where the sandbox allows it:** the stand-in
   `Assertions` class implements real assertion logic (not no-ops), so any test that
   needs no database could actually be *executed*, not just compiled — and every one
   was. That covers `JsonParserTest`, `ConfigurationLoaderTest`, `ValueCodecTest`,
   `FingerprintCalculatorTest`, `RuleSetLoaderAndValidatorTest`,
   `UiConfigurationLoaderAndValidatorTest`, and `SourceScanTest`: 31 individual test
   methods, all genuinely passing (two `ConfigurationLoaderTest` methods that take a
   JUnit-injected `@TempDir` parameter couldn't run under this ad hoc runner and were
   skipped, not failed). The three tests this sandbox truly cannot run —
   `SchemaProvisionerTest`, `AcceptanceExampleRunnerTest`, and `HttpIntegrationTest`
   — are exactly the ones that need a real H2 driver, which does not exist anywhere
   in this filesystem and cannot be downloaded here.

   For those three, the same approach as the v1.4 round applies: careful hand-tracing
   in place of execution. The redirect-target logic (`STAY_ON_LIST` vs `GO_TO_VIEW`,
   for both create and update), the query-string flash-banner mechanism, the
   `standaloneViewEditRoutes: REMOVE` route-gating, and the `perTable` override's
   effect on `RouteDispatcher`/`HtmlRenderer` were each traced by hand against the
   new HTTP integration test cases line by line. The rule engine's cascade logic
   itself is unchanged from v1.4's independently-verified behavior (see git history /
   prior delivery notes on the `synthetic` `MutationEvent` fix). The schema
   fingerprint was re-verified byte-for-byte against the real sample file using both
   a standalone Python re-implementation (design review) and the real Java
   `FingerprintCalculator` (this sandbox, this round) — the value the new rule and UI
   files both declare as `expectedSchemaFingerprint` matches exactly.

   None of this substitutes for actually running `mvn clean verify` on a machine
   with normal internet access — please do that before relying on this beyond a
   read-through. It is what the `deliveryGate` requires, and it is the one step
   this sandbox could not perform.
