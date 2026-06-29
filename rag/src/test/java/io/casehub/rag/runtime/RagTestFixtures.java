package io.casehub.rag.runtime;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.casehub.platform.api.identity.CurrentPrincipal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

final class RagTestFixtures {

    private RagTestFixtures() {}

    /** Default stub config with sensible test defaults (bm25 disabled, no quantization). */
    static RagConfig stubConfig() {
        return stubConfig("dense", "sparse", "bm25",
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

    static final class StubEmbeddingModel implements EmbeddingModel {

        private final int dim;

        StubEmbeddingModel(int dim) {
            this.dim = dim;
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> embeddings = new ArrayList<>(segments.size());
            float[] vec = new float[dim];
            for (int i = 0; i < dim; i++) vec[i] = 0.1f;
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(Embedding.from(vec));
            }
            return Response.from(embeddings);
        }

        @Override
        public int dimension() {
            return dim;
        }
    }
}
