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

    @WithDefault("false")
    default boolean allowSparseVectorMigration() { return false; }

    @WithDefault("3")
    int oversampleFactor();

    @WithDefault("200")
    int overFetchLimit();

    @WithDefault("false")
    default boolean spladeEnabled() { return false; }

    @WithDefault("sparse")
    default String spladeVectorName() { return "sparse"; }

    @WithDefault("0")
    default int spladeTopK() { return 0; }

    @WithDefault("false")
    default boolean bm25Enabled() { return false; }

    @WithDefault("bm25")
    default String bm25VectorName() { return "bm25"; }

    @WithDefault("Qdrant/bm25")
    default String bm25Model() { return "Qdrant/bm25"; }

    @WithDefault("0")
    default int bm25TopK() { return 0; }

    default CcWeightsConfig ccWeights() { return new CcWeightsConfig() {}; }

    interface CcWeightsConfig {
        @WithDefault("0.6")
        default double dense() { return 0.6; }

        @WithDefault("0.2")
        default double sparse() { return 0.2; }

        @WithDefault("0.2")
        default double bm25() { return 0.2; }
    }
}
