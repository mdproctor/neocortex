package io.casehub.neocortex.rag.crossencoder.reranking;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.rag.reranking")
public interface RerankingConfig {
    @WithDefault("false")
    boolean enabled();

    @WithDefault("30")
    int rerankPoolSize();
}
