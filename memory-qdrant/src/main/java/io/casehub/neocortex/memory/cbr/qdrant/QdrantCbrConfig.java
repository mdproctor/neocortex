package io.casehub.neocortex.memory.cbr.qdrant;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "casehub.memory.cbr.qdrant")
public interface QdrantCbrConfig {

    @WithDefault("localhost")
    String host();

    @WithDefault("6334")
    int port();

    Optional<String> apiKey();

    @WithDefault("false")
    boolean useTls();

    @WithDefault("cbr")
    String collectionPrefix();

    @WithDefault("dense")
    String denseVectorName();

    @WithDefault("3")
    int maxRetries();

    @WithDefault("false")
    boolean allowDimensionMigration();
}
