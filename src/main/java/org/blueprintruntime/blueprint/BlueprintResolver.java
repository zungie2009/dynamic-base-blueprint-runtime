package org.blueprintruntime.blueprint;

import org.blueprintruntime.config.BusinessConfiguration;
import org.blueprintruntime.schema.FingerprintCalculator;

/**
 * Resolves a loaded {@link BusinessConfiguration} into a {@link ResolvedBusinessBlueprint}
 * by computing its structural fingerprint. This is the one place the runtime turns
 * "a configuration" into "the active business" — every other service depends on the
 * result, never on the raw configuration file again.
 */
public final class BlueprintResolver {

    private BlueprintResolver() {
    }

    public static ResolvedBusinessBlueprint resolve(BusinessConfiguration configuration) {
        String fingerprint = FingerprintCalculator.compute(configuration);
        return new ResolvedBusinessBlueprint(configuration, fingerprint);
    }
}
