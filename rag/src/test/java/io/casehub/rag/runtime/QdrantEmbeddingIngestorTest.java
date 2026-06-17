package io.casehub.rag.runtime;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.inference.inmem.InMemoryInferenceModel;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
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
class QdrantEmbeddingIngestorTest {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:v1.18.0")
        .withExposedPorts(6334);

    private static final int DENSE_DIM = 4;
    private static final String TENANT = "tenant-1";

    private static final AtomicInteger corpusCounter = new AtomicInteger();

    private QdrantClient            client;
    private QdrantEmbeddingIngestor store;

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
        // log1p(max(0, v)) ≥ 0.01 when v ≥ ~0.01005.
        // Values: 0.5, 0.0, 0.3, 0.0, 0.8, 0.0, 0.0, 0.2 → indices 0,2,4,7 survive threshold
        InMemoryInferenceModel spladeModel = InMemoryInferenceModel.returning(
            0.5f, 0.0f, 0.3f, 0.0f, 0.8f, 0.0f, 0.0f, 0.2f
        );
        SparseEmbedder sparseEmbedder = new SparseEmbedder(spladeModel);

        CurrentPrincipal principal = RagTestFixtures.stubPrincipal(TENANT);

        store = new QdrantEmbeddingIngestor(
            client, embeddingModel, sparseEmbedder,
            TenancyStrategy.SEPARATE_COLLECTIONS,
            "dense", "sparse",
            principal
        );
    }

    @Test
    void ingestCreatesCollectionAndUpserts() throws Exception {
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("first chunk", "doc-1", Map.of("key", "val"))
        ));

        assertThat(client.collectionExistsAsync(
            TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus)).get())
            .isTrue();
        assertThat(store.listDocuments(corpus)).containsExactly("doc-1");
    }

    @Test
    void ingestMultipleDocuments() {
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("chunk a", "doc-1", Map.of()),
            new ChunkInput("chunk b", "doc-1", Map.of())
        ));
        store.ingest(corpus, List.of(
            new ChunkInput("chunk c", "doc-2", Map.of())
        ));

        assertThat(store.listDocuments(corpus)).containsExactlyInAnyOrder("doc-1", "doc-2");
    }

    @Test
    void deleteDocument() {
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("chunk a", "doc-1", Map.of()),
            new ChunkInput("chunk b", "doc-2", Map.of())
        ));

        store.deleteDocument(corpus, "doc-1");

        assertThat(store.listDocuments(corpus)).containsExactly("doc-2");
    }

    @Test
    void deleteCorpusSeparateMode() throws Exception {
        CorpusRef corpus = uniqueCorpus();
        String collection = TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus);

        store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        ));
        assertThat(client.collectionExistsAsync(collection).get()).isTrue();

        store.deleteCorpus(corpus);

        assertThat(client.collectionExistsAsync(collection).get()).isFalse();
    }

    @Test
    void tenancyMismatchThrows() {
        CorpusRef wrongTenant = new CorpusRef("other-tenant", "corpus");

        assertThatThrownBy(() -> store.ingest(wrongTenant, List.of(
            new ChunkInput("text", "doc-1", Map.of()))))
            .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> store.deleteDocument(wrongTenant, "doc-1"))
            .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> store.deleteCorpus(wrongTenant))
            .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> store.listDocuments(wrongTenant))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void deleteCorpusThenReingestRecreatesCollection() throws Exception {
        CorpusRef corpus = uniqueCorpus();
        String collection = TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus);

        store.ingest(corpus, List.of(
            new ChunkInput("original content", "doc-1", Map.of())
        ));
        assertThat(client.collectionExistsAsync(collection).get()).isTrue();

        store.deleteCorpus(corpus);
        assertThat(client.collectionExistsAsync(collection).get()).isFalse();

        store.ingest(corpus, List.of(
            new ChunkInput("new content", "doc-2", Map.of())
        ));
        assertThat(client.collectionExistsAsync(collection).get()).isTrue();
        assertThat(store.listDocuments(corpus)).containsExactly("doc-2");
    }

    @Test
    void listDocumentsOnNonExistentCorpusReturnsEmpty() {
        CorpusRef corpus = uniqueCorpus();
        assertThat(store.listDocuments(corpus)).isEmpty();
    }

    @Test
    void ingestDenseOnlyMode() throws Exception {
        // Create ingestor with null SparseEmbedder — dense-only mode
        QdrantEmbeddingIngestor denseOnlyStore = new QdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null, // no sparse embedder
            TenancyStrategy.SEPARATE_COLLECTIONS,
            "dense", "sparse",
            RagTestFixtures.stubPrincipal(TENANT)
        );

        CorpusRef corpus = uniqueCorpus();
        denseOnlyStore.ingest(corpus, List.of(
            new ChunkInput("dense-only chunk", "doc-1", Map.of("key", "val"))
        ));

        // Collection should exist
        assertThat(client.collectionExistsAsync(
            TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus)).get())
            .isTrue();

        // Document should be listed
        assertThat(denseOnlyStore.listDocuments(corpus)).containsExactly("doc-1");
    }

    @Test
    void reingestSameDocumentProducesIdempotentUpsert() {
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("original text", "doc-1", Map.of())
        ));
        assertThat(store.listDocuments(corpus)).containsExactly("doc-1");

        // Re-ingest same document — should overwrite, not duplicate
        store.ingest(corpus, List.of(
            new ChunkInput("updated text", "doc-1", Map.of())
        ));
        assertThat(store.listDocuments(corpus)).containsExactly("doc-1");
    }

    @Test
    void multiDocBatchProducesStableIds() {
        CorpusRef corpus = uniqueCorpus();

        // Ingest A and B together in a batch
        store.ingest(corpus, List.of(
            new ChunkInput("chunk A", "doc-A", Map.of()),
            new ChunkInput("chunk B", "doc-B", Map.of())
        ));

        // Delete B and re-ingest B alone
        store.deleteDocument(corpus, "doc-B");
        store.ingest(corpus, List.of(
            new ChunkInput("chunk B", "doc-B", Map.of())
        ));

        // B should still be exactly 1 document (same deterministic ID regardless of batch)
        assertThat(store.listDocuments(corpus)).containsExactlyInAnyOrder("doc-A", "doc-B");
    }

    // --- helpers ---

    private CorpusRef uniqueCorpus() {
        return new CorpusRef(TENANT, "corpus" + corpusCounter.incrementAndGet());
    }
}
