package io.casehub.rag.runtime;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.inference.inmem.InMemoryInferenceModel;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
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
    private static final String DENSE_VECTOR_NAME = "dense";
    private static final String SPARSE_VECTOR_NAME = "sparse";
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

        EmbeddingModel embeddingModel = new RagTestFixtures.StubEmbeddingModel(DENSE_DIM);

        InMemoryInferenceModel spladeModel = InMemoryInferenceModel.returning(
            0.5f, 0.0f, 0.3f, 0.0f, 0.8f, 0.0f, 0.0f, 0.2f
        );
        SparseEmbedder sparseEmbedder = new SparseEmbedder(spladeModel);

        TenantGuard guard = TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT));

        store = new QdrantEmbeddingIngestor(
            client, embeddingModel, sparseEmbedder,
            TenancyStrategy.SEPARATE_COLLECTIONS,
            DENSE_VECTOR_NAME, SPARSE_VECTOR_NAME,
            guard,
            Integer.MAX_VALUE
        );

        retriever = new ReactiveHybridCaseRetriever(
            client, embeddingModel, sparseEmbedder,
            TenancyStrategy.SEPARATE_COLLECTIONS,
            DENSE_VECTOR_NAME, SPARSE_VECTOR_NAME,
            64, 64, 60,
            false, 10, null,
            guard
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
        EmbeddingModel model = new RagTestFixtures.StubEmbeddingModel(DENSE_DIM);

        QdrantEmbeddingIngestor noTenantStore = new QdrantEmbeddingIngestor(
            client, model, null,
            TenancyStrategy.SEPARATE_COLLECTIONS,
            DENSE_VECTOR_NAME, SPARSE_VECTOR_NAME,
            noTenantGuard,
            Integer.MAX_VALUE
        );

        CorpusRef corpus = uniqueCorpus();
        noTenantStore.ingest(corpus, List.of(
            new ChunkInput("searchable content", "doc-1", Map.of())
        ));

        ReactiveHybridCaseRetriever noTenantRetriever = new ReactiveHybridCaseRetriever(
            client, model, null,
            TenancyStrategy.SEPARATE_COLLECTIONS,
            DENSE_VECTOR_NAME, SPARSE_VECTOR_NAME,
            64, 64, 60,
            false, 10, null,
            noTenantGuard
        );

        List<RetrievedChunk> results = noTenantRetriever.retrieve(
            RetrievalQuery.of("searchable"), corpus, 10, null).await().indefinitely();
        assertThat(results).isNotEmpty();
    }

    // --- helpers ---

    private CorpusRef uniqueCorpus() {
        return new CorpusRef(TENANT, "rxretriever" + corpusCounter.incrementAndGet());
    }
}
