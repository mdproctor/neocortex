package io.casehub.rag.runtime;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ReactiveQdrantCorpusStoreTest {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:v1.18.0")
        .withExposedPorts(6334);

    private static final int DENSE_DIM = 4;
    private static final String TENANT = "tenant-1";
    private static final AtomicInteger corpusCounter = new AtomicInteger();

    private QdrantClient client;
    private ReactiveQdrantCorpusStore store;

    @BeforeEach
    void setUp() {
        client = new QdrantClient(
            QdrantGrpcClient.newBuilder(
                qdrant.getHost(),
                qdrant.getMappedPort(6334),
                false
            ).build()
        );

        EmbeddingModel embeddingModel = new StubEmbeddingModel(DENSE_DIM);

        InMemoryInferenceModel spladeModel = InMemoryInferenceModel.returning(
            0.5f, 0.0f, 0.3f, 0.0f, 0.8f, 0.0f, 0.0f, 0.2f
        );
        SparseEmbedder sparseEmbedder = new SparseEmbedder(spladeModel);

        CurrentPrincipal principal = stubPrincipal(TENANT);

        store = new ReactiveQdrantCorpusStore(
            client, embeddingModel, sparseEmbedder,
            TenancyStrategy.SEPARATE_COLLECTIONS,
            "dense", "sparse", DENSE_DIM,
            principal
        );
    }

    @Test
    void ingestCreatesCollectionAndUpserts() throws Exception {
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("first chunk", "doc-1", Map.of("key", "val"))
        )).await().indefinitely();

        assertThat(client.collectionExistsAsync(
            TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus)).get())
            .isTrue();
        List<String> docs = store.listDocuments(corpus).await().indefinitely();
        assertThat(docs).containsExactly("doc-1");
    }

    @Test
    void ingestMultipleDocuments() {
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("chunk a", "doc-1", Map.of()),
            new ChunkInput("chunk b", "doc-1", Map.of())
        )).await().indefinitely();
        store.ingest(corpus, List.of(
            new ChunkInput("chunk c", "doc-2", Map.of())
        )).await().indefinitely();

        List<String> docs = store.listDocuments(corpus).await().indefinitely();
        assertThat(docs).containsExactlyInAnyOrder("doc-1", "doc-2");
    }

    @Test
    void deleteDocument() {
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("chunk a", "doc-1", Map.of()),
            new ChunkInput("chunk b", "doc-2", Map.of())
        )).await().indefinitely();

        store.deleteDocument(corpus, "doc-1").await().indefinitely();

        List<String> docs = store.listDocuments(corpus).await().indefinitely();
        assertThat(docs).containsExactly("doc-2");
    }

    @Test
    void deleteCorpusSeparateMode() throws Exception {
        CorpusRef corpus = uniqueCorpus();
        String collection = TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus);

        store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        )).await().indefinitely();
        assertThat(client.collectionExistsAsync(collection).get()).isTrue();

        store.deleteCorpus(corpus).await().indefinitely();

        assertThat(client.collectionExistsAsync(collection).get()).isFalse();
    }

    @Test
    void tenancyMismatchThrows() {
        CorpusRef wrongTenant = new CorpusRef("other-tenant", "corpus");

        assertThatThrownBy(() -> store.ingest(wrongTenant, List.of(
            new ChunkInput("text", "doc-1", Map.of()))).await().indefinitely())
            .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> store.deleteDocument(wrongTenant, "doc-1")
            .await().indefinitely())
            .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> store.deleteCorpus(wrongTenant)
            .await().indefinitely())
            .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> store.listDocuments(wrongTenant)
            .await().indefinitely())
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void listDocumentsOnNonExistentCorpusReturnsEmpty() {
        CorpusRef corpus = uniqueCorpus();
        List<String> docs = store.listDocuments(corpus).await().indefinitely();
        assertThat(docs).isEmpty();
    }

    // --- helpers ---

    private CorpusRef uniqueCorpus() {
        return new CorpusRef(TENANT, "rxcorpus" + corpusCounter.incrementAndGet());
    }

    private static CurrentPrincipal stubPrincipal(String tenantId) {
        return new CurrentPrincipal() {
            @Override public String actorId() { return "test-actor"; }
            @Override public Set<String> groups() { return Set.of(); }
            @Override public String tenancyId() { return tenantId; }
            @Override public boolean isCrossTenantAdmin() { return false; }
        };
    }

    private static final class StubEmbeddingModel implements EmbeddingModel {
        private final int dim;
        StubEmbeddingModel(int dim) { this.dim = dim; }
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
        public int dimension() { return dim; }
    }
}
