package org.blueprintruntime.config;

import java.util.List;

/**
 * Raised when an external configuration or rule file is missing, malformed, or
 * semantically invalid. Carries every problem found (not just the first) so the
 * runtime can "display a useful validation report" per the failure policy, instead
 * of provisioning a partial schema or starting the business UI.
 */
public class ConfigurationException extends RuntimeException {
    private final List<String> problems;

    public ConfigurationException(String summary, List<String> problems) {
        super(summary + (problems.isEmpty() ? "" : ":\n - " + String.join("\n - ", problems)));
        this.problems = List.copyOf(problems);
    }

    public ConfigurationException(String message) {
        this(message, List.of());
    }

    public List<String> problems() {
        return problems;
    }
}
