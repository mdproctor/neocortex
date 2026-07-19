package io.casehub.neocortex.memory.cbr.qdrant;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.TextualCbrCase;
import io.casehub.neocortex.memory.cbr.testing.CbrCaseMemoryStoreContractTest;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static io.casehub.neocortex.memory.cbr.FeatureValue.string;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class QdrantCbrCaseMemoryStoreTest extends CbrCaseMemoryStoreContractTest {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:v1.18.0")
        .withExposedPorts(6334);

    /** Counter for unique collection prefixes per test to avoid cross-test pollution. */
    private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

    private ReactiveQdrantCbrCaseMemoryStore reactiveStore;

    @Override
    protected CbrCaseMemoryStore store() {
        if (reactiveStore == null) {
            reactiveStore = createReactiveStore(null, false);
        }
        return new BlockingQdrantWrapper(reactiveStore);
    }

    private ReactiveQdrantCbrCaseMemoryStore createReactiveStore(EmbeddingModel embeddingModel, boolean allowMigration) {
        int testId = TEST_COUNTER.incrementAndGet();
        QdrantCbrConfig config = testConfig(testId, allowMigration);
        QdrantClient client = new QdrantClient(
            QdrantGrpcClient.newBuilder(qdrant.getHost(), qdrant.getMappedPort(6334), false).build());
        var collectionManager = new CbrCollectionManager(client, config);
        return new ReactiveQdrantCbrCaseMemoryStore(collectionManager, embeddingModel, config, null, null);
    }

    private QdrantCbrConfig testConfig(int testId, boolean allowMigration) {
        return new QdrantCbrConfig() {
            @Override public String host() { return qdrant.getHost(); }
            @Override public int port() { return qdrant.getMappedPort(6334); }
            @Override public Optional<String> apiKey() { return Optional.empty(); }
            @Override public boolean useTls() { return false; }
            @Override public String collectionPrefix() { return "cbr_test_" + testId; }
            @Override public String denseVectorName() { return "dense"; }
            @Override public int maxRetries() { return 3; }
            @Override public boolean allowDimensionMigration() { return allowMigration; }
            @Override public int oversampleFactor() { return 3; }
            @Override public int overFetchLimit() { return 200; }
        };
    }

    @Test
    void ensureCollection_throwsOnDimensionMismatch_whenMigrationDisabled() {
        // Create store with dim=1 (no embedding model), store a case to create collection
        // Both stores must share the same collection prefix to trigger dimension mismatch
        int sharedTestId = TEST_COUNTER.incrementAndGet();
        var config1 = testConfig(sharedTestId, false);
        QdrantClient client = new QdrantClient(
            QdrantGrpcClient.newBuilder(qdrant.getHost(), qdrant.getMappedPort(6334), false).build());
        var collectionManager1 = new CbrCollectionManager(client, config1);
        var store1 = new BlockingQdrantWrapper(new ReactiveQdrantCbrCaseMemoryStore(collectionManager1,
            (dev.langchain4j.model.embedding.EmbeddingModel) null, config1,
            (io.casehub.neocortex.memory.CaseMemoryStore) null, null));

        store1.registerSchema(CbrFeatureSchema.of("dim-test",
            FeatureField.categorical("cat")));
        store1.store(new TextualCbrCase("p", "s", null, null),
            "dim-test", ENTITY, CBR, TENANT, "case-1", io.casehub.platform.api.path.Path.root());

        // Create a second store with a mock embedding model that reports dim=4
        // Use same collection prefix to trigger dimension mismatch
        var config2 = testConfig(sharedTestId, false);
        var collectionManager2 = new CbrCollectionManager(client, config2);
        var store2 = new BlockingQdrantWrapper(new ReactiveQdrantCbrCaseMemoryStore(collectionManager2, new StubEmbeddingModel(4), config2, null, null));

        assertThatThrownBy(() ->
            store2.store(new TextualCbrCase("p2", "s2", null, null),
                "dim-test", ENTITY, CBR, TENANT, "case-2", io.casehub.platform.api.path.Path.root()))
            .isInstanceOf(CbrDimensionMismatchException.class);
    }

    @Test
    void ensureCollection_recreatesCollection_whenMigrationEnabled() {
        // Both stores must share the same collection prefix
        int sharedTestId = TEST_COUNTER.incrementAndGet();
        var config1 = testConfig(sharedTestId, false);
        QdrantClient client = new QdrantClient(
            QdrantGrpcClient.newBuilder(qdrant.getHost(), qdrant.getMappedPort(6334), false).build());
        var collectionManager1 = new CbrCollectionManager(client, config1);
        var store1 = new BlockingQdrantWrapper(new ReactiveQdrantCbrCaseMemoryStore(collectionManager1,
            (dev.langchain4j.model.embedding.EmbeddingModel) null, config1,
            (io.casehub.neocortex.memory.CaseMemoryStore) null, null));

        store1.registerSchema(CbrFeatureSchema.of("dim-migrate",
            FeatureField.categorical("cat")));
        store1.store(new TextualCbrCase("p", "s", null, null),
            "dim-migrate", ENTITY, CBR, TENANT, "case-1", io.casehub.platform.api.path.Path.root());

        // Enabling migration allows recreation
        var config2 = testConfig(sharedTestId, true);
        var collectionManager2 = new CbrCollectionManager(client, config2);
        var store2 = new BlockingQdrantWrapper(new ReactiveQdrantCbrCaseMemoryStore(collectionManager2, new StubEmbeddingModel(4), config2, null, null));

        store2.registerSchema(CbrFeatureSchema.of("dim-migrate",
            FeatureField.categorical("cat")));
        assertThatCode(() ->
            store2.store(new TextualCbrCase("p2", "s2", null, null),
                "dim-migrate", ENTITY, CBR, TENANT, "case-2", io.casehub.platform.api.path.Path.root()))
            .doesNotThrowAnyException();
    }

    @Test
    void retrieveSimilar_withProblem_noEmbeddingModel_logsInfo() {
        var handler = new java.util.logging.Handler() {
            final java.util.List<java.util.logging.LogRecord> records =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            @Override public void publish(java.util.logging.LogRecord r) { records.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        var logger = java.util.logging.Logger.getLogger(
            "io.casehub.neocortex.memory.cbr.qdrant.ReactiveQdrantCbrCaseMemoryStore");
        logger.addHandler(handler);
        try {
            var s = store(); // uses null embeddingModel
            s.registerSchema(CbrFeatureSchema.of("log-test",
                FeatureField.categorical("cat")));
            s.store(new FeatureVectorCbrCase("problem", "solution", null, null,
                Map.of("cat", string("A"))), "log-test", ENTITY, CBR, TENANT, "case-log", io.casehub.platform.api.path.Path.root());

            s.retrieveSimilar(CbrQuery.of(TENANT, CBR, io.casehub.platform.api.path.Path.root(), "log-test",
                Map.of("cat", string("A")), 5).withProblem("query text"), FeatureVectorCbrCase.class);

            assertThat(handler.records).anyMatch(r ->
                r.getLevel() == java.util.logging.Level.WARNING
                && r.getMessage().contains("HYBRID degraded to FEATURE_ONLY"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void retrieveSimilar_semanticTextSimilarityRanking() {
        // Create store with embedding model that produces semantically different vectors
        var embeddingModel = new SemanticStubEmbeddingModel(4);
        var semanticStore = new BlockingQdrantWrapper(createReactiveStore(embeddingModel, false));

        // Register schema with semantic text field
        semanticStore.registerSchema(CbrFeatureSchema.of("semantic-text-test",
            FeatureField.categorical("category"),
            FeatureField.semanticText("notes")));

        // Store cases with different semantic content
        semanticStore.store(new FeatureVectorCbrCase(
            "marine rush problem",
            "build bunkers early",
            null, null,
            Map.of("category", string("defense"), "notes", string("early game marine rush attack"))),
            "semantic-text-test", ENTITY, CBR, TENANT, "case-1", io.casehub.platform.api.path.Path.root());

        semanticStore.store(new FeatureVectorCbrCase(
            "late game problem",
            "expand to third base",
            null, null,
            Map.of("category", string("economy"), "notes", string("late game economy management and expansion"))),
            "semantic-text-test", ENTITY, CBR, TENANT, "case-2", io.casehub.platform.api.path.Path.root());

        semanticStore.store(new FeatureVectorCbrCase(
            "early aggression problem",
            "scout and prepare defenses",
            null, null,
            Map.of("category", string("defense"), "notes", string("defending against early game aggression"))),
            "semantic-text-test", ENTITY, CBR, TENANT, "case-3", io.casehub.platform.api.path.Path.root());

        // Query with text semantically similar to case-1 and case-3 (early game defense)
        var query = CbrQuery.of(TENANT, CBR, io.casehub.platform.api.path.Path.root(), "semantic-text-test",
            Map.of("category", string("defense"), "notes", string("how to stop early marine attacks")), 3);

        var results = semanticStore.retrieveSimilar(query, FeatureVectorCbrCase.class);

        // Should retrieve all defense cases, but case-1 and case-3 should rank higher than case-2
        // due to semantic similarity in the "notes" field
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);

        // Case-1 or case-3 should be first (both are semantically similar to the query)
        var topCase = results.get(0);
        assertThat(topCase.cbrCase().problem())
            .isIn("marine rush problem", "early aggression problem");

        // Case-2 (late game economy) should rank lower due to semantic dissimilarity
        if (results.size() == 3) {
            var lastCase = results.get(2);
            assertThat(lastCase.cbrCase().problem()).isEqualTo("late game problem");
        }
    }

    @Test
    void ensureCollection_addsSparseVector_whenSpladeEnabled() throws Exception {
        int sharedTestId = TEST_COUNTER.incrementAndGet();
        QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder(qdrant.getHost(), qdrant.getMappedPort(6334), false).build());

        // Phase 1: Create collection with default config (SPLADE disabled)
        var config1  = testConfig(sharedTestId, false);
        var manager1 = new CbrCollectionManager(client, config1);
        var store1   = new BlockingQdrantWrapper(new ReactiveQdrantCbrCaseMemoryStore(manager1,
            (dev.langchain4j.model.embedding.EmbeddingModel) null, config1,
            (io.casehub.neocortex.memory.CaseMemoryStore) null, null));
        store1.registerSchema(CbrFeatureSchema.of("splade-evolve",
                                                  FeatureField.categorical("cat")));
        store1.store(new TextualCbrCase("p", "s", null, null),
                     "splade-evolve", ENTITY, CBR, TENANT, "case-1", io.casehub.platform.api.path.Path.root());

        // Verify no sparse vectors exist yet
        String collection = config1.collectionPrefix() + "_splade-evolve";
        var    info1      = client.getCollectionInfoAsync(collection).get();
        assertThat(info1.getConfig().getParams().getSparseVectorsConfig().getMapMap()).isEmpty();

        // Phase 2: New manager with SPLADE enabled, same collection prefix
        var config2 = new QdrantCbrConfig() {
            @Override
            public String host()                     {return qdrant.getHost();}

            @Override
            public int port()                        {return qdrant.getMappedPort(6334);}

            @Override
            public Optional<String> apiKey()         {return Optional.empty();}

            @Override
            public boolean useTls()                  {return false;}

            @Override
            public String collectionPrefix()         {return "cbr_test_" + sharedTestId;}

            @Override
            public String denseVectorName()          {return "dense";}

            @Override
            public int maxRetries()                  {return 3;}

            @Override
            public boolean allowDimensionMigration() {return false;}

            @Override
            public int oversampleFactor()            {return 3;}

            @Override
            public int overFetchLimit()              {return 200;}

            @Override
            public boolean spladeEnabled()           {return true;}

            @Override
            public boolean allowSparseVectorMigration() {return true;}
        };
        var manager2 = new CbrCollectionManager(client, config2);
        manager2.ensureCollection("splade-evolve", 1);

        // Verify sparse vector was added
        var info2 = client.getCollectionInfoAsync(collection).get();
        assertThat(info2.getConfig().getParams().getSparseVectorsConfig().getMapMap())
                .containsKey("sparse");
    }

    @Test
    void ensureCollection_addsBm25Vector_whenBm25Enabled() throws Exception {
        int sharedTestId = TEST_COUNTER.incrementAndGet();
        QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder(qdrant.getHost(), qdrant.getMappedPort(6334), false).build());

        // Phase 1: Create collection with default config (BM25 disabled)
        var config1  = testConfig(sharedTestId, false);
        var manager1 = new CbrCollectionManager(client, config1);
        var store1   = new BlockingQdrantWrapper(new ReactiveQdrantCbrCaseMemoryStore(manager1,
            (dev.langchain4j.model.embedding.EmbeddingModel) null, config1,
            (io.casehub.neocortex.memory.CaseMemoryStore) null, null));
        store1.registerSchema(CbrFeatureSchema.of("bm25-evolve",
                                                  FeatureField.categorical("cat")));
        store1.store(new TextualCbrCase("p", "s", null, null),
                     "bm25-evolve", ENTITY, CBR, TENANT, "case-1", io.casehub.platform.api.path.Path.root());

        // Phase 2: New manager with BM25 enabled, same collection prefix
        var config2 = new QdrantCbrConfig() {
            @Override
            public String host()                     {return qdrant.getHost();}

            @Override
            public int port()                        {return qdrant.getMappedPort(6334);}

            @Override
            public Optional<String> apiKey()         {return Optional.empty();}

            @Override
            public boolean useTls()                  {return false;}

            @Override
            public String collectionPrefix()         {return "cbr_test_" + sharedTestId;}

            @Override
            public String denseVectorName()          {return "dense";}

            @Override
            public int maxRetries()                  {return 3;}

            @Override
            public boolean allowDimensionMigration() {return false;}

            @Override
            public int oversampleFactor()            {return 3;}

            @Override
            public int overFetchLimit()              {return 200;}

            @Override
            public boolean bm25Enabled()             {return true;}

            @Override
            public boolean allowSparseVectorMigration() {return true;}
        };
        var manager2 = new CbrCollectionManager(client, config2);
        manager2.ensureCollection("bm25-evolve", 1);

        // Verify BM25 sparse vector was added with IDF modifier
        String collection    = config1.collectionPrefix() + "_bm25-evolve";
        var    info2         = client.getCollectionInfoAsync(collection).get();
        var    sparseVectors = info2.getConfig().getParams().getSparseVectorsConfig().getMapMap();
        assertThat(sparseVectors).containsKey("bm25");
        assertThat(sparseVectors.get("bm25").getModifier())
                .isEqualTo(io.qdrant.client.grpc.Collections.Modifier.Idf);
    }


    private record StubEmbeddingModel(int dim) implements EmbeddingModel {
        @Override public Response<Embedding> embed(String text) {
            float[] vec = new float[dim];
            return new Response<>(new Embedding(vec));
        }
        @Override public Response<Embedding> embed(TextSegment segment) {
            return embed(segment.text());
        }
        @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> embeddings = segments.stream()
                .map(s -> new Embedding(new float[dim]))
                .toList();
            return new Response<>(embeddings);
        }
        @Override public int dimension() { return dim; }
    }

    /**
     * Embedding model that produces different vectors for different texts based on keyword presence.
     * Uses simple heuristics to simulate semantic similarity.
     */
    private record SemanticStubEmbeddingModel(int dim) implements EmbeddingModel {
        @Override
        public Response<Embedding> embed(String text) {
            float[] vec = new float[dim];
            String lower = text.toLowerCase();

            // Dimension 0: early game concepts
            if (lower.contains("early") || lower.contains("marine") || lower.contains("rush")) {
                vec[0] = 1.0f;
            }

            // Dimension 1: defense concepts
            if (lower.contains("defense") || lower.contains("defending") || lower.contains("bunker") ||
                lower.contains("stop") || lower.contains("attack")) {
                vec[1] = 1.0f;
            }

            // Dimension 2: late game / economy concepts
            if (lower.contains("late") || lower.contains("economy") || lower.contains("expansion") ||
                lower.contains("expand") || lower.contains("base")) {
                vec[2] = 1.0f;
            }

            // Dimension 3: management / strategic concepts
            if (lower.contains("management") || lower.contains("third")) {
                vec[3] = 1.0f;
            }

            // Normalize to unit vector for cosine similarity
            float norm = 0.0f;
            for (float v : vec) {
                norm += v * v;
            }
            if (norm > 0) {
                norm = (float) Math.sqrt(norm);
                for (int i = 0; i < vec.length; i++) {
                    vec[i] /= norm;
                }
            }

            return new Response<>(new Embedding(vec));
        }

        @Override
        public Response<Embedding> embed(TextSegment segment) {
            return embed(segment.text());
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> embeddings = segments.stream()
                .map(s -> embed(s.text()).content())
                .toList();
            return new Response<>(embeddings);
        }

        @Override
        public int dimension() {
            return dim;
        }
    }

    private static class BlockingQdrantWrapper implements CbrCaseMemoryStore {
        private final ReactiveQdrantCbrCaseMemoryStore delegate;

        BlockingQdrantWrapper(ReactiveQdrantCbrCaseMemoryStore delegate)                                                                  {this.delegate = delegate;}

        @Override
        public void registerSchema(CbrFeatureSchema schema)                                                                               {delegate.registerSchema(schema).await().indefinitely();}

        @Override
        public String store(CbrCase c, String ct, String e, MemoryDomain d, String t, String ci, io.casehub.platform.api.path.Path scope) {return delegate.store(c, ct, e, d, t, ci, scope).await().indefinitely();}

        @Override
        public <C extends CbrCase> java.util.List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery q, Class<C> ct)                              {return delegate.retrieveSimilar(q, ct).await().indefinitely();}

        @Override
        public Integer erase(EraseRequest r)                                                                                              {return delegate.erase(r).await().indefinitely();}

        @Override
        public Integer eraseEntity(String e, String t)                                                                                    {return delegate.eraseEntity(e, t).await().indefinitely();}

        @Override
        public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String t)                                                    {return delegate.eraseByScope(scope, t).await().indefinitely();}

        @Override
        public void recordOutcome(String ci, String t, CbrOutcome o)                                                                      {delegate.recordOutcome(ci, t, o).await().indefinitely();}

        @Override
        public Integer purge(CbrRetentionPolicy p)                                                                                        {return delegate.purge(p).await().indefinitely();}

        @Override
        public void supersede(String ci, String t, String sci, String r)                                                                  {delegate.supersede(ci, t, sci, r).await().indefinitely();}

        @Override
        public void reinstate(String ci, String t)                                                                                        {delegate.reinstate(ci, t).await().indefinitely();}
        public io.casehub.neocortex.memory.cbr.SupersessionStatus getSupersessionStatus(String caseId, String tenantId) { return delegate.getSupersessionStatus(caseId, tenantId).await().indefinitely(); }
        public java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) { return delegate.findSupersededCases(tenantId, domain).await().indefinitely(); }

    }
}
