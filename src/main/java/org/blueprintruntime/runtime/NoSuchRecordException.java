package org.blueprintruntime.runtime;

/** Thrown when a view/update/delete addresses a record id that does not exist in the given table. */
public final class NoSuchRecordException extends RuntimeException {
    public NoSuchRecordException(String tableId, String recordId) {
        super("No record '" + recordId + "' in table '" + tableId + "'");
    }
}
