package org.blueprintruntime.runtime;

/**
 * Thrown when the database's {@code ON DELETE RESTRICT} foreign-key policy rejects a
 * delete, per {@code genericCrudContract.delete}: "translate violations into a
 * readable user message" rather than surfacing a raw SQL exception.
 */
public final class ForeignKeyRestrictionException extends RuntimeException {
    public ForeignKeyRestrictionException(String tableId, String recordId) {
        super("Cannot delete this record from '" + tableId + "' (id " + recordId
                + ") because other records still reference it.");
    }
}
