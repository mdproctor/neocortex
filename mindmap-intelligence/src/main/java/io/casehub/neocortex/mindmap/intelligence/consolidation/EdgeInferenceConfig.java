package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.mindmap.consolidation.edge-inference")
public interface EdgeInferenceConfig {
    @WithDefault("1")
    int degreeThreshold();

    @WithDefault("30")
    int maxCandidates();
}
