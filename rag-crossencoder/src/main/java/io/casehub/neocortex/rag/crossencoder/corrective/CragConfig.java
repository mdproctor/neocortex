package io.casehub.neocortex.rag.crossencoder.corrective;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.rag.crag")
public interface CragConfig {

    @WithDefault("0.7")
    double correctThreshold();

    @WithDefault("0.3")
    double incorrectThreshold();

    @WithDefault("3")
    int expansionMultiplier();

    @WithDefault("false")
    boolean enabled();
}
