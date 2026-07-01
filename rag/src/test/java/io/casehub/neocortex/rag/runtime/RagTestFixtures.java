package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.inference.EmbeddingMode;
import io.casehub.neocortex.inference.MultiModalEmbedder;
import io.casehub.neocortex.inference.MultiModalEmbedding;
import io.casehub.platform.api.identity.CurrentPrincipal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

final class RagTestFixtures {

    private RagTestFixtures() {}

    /** Default stub config with sensible test defaults (bm25 disabled, no quantization). */
    static RagConfig stubConfig() {
        return stubConfig("dense", "sparse", "bm25", "colbert",
            TenancyStrategy.SEPARATE_COLLECTIONS,
            DenseQuantization.NONE, true, OptionalDouble.empty(),
            OptionalInt.empty(), Integer.MAX_VALUE,
            64, 64, 40, 60,
            false, 10, false);
    }

    static RagConfig stubConfig(String denseVectorName, String sparseVectorName,
            String bm25VectorName,
            TenancyStrategy tenancy, DenseQuantization quant, boolean alwaysRam,
            OptionalDouble oversampling, OptionalInt matryoshkaDim,
            int batchSize, int denseTopK, int sparseTopK, int bm25TopK, int rrfK,
            boolean rerankEnabled, int rerankTopN,
            boolean bm25Enabled) {
        return stubConfig(denseVectorName, sparseVectorName, bm25VectorName, "colbert",
            tenancy, quant, alwaysRam, oversampling, matryoshkaDim,
            batchSize, denseTopK, sparseTopK, bm25TopK, rrfK,
            rerankEnabled, rerankTopN, bm25Enabled);
    }

    static RagConfig stubConfig(String denseVectorName, String sparseVectorName,
            String bm25VectorName, String colbertVectorName,
            TenancyStrategy tenancy, DenseQuantization quant, boolean alwaysRam,
            OptionalDouble oversampling, OptionalInt matryoshkaDim,
            int batchSize, int denseTopK, int sparseTopK, int bm25TopK, int rrfK,
            boolean rerankEnabled, int rerankTopN,
            boolean bm25Enabled) {
        return new RagConfig() {
            @Override public QdrantConfig qdrant() {
                return new QdrantConfig() {
                    @Override public String host() { return "localhost"; }
                    @Override public int port() { return 6334; }
                    @Override public Optional<String> apiKey() { return Optional.empty(); }
                    @Override public boolean useTls() { return false; }
                };
            }
            @Override public TenancyStrategy tenancyStrategy() { return tenancy; }
            @Override public String denseVectorName() { return denseVectorName; }
            @Override public String sparseVectorName() { return sparseVectorName; }
            @Override public boolean bm25Enabled() { return bm25Enabled; }
            @Override public String bm25VectorName() { return bm25VectorName; }
            @Override public String colbertVectorName() { return colbertVectorName; }
            @Override public RetrievalConfig retrieval() {
                return new RetrievalConfig() {
                    @Override public int denseTopK() { return denseTopK; }
                    @Override public int sparseTopK() { return sparseTopK; }
                    @Override public int bm25TopK() { return bm25TopK; }
                    @Override public int rrfK() { return rrfK; }
                    @Override public boolean rerankEnabled() { return rerankEnabled; }
                    @Override public int rerankTopN() { return rerankTopN; }
                };
            }
            @Override public int embeddingBatchSize() { return batchSize; }
            @Override public MatryoshkaConfig matryoshka() {
                return new MatryoshkaConfig() {
                    @Override public OptionalInt dimension() { return matryoshkaDim; }
                };
            }
            @Override public QuantizationConfig quantization() {
                return new QuantizationConfig() {
                    @Override public DenseQuantization type() { return quant; }
                    @Override public boolean alwaysRam() { return alwaysRam; }
                    @Override public OptionalDouble oversampling() { return oversampling; }
                };
            }
        };
    }

    static CurrentPrincipal stubPrincipal(String tenantId) {
        return new CurrentPrincipal() {
            @Override public String actorId() { return "test-actor"; }
            @Override public Set<String> groups() { return Set.of(); }
            @Override public String tenancyId() { return tenantId; }
            @Override public boolean isCrossTenantAdmin() { return false; }
        };
    }

    /** Dense-only stub embedder. */
    static StubMultiModalEmbedder stubEmbedder(int dim) {
        return new StubMultiModalEmbedder(dim, false);
    }

    /** Dense + sparse stub embedder. */
    static StubMultiModalEmbedder stubEmbedder(int dim, boolean sparse) {
        return new StubMultiModalEmbedder(dim, sparse);
    }

    static final class StubMultiModalEmbedder implements MultiModalEmbedder {

        private final int dim;
        private final boolean sparse;

        StubMultiModalEmbedder(int dim, boolean sparse) {
            this.dim = dim;
            this.sparse = sparse;
        }

        @Override
        public MultiModalEmbedding embed(String text) {
            return makeEmbedding();
        }

        @Override
        public List<MultiModalEmbedding> embedBatch(List<String> texts) {
            List<MultiModalEmbedding> result = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                result.add(makeEmbedding());
            }
            return result;
        }

        @Override
        public Set<EmbeddingMode> supportedModes() {
            if (sparse) {
                return EnumSet.of(EmbeddingMode.DENSE, EmbeddingMode.SPARSE);
            }
            return EnumSet.of(EmbeddingMode.DENSE);
        }

        @Override
        public int denseDimension() {
            return dim;
        }

        @Override
        public OptionalInt colbertDimension() {
            return OptionalInt.empty();
        }

        private MultiModalEmbedding makeEmbedding() {
            float[] vec = new float[dim];
            for (int i = 0; i < dim; i++) vec[i] = 0.1f;
            Map<Integer, Float> sparseMap = sparse
                ? Map.of(0, 0.5f, 2, 0.3f, 4, 0.8f, 7, 0.2f)
                : null;
            return new MultiModalEmbedding(vec, sparseMap, null);
        }
    }
}
