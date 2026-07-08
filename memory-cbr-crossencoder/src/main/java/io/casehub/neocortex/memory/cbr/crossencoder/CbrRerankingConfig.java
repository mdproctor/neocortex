package io.casehub.neocortex.memory.cbr.crossencoder;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.cbr.reranking")
public interface CbrRerankingConfig {
    @WithDefault("false")
    boolean enabled();

    @WithDefault("30")
    int rerankPoolSize();
}
