package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.inference.MultiModalEmbedder;
import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.RelevanceGrade;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
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

        MultiModalEmbedder embedder = RagTestFixtures.stubEmbedder(DENSE_DIM, true);

        TenantGuard guard = TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT));

        RagConfig config = RagTestFixtures.stubConfig();

        store = new QdrantEmbeddingIngestor(
            client, embedder,
            guard, config
        );

        retriever = new HybridCaseRetriever(
            client, embedder,
            guard, config
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
        // Create ingestor and retriever with dense-only embedder
        MultiModalEmbedder denseOnlyEmbedder = RagTestFixtures.stubEmbedder(DENSE_DIM);

        TenantGuard denseOnlyGuard = TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT));

        RagConfig denseOnlyConfig = RagTestFixtures.stubConfig();

        QdrantEmbeddingIngestor denseOnlyStore = new QdrantEmbeddingIngestor(
            client, denseOnlyEmbedder,
            denseOnlyGuard, denseOnlyConfig
        );

        HybridCaseRetriever denseOnlyRetriever = new HybridCaseRetriever(
            client, denseOnlyEmbedder,
            denseOnlyGuard, denseOnlyConfig
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
        MultiModalEmbedder embedder = RagTestFixtures.stubEmbedder(DENSE_DIM);

        RagConfig noTenantConfig = RagTestFixtures.stubConfig();

        QdrantEmbeddingIngestor noTenantStore = new QdrantEmbeddingIngestor(
            client, embedder,
            noTenantGuard, noTenantConfig
        );

        HybridCaseRetriever noTenantRetriever = new HybridCaseRetriever(
            client, embedder,
            noTenantGuard, noTenantConfig
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
        MultiModalEmbedder embedder = RagTestFixtures.stubEmbedder(DENSE_DIM);
        TenantGuard guard = TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT));

        RagConfig quantizedConfig = RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.BINARY, true, OptionalDouble.of(2.0), OptionalInt.empty(), Integer.MAX_VALUE, 64, 64, 40, 60, false, 10, false);

        QdrantEmbeddingIngestor quantizedStore = new QdrantEmbeddingIngestor(
            client, embedder,
            guard, quantizedConfig
        );

        HybridCaseRetriever quantizedRetriever = new HybridCaseRetriever(
            client, embedder,
            guard, quantizedConfig
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

        MultiModalEmbedder embedder = RagTestFixtures.stubEmbedder(DENSE_DIM);

        QdrantEmbeddingIngestor bm25Store = new QdrantEmbeddingIngestor(
            client, embedder,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)), bm25Config);

        HybridCaseRetriever bm25Retriever = new HybridCaseRetriever(
            client, embedder,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)), bm25Config);

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
        MultiModalEmbedder sparseEmbedder = RagTestFixtures.stubEmbedder(DENSE_DIM, true);

        RagConfig threeWayConfig = RagTestFixtures.stubConfig("dense", "sparse", "bm25",
            TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true,
            OptionalDouble.empty(), OptionalInt.empty(), Integer.MAX_VALUE,
            64, 64, 40, 60, false, 10, true);

        QdrantEmbeddingIngestor store3 = new QdrantEmbeddingIngestor(
            client, sparseEmbedder,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            threeWayConfig);

        HybridCaseRetriever retriever3 = new HybridCaseRetriever(
            client, sparseEmbedder,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            threeWayConfig);

        CorpusRef corpus = uniqueCorpus();
        store3.ingest(corpus, List.of(
            new ChunkInput("ApplicationScoped CDI bean", "doc-1", Map.of()),
            new ChunkInput("Quarkus REST endpoint", "doc-2", Map.of())
        ));

        List<RetrievedChunk> results = retriever3.retrieve(
            RetrievalQuery.of("CDI bean"), corpus, 10, null);

        assertThat(results).isNotEmpty();
    }

    @Test
    void usesEmbedBatchWhenExpansionActive() {
        RagTestFixtures.StubMultiModalEmbedder stub = RagTestFixtures.stubEmbedder(DENSE_DIM, true);

        CorpusRef corpus = uniqueCorpus();
        // Ingest first so collection exists
        var ingestor = new QdrantEmbeddingIngestor(client, stub,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig());
        ingestor.ingest(corpus, List.of(
            new ChunkInput("test content", "doc-1", Map.of())));

        var retriever = new HybridCaseRetriever(client, stub,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig());

        stub.clearCalls();

        // Query WITH expansion active
        var expandedQuery = RetrievalQuery.of("original").withExpansion("hypothetical");
        retriever.retrieve(expandedQuery, corpus, 10, null);

        // Should use embedBatch with [searchText, text]
        assertThat(stub.batchCalls()).hasSize(1);
        assertThat(stub.batchCalls().get(0)).containsExactly("hypothetical", "original");
        assertThat(stub.embedCalls()).isEmpty();
    }

    @Test
    void usesSingleEmbedWhenNoExpansion() {
        RagTestFixtures.StubMultiModalEmbedder stub = RagTestFixtures.stubEmbedder(DENSE_DIM, true);

        CorpusRef corpus = uniqueCorpus();
        var ingestor = new QdrantEmbeddingIngestor(client, stub,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig());
        ingestor.ingest(corpus, List.of(
            new ChunkInput("test content", "doc-1", Map.of())));

        var retriever = new HybridCaseRetriever(client, stub,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig());

        stub.clearCalls();

        // Query WITHOUT expansion
        retriever.retrieve(RetrievalQuery.of("original"), corpus, 10, null);

        // Should use single embed call
        assertThat(stub.embedCalls()).hasSize(1);
        assertThat(stub.embedCalls().get(0)).isEqualTo("original");
        assertThat(stub.batchCalls()).isEmpty();
    }

    // --- helpers ---

    private CorpusRef uniqueCorpus() {
        return new CorpusRef(TENANT, "retriever-corpus" + corpusCounter.incrementAndGet());
    }
}
