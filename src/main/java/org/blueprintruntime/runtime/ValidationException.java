package org.blueprintruntime.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/** One or more field-level validation failures, carried together so a form can re-render with every message at once. */
public final class ValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public ValidationException(Map<String, String> fieldErrors) {
        super("Validation failed: " + fieldErrors);
        this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
