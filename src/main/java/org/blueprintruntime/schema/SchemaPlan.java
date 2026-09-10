package org.blueprintruntime.schema;

import java.util.List;

/**
 * The canonical desired DDL for one business, built by {@link SchemaPlanner} and
 * executed by {@link SchemaProvisioner}. Foreign keys are always added in a second
 * pass, after every table exists — this is what lets the dependency rule ("create
 * referenced parents before children; for cycles, create tables first and add foreign
 * keys afterward") hold uniformly for both acyclic and cyclic relationship graphs
 * without needing two different code paths.
 */
public record SchemaPlan(
        String schemaName,
        String createSchemaStatement,
        List<String> createTableStatements,
        List<String> addForeignKeyStatements
) {
}
