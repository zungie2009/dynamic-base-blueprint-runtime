package org.blueprintruntime.schema;

/**
 * Raised when a business schema is already installed under a different structural
 * fingerprint than the one the active configuration now resolves to. Per the
 * destructive-migration rule, the runtime never drops, renames, or retypes anything
 * automatically — it rejects activation and reports that an explicit migration is
 * required, leaving the previously installed schema untouched.
 */
public class SchemaMigrationRequiredException extends RuntimeException {
    public SchemaMigrationRequiredException(String businessId, String installedFingerprint, String requestedFingerprint) {
        super("Business '" + businessId + "' already has an installed schema with fingerprint "
                + installedFingerprint + ", but the active configuration resolves to a different fingerprint "
                + requestedFingerprint + ". An explicit migration is required; no schema changes were made.");
    }
}
