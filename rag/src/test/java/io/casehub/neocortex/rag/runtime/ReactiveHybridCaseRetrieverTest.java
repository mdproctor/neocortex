package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.inference.MultiModalEmbedder;
import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
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
class ReactiveHybridCaseRetrieverTest {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:v1.18.0")
        .withExposedPorts(6334);

    private static final int DENSE_DIM = 4;
    private static final String TENANT = "tenant-1";
    private static final AtomicInteger corpusCounter = new AtomicInteger();

    private QdrantClient                client;
    private QdrantEmbeddingIngestor     store;
    private ReactiveHybridCaseRetriever retriever;

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

        retriever = new ReactiveHybridCaseRetriever(
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

        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("brown fox"), corpus, 10, null)
            .await().indefinitely();

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(chunk -> {
            assertThat(chunk.content()).isNotBlank();
            assertThat(chunk.sourceDocumentId()).isNotBlank();
            assertThat(chunk.relevanceScore()).isGreaterThan(0.0);
        });
        assertThat(results).anyMatch(chunk ->
            "animals".equals(chunk.metadata().get("category"))
            || "tech".equals(chunk.metadata().get("category")));
    }

    @Test
    void retrieveRespectsMaxResults() {
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("chunk one about cats", "doc-1", Map.of()),
            new ChunkInput("chunk two about dogs", "doc-2", Map.of()),
            new ChunkInput("chunk three about birds", "doc-3", Map.of())
        ));

        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("animals"), corpus, 1, null)
            .await().indefinitely();

        assertThat(results).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void retrieveEmptyCorpus() {
        CorpusRef corpus = uniqueCorpus();

        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("anything"), corpus, 10, null)
            .await().indefinitely();

        assertThat(results).isEmpty();
    }

    @Test
    void tenancyMismatchThrows() {
        CorpusRef wrongTenant = new CorpusRef("other-tenant", "corpus");

        assertThatThrownBy(() -> retriever.retrieve(RetrievalQuery.of("query"), wrongTenant, 10, null)
            .await().indefinitely())
            .isInstanceOf(SecurityException.class);
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

        CorpusRef corpus = uniqueCorpus();
        noTenantStore.ingest(corpus, List.of(
            new ChunkInput("searchable content", "doc-1", Map.of())
        ));

        ReactiveHybridCaseRetriever noTenantRetriever = new ReactiveHybridCaseRetriever(
            client, embedder,
            noTenantGuard, noTenantConfig
        );

        List<RetrievedChunk> results = noTenantRetriever.retrieve(
            RetrievalQuery.of("searchable"), corpus, 10, null).await().indefinitely();
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

        ReactiveHybridCaseRetriever bm25Retriever = new ReactiveHybridCaseRetriever(
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
            RetrievalQuery.of("HashMap"), corpus, 10, null)
            .await().indefinitely();

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

        ReactiveHybridCaseRetriever retriever3 = new ReactiveHybridCaseRetriever(
            client, sparseEmbedder,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            threeWayConfig);

        CorpusRef corpus = uniqueCorpus();
        store3.ingest(corpus, List.of(
            new ChunkInput("ApplicationScoped CDI bean", "doc-1", Map.of()),
            new ChunkInput("Quarkus REST endpoint", "doc-2", Map.of())
        ));

        List<RetrievedChunk> results = retriever3.retrieve(
            RetrievalQuery.of("CDI bean"), corpus, 10, null)
            .await().indefinitely();

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

        var retriever = new ReactiveHybridCaseRetriever(client, stub,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig());

        stub.clearCalls();

        // Query WITH expansion active
        var expandedQuery = RetrievalQuery.of("original").withExpansion("hypothetical");
        retriever.retrieve(expandedQuery, corpus, 10, null)
            .await().indefinitely();

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

        var retriever = new ReactiveHybridCaseRetriever(client, stub,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig());

        stub.clearCalls();

        // Query WITHOUT expansion
        retriever.retrieve(RetrievalQuery.of("original"), corpus, 10, null)
            .await().indefinitely();

        // Should use single embed call
        assertThat(stub.embedCalls()).hasSize(1);
        assertThat(stub.embedCalls().get(0)).isEqualTo("original");
        assertThat(stub.batchCalls()).isEmpty();
    }

    // --- helpers ---

    private CorpusRef uniqueCorpus() {
        return new CorpusRef(TENANT, "rxretriever" + corpusCounter.incrementAndGet());
    }
}
