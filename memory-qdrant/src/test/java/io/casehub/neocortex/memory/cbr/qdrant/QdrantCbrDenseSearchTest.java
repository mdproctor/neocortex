package io.casehub.neocortex.memory.cbr.qdrant;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.*;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class QdrantCbrDenseSearchTest {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:v1.18.0")
        .withExposedPorts(6334);

    private static final MemoryDomain CBR = new MemoryDomain("cbr");
    private static final String TENANT = "test-tenant";
    private static final String ENTITY = "test-entity";
    private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
    private static final int DIM = 4;

    private QdrantCbrCaseMemoryStore store;
    private final DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();

    @BeforeEach
    void setUp() {
        QdrantClient client = new QdrantClient(
            QdrantGrpcClient.newBuilder(qdrant.getHost(), qdrant.getMappedPort(6334), false).build());

        int testId = TEST_COUNTER.incrementAndGet();
        QdrantCbrConfig config = new QdrantCbrConfig() {
            @Override public String host() { return qdrant.getHost(); }
            @Override public int port() { return qdrant.getMappedPort(6334); }
            @Override public Optional<String> apiKey() { return Optional.empty(); }
            @Override public boolean useTls() { return false; }
            @Override public String collectionPrefix() { return "dense_test_" + testId; }
            @Override public String denseVectorName() { return "dense"; }
            @Override public int maxRetries() { return 3; }
            @Override public boolean allowDimensionMigration() { return false; }
        };

        CbrCollectionManager collectionManager = new CbrCollectionManager(client, config);
        store = new QdrantCbrCaseMemoryStore(collectionManager, embeddingModel, config, null);

        store.registerSchema(CbrFeatureSchema.of("starcraft-game",
            FeatureField.categorical("opponent_race"),
            FeatureField.numeric("army_size_ratio", 0.0, 3.0)));
    }

    @Test
    void denseSearch_ranksResultsBySimilarity() {
        // "alpha" embeds to [1,0,0,0], "alpha-ish" to [0.9,0.436,0,0], "beta" to [0,1,0,0]
        store.store(new TextualCbrCase("alpha", "solution-a", null, null),
            "starcraft-game", ENTITY, CBR, TENANT, "case-alpha");
        store.store(new TextualCbrCase("beta", "solution-b", null, null),
            "starcraft-game", ENTITY, CBR, TENANT, "case-beta");
        store.store(new TextualCbrCase("alpha-ish", "solution-c", null, null),
            "starcraft-game", ENTITY, CBR, TENANT, "case-alpha-ish");

        var query = CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of(), 10)
            .withProblem("alpha");

        var results = store.retrieveSimilar(query, CbrCase.class);

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        // "alpha" should rank first (exact match), then "alpha-ish" (high similarity)
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("alpha");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void denseSearch_minSimilarity_filtersLowScoreResults() {
        store.store(new TextualCbrCase("alpha", "solution-a", null, null),
            "starcraft-game", ENTITY, CBR, TENANT, "case-filter-alpha");
        store.store(new TextualCbrCase("beta", "solution-b", null, null),
            "starcraft-game", ENTITY, CBR, TENANT, "case-filter-beta");

        // Query with high threshold — "beta" should be excluded (orthogonal to "alpha")
        var query = CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of(), 10)
            .withProblem("alpha")
            .withMinSimilarity(0.5);

        var results = store.retrieveSimilar(query, CbrCase.class);

        // "alpha" should pass (cos=1.0), "beta" should be filtered (cos≈0.0)
        assertThat(results).allSatisfy(r -> assertThat(r.score()).isGreaterThanOrEqualTo(0.5));
        assertThat(results.stream().map(r -> r.cbrCase().problem()))
            .contains("alpha")
            .doesNotContain("beta");
    }

    @Test
    void denseSearch_fallsBackToFilterOnly_whenProblemNull() {
        store.store(new TextualCbrCase("alpha", "solution-a", null, null),
            "starcraft-game", ENTITY, CBR, TENANT, "case-fallback-alpha");
        store.store(new TextualCbrCase("beta", "solution-b", null, null),
            "starcraft-game", ENTITY, CBR, TENANT, "case-fallback-beta");

        // problem=null → filter-only mode, all results score 1.0
        var query = CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of(), 10);

        var results = store.retrieveSimilar(query, CbrCase.class);

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(r -> assertThat(r.score()).isEqualTo(1.0f));
    }

    @Test
    void denseSearch_withPayloadFilters_combinesVectorAndFilter() {
        store.store(new FeatureVectorCbrCase("alpha", "sol-a", null, null,
                Map.of("opponent_race", "Zerg")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-combo-zerg");
        store.store(new FeatureVectorCbrCase("alpha", "sol-b", null, null,
                Map.of("opponent_race", "Protoss")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-combo-protoss");

        // Dense search for "alpha" + filter for Zerg only
        var query = CbrQuery.of(TENANT, CBR, "starcraft-game",
                Map.of("opponent_race", "Zerg"), 10)
            .withProblem("alpha");

        var results = store.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().features().get("opponent_race")).isEqualTo("Zerg");
    }

    /**
     * Deterministic embedding model for testing. Maps known text strings to
     * fixed unit vectors so cosine similarity is predictable.
     */
    static class DeterministicEmbeddingModel implements EmbeddingModel {
        private static final Map<String, float[]> KNOWN_VECTORS = Map.of(
            "alpha", new float[]{1.0f, 0.0f, 0.0f, 0.0f},
            "alpha-ish", new float[]{0.9f, 0.436f, 0.0f, 0.0f},  // cos(alpha, alpha-ish) ≈ 0.9
            "beta", new float[]{0.0f, 1.0f, 0.0f, 0.0f},
            "gamma", new float[]{0.0f, 0.0f, 1.0f, 0.0f}
        );

        @Override
        public Response<Embedding> embed(String text) {
            return Response.from(Embedding.from(vectorFor(text)));
        }

        @Override
        public Response<Embedding> embed(TextSegment segment) {
            return embed(segment.text());
        }

        @Override
        public Response<java.util.List<Embedding>> embedAll(java.util.List<TextSegment> segments) {
            java.util.List<Embedding> embeddings = segments.stream()
                .map(segment -> Embedding.from(vectorFor(segment.text())))
                .toList();
            return Response.from(embeddings);
        }

        @Override
        public int dimension() { return DIM; }

        private float[] vectorFor(String text) {
            float[] known = KNOWN_VECTORS.get(text);
            if (known != null) return known;
            // Fallback: hash-based deterministic vector, normalized
            int hash = text.hashCode();
            float[] v = new float[DIM];
            for (int i = 0; i < DIM; i++) {
                v[i] = ((hash >> (i * 8)) & 0xFF) / 255.0f;
            }
            float norm = 0;
            for (float f : v) norm += f * f;
            norm = (float) Math.sqrt(norm);
            if (norm > 0) for (int i = 0; i < DIM; i++) v[i] /= norm;
            return v;
        }
    }
}
