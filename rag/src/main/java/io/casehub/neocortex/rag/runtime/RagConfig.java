package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.fusion.FusionStrategy;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

@ConfigMapping(prefix = "casehub.rag")
public interface RagConfig {

    QdrantConfig qdrant();

    @WithDefault("SEPARATE_COLLECTIONS")
    TenancyStrategy tenancyStrategy();

    @WithDefault("dense")
    String denseVectorName();

    @WithDefault("sparse")
    String sparseVectorName();

    @WithDefault("true")
    boolean bm25Enabled();

    @WithDefault("bm25")
    String bm25VectorName();

    @WithDefault("colbert")
    String colbertVectorName();

    RetrievalConfig retrieval();

    @WithDefault("100")
    int embeddingBatchSize();

    @WithDefault("1000000")
    int maxMultivectorFloats();

    /**
     * Maximum token sequence length for embedders. Used by SeparateModelEmbedder
     * to report maxSequenceLength() contract. Defaults to 512 if not configured.
     */
    Optional<Integer> maxSequenceLength();

    MatryoshkaConfig matryoshka();

    interface MatryoshkaConfig {
        OptionalInt dimension();
    }

    QuantizationConfig quantization();

    interface QuantizationConfig {
        @WithDefault("NONE")
        DenseQuantization type();

        @WithDefault("true")
        boolean alwaysRam();

        OptionalDouble oversampling();
    }

    ColbertQuantizationConfig colbertQuantization();

    interface ColbertQuantizationConfig {
        @WithDefault("NONE")
        DenseQuantization type();

        @WithDefault("true")
        boolean alwaysRam();
    }

    interface QdrantConfig {
        @WithDefault("localhost")
        String host();

        @WithDefault("6334")
        int port();

        Optional<String> apiKey();

        @WithDefault("false")
        boolean useTls();
    }

    interface RetrievalConfig {
        @WithDefault("RRF")
        FusionStrategy fusionStrategy();

        @WithDefault("40")
        int denseTopK();

        @WithDefault("40")
        int sparseTopK();

        @WithDefault("40")
        int bm25TopK();

        @WithDefault("60")
        int rrfK();

        @WithDefault("true")
        boolean rerankEnabled();

        @WithDefault("10")
        int rerankTopN();

        CcWeightsConfig ccWeights();
    }

    interface CcWeightsConfig {
        @WithDefault("0.5")
        double dense();

        @WithDefault("0.3")
        double sparse();

        @WithDefault("0.2")
        double bm25();
    }
}
