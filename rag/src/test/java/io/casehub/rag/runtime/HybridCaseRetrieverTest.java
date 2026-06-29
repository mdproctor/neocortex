package io.casehub.rag.runtime;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.inference.inmem.InMemoryInferenceModel;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
import io.casehub.rag.RelevanceGrade;
import io.casehub.rag.RetrievalQuery;
import io.casehub.rag.RetrievedChunk;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class HybridCaseRetrieverTest {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:v1.18.0")
        .withExposedPorts(6334);

    private static final int DENSE_DIM = 4;
    private static final String TENANT = "tenant-1";
    private static final String DENSE_VECTOR_NAME = "dense";
    private static final String SPARSE_VECTOR_NAME = "sparse";

    private static final AtomicInteger corpusCounter = new AtomicInteger();

    private QdrantClient            client;
    private QdrantEmbeddingIngestor store;
    private HybridCaseRetriever     retriever;

    @BeforeEach
    void setUp() {
        client = new QdrantClient(
            QdrantGrpcClient.newBuilder(
                qdrant.getHost(),
                qdrant.getMappedPort(6334),
                false
            ).build()
        );

        EmbeddingModel embeddingModel = new RagTestFixtures.StubEmbeddingModel(DENSE_DIM);

        // SPLADE stub: 8-element output, some above threshold (0.01), some not.
        InMemoryInferenceModel spladeModel = InMemoryInferenceModel.returning(
            0.5f, 0.0f, 0.3f, 0.0f, 0.8f, 0.0f, 0.0f, 0.2f
        );
        SparseEmbedder sparseEmbedder = new SparseEmbedder(spladeModel);

        TenantGuard guard = TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT));

        RagConfig config = RagTestFixtures.stubConfig();

        store = new QdrantEmbeddingIngestor(
            client, embeddingModel, sparseEmbedder,
            guard, config
        );

        retriever = new HybridCaseRetriever(
            client, embeddingModel, sparseEmbedder,
            guard, null, config
        );
    }

    @Test
    void retrieveFromIngestedCorpus() {
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("The quick brown fox jumps over the lazy dog", "doc-1",
                Map.of("category", "animals")),
            new ChunkInput("Machine learning is a subset of artificial intelligence", "doc-2",
                Map.of("category", "tech"))
        ));

        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("brown fox"), corpus, 10, null);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(chunk -> {
            assertThat(chunk.content()).isNotBlank();
            assertThat(chunk.sourceDocumentId()).isNotBlank();
            assertThat(chunk.relevanceScore()).isGreaterThan(0.0);
        });
        // Verify metadata passes through (excluding reserved fields)
        assertThat(results).anyMatch(chunk -> "animals".equals(chunk.metadata().get("category"))
            || "tech".equals(chunk.metadata().get("category")));
        assertThat(results).allSatisfy(chunk ->
            assertThat(chunk.grade()).isEqualTo(RelevanceGrade.UNGRADED));
    }

    @Test
    void retrieveRespectsMaxResults() {
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("chunk one about cats", "doc-1", Map.of()),
            new ChunkInput("chunk two about dogs", "doc-2", Map.of()),
            new ChunkInput("chunk three about birds", "doc-3", Map.of())
        ));

        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("animals"), corpus, 1, null);

        assertThat(results).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void retrieveEmptyCorpus() {
        CorpusRef corpus = uniqueCorpus(); // never ingested — collection does not exist

        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("anything"), corpus, 10, null);

        assertThat(results).isEmpty();
    }

    @Test
    void tenancyMismatchThrows() {
        CorpusRef wrongTenant = new CorpusRef("other-tenant", "corpus");

        assertThatThrownBy(() -> retriever.retrieve(RetrievalQuery.of("query"), wrongTenant, 10, null))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void denseOnlyModeIngestAndRetrieve() {
        // Create ingestor and retriever with null SparseEmbedder — dense-only mode
        EmbeddingModel denseOnlyModel = new RagTestFixtures.StubEmbeddingModel(DENSE_DIM);

        TenantGuard denseOnlyGuard = TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT));

        RagConfig denseOnlyConfig = RagTestFixtures.stubConfig();

        QdrantEmbeddingIngestor denseOnlyStore = new QdrantEmbeddingIngestor(
            client, denseOnlyModel, null, // no sparse embedder
            denseOnlyGuard, denseOnlyConfig
        );

        HybridCaseRetriever denseOnlyRetriever = new HybridCaseRetriever(
            client, denseOnlyModel, null, // no sparse embedder
            denseOnlyGuard, null, denseOnlyConfig
        );

        CorpusRef corpus = uniqueCorpus();
        denseOnlyStore.ingest(corpus, List.of(
            new ChunkInput("Dense-only retrieval works", "doc-1",
                Map.of("category", "test")),
            new ChunkInput("Another chunk for dense search", "doc-2",
                Map.of("category", "test"))
        ));

        List<RetrievedChunk> results = denseOnlyRetriever.retrieve(
            RetrievalQuery.of("dense retrieval"), corpus, 10, null);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(chunk -> {
            assertThat(chunk.content()).isNotBlank();
            assertThat(chunk.sourceDocumentId()).isNotBlank();
            assertThat(chunk.relevanceScore()).isGreaterThan(0.0);
        });
    }

    @Test
    void retrieveWithPayloadFilterNarrowsResults() {
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("Java CDI injection", "doc-1", Map.of("domain", "jvm")),
            new ChunkInput("Python pip install", "doc-2", Map.of("domain", "python")),
            new ChunkInput("Java Spring Boot", "doc-3", Map.of("domain", "jvm"))
        ));

        List<RetrievedChunk> allResults = retriever.retrieve(RetrievalQuery.of("programming"), corpus, 10, null);
        List<RetrievedChunk> jvmOnly = retriever.retrieve(RetrievalQuery.of("programming"), corpus, 10,
            PayloadFilter.eq("domain", "jvm"));

        assertThat(allResults.size()).isGreaterThanOrEqualTo(jvmOnly.size());
        assertThat(jvmOnly).allSatisfy(chunk ->
            assertThat(chunk.metadata().get("domain")).isEqualTo("jvm"));
    }

    @Test
    void retrieveWorksWithoutCurrentPrincipal() {
        TenantGuard noTenantGuard = TenantGuard.of(null);
        EmbeddingModel model = new RagTestFixtures.StubEmbeddingModel(DENSE_DIM);

        RagConfig noTenantConfig = RagTestFixtures.stubConfig();

        QdrantEmbeddingIngestor noTenantStore = new QdrantEmbeddingIngestor(
            client, model, null,
            noTenantGuard, noTenantConfig
        );

        HybridCaseRetriever noTenantRetriever = new HybridCaseRetriever(
            client, model, null,
            noTenantGuard, null, noTenantConfig
        );

        CorpusRef corpus = uniqueCorpus();
        noTenantStore.ingest(corpus, List.of(
            new ChunkInput("searchable content", "doc-1", Map.of())
        ));

        List<RetrievedChunk> results = noTenantRetriever.retrieve(
            RetrievalQuery.of("searchable"), corpus, 10, null);
        assertThat(results).isNotEmpty();
    }

    @Test
    void quantizedRetrieverIngestsAndRetrieves() {
        // Verify end-to-end: quantized ingestor + retriever work together
        EmbeddingModel model = new RagTestFixtures.StubEmbeddingModel(DENSE_DIM);
        TenantGuard guard = TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT));

        RagConfig quantizedConfig = RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.BINARY, true, OptionalDouble.of(2.0), OptionalInt.empty(), Integer.MAX_VALUE, 64, 64, 40, 60, false, 10, false);

        QdrantEmbeddingIngestor quantizedStore = new QdrantEmbeddingIngestor(
            client, model, null,
            guard, quantizedConfig
        );

        HybridCaseRetriever quantizedRetriever = new HybridCaseRetriever(
            client, model, null,
            guard, null, quantizedConfig
        );

        CorpusRef corpus = uniqueCorpus();
        quantizedStore.ingest(corpus, List.of(
            new ChunkInput("quantized search content", "doc-1", Map.of())
        ));

        List<RetrievedChunk> results = quantizedRetriever.retrieve(
            RetrievalQuery.of("quantized"), corpus, 10, null);

        assertThat(results).isNotEmpty();
    }

    @Test
    void bm25OnlyModeIngestAndRetrieve() {
        // Dense + BM25, no SPLADE
        RagConfig bm25Config = RagTestFixtures.stubConfig("dense", "sparse", "bm25",
            TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true,
            OptionalDouble.empty(), OptionalInt.empty(), Integer.MAX_VALUE,
            64, 64, 40, 60, false, 10, true);

        QdrantEmbeddingIngestor bm25Store = new QdrantEmbeddingIngestor(
            client, new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null, TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)), bm25Config);

        HybridCaseRetriever bm25Retriever = new HybridCaseRetriever(
            client, new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null, TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            null, bm25Config);

        CorpusRef corpus = uniqueCorpus();
        bm25Store.ingest(corpus, List.of(
            new ChunkInput("ConcurrentHashMap is thread-safe", "doc-1",
                Map.of("category", "java")),
            new ChunkInput("Python asyncio event loop", "doc-2",
                Map.of("category", "python"))
        ));

        List<RetrievedChunk> results = bm25Retriever.retrieve(
            RetrievalQuery.of("HashMap"), corpus, 10, null);

        assertThat(results).isNotEmpty();
    }

    @Test
    void threeWayRrfIngestAndRetrieve() {
        // Dense + SPLADE + BM25
        InMemoryInferenceModel spladeModel = InMemoryInferenceModel.returning(
            0.5f, 0.0f, 0.3f, 0.0f, 0.8f, 0.0f, 0.0f, 0.2f);
        SparseEmbedder sparseEmbedder = new SparseEmbedder(spladeModel);

        RagConfig threeWayConfig = RagTestFixtures.stubConfig("dense", "sparse", "bm25",
            TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true,
            OptionalDouble.empty(), OptionalInt.empty(), Integer.MAX_VALUE,
            64, 64, 40, 60, false, 10, true);

        QdrantEmbeddingIngestor store3 = new QdrantEmbeddingIngestor(
            client, new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            sparseEmbedder, TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            threeWayConfig);

        HybridCaseRetriever retriever3 = new HybridCaseRetriever(
            client, new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            sparseEmbedder, TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            null, threeWayConfig);

        CorpusRef corpus = uniqueCorpus();
        store3.ingest(corpus, List.of(
            new ChunkInput("ApplicationScoped CDI bean", "doc-1", Map.of()),
            new ChunkInput("Quarkus REST endpoint", "doc-2", Map.of())
        ));

        List<RetrievedChunk> results = retriever3.retrieve(
            RetrievalQuery.of("CDI bean"), corpus, 10, null);

        assertThat(results).isNotEmpty();
    }

    // --- helpers ---

    private CorpusRef uniqueCorpus() {
        return new CorpusRef(TENANT, "retriever-corpus" + corpusCounter.incrementAndGet());
    }
}
