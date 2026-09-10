package org.blueprintruntime.blueprint;

import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.schema.Identifiers;

/**
 * The active, fully resolved business: its configuration plus the structural
 * fingerprint computed from it. Everything downstream (schema provisioning, CRUD,
 * routing, rule binding, introspection) reads from this rather than re-deriving
 * identity or re-hashing the configuration on every request.
 */
public record ResolvedBusinessBlueprint(BusinessConfiguration configuration, String schemaFingerprint) {

    public String applicationId() {
        return configuration.configurationId();
    }

    public String businessId() {
        return configuration.businessId();
    }

    public String schemaVersion() {
        return configuration.schemaVersion();
    }

    /** {@code APP_<sanitized uppercase businessId>} per databaseNaming.businessSchema. */
    public String databaseSchemaName() {
        return Identifiers.schemaName(configuration.businessId());
    }
}
