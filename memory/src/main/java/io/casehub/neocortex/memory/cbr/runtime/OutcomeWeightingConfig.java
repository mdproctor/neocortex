package io.casehub.neocortex.memory.cbr.runtime;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.cbr.outcome-weighting")
public interface OutcomeWeightingConfig {
    @WithDefault("0.3")
    double influence();
}
