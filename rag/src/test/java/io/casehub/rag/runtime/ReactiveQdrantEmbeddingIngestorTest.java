package io.casehub.rag.runtime;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.inference.inmem.InMemoryInferenceModel;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
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
class ReactiveQdrantEmbeddingIngestorTest {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:v1.18.0")
        .withExposedPorts(6334);

    private static final int DENSE_DIM = 4;
    private static final String TENANT = "tenant-1";
    private static final AtomicInteger corpusCounter = new AtomicInteger();

    private QdrantClient                    client;
    private ReactiveQdrantEmbeddingIngestor store;

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

        store = new ReactiveQdrantEmbeddingIngestor(
            client, embeddingModel, sparseEmbedder,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig()
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
    void deleteCorpusThenReingestRecreatesCollection() throws Exception {
        CorpusRef corpus = uniqueCorpus();
        String collection = TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus);

        store.ingest(corpus, List.of(
            new ChunkInput("original content", "doc-1", Map.of())
        )).await().indefinitely();
        assertThat(client.collectionExistsAsync(collection).get()).isTrue();

        store.deleteCorpus(corpus).await().indefinitely();
        assertThat(client.collectionExistsAsync(collection).get()).isFalse();

        store.ingest(corpus, List.of(
            new ChunkInput("new content", "doc-2", Map.of())
        )).await().indefinitely();
        assertThat(client.collectionExistsAsync(collection).get()).isTrue();
        List<String> docs = store.listDocuments(corpus).await().indefinitely();
        assertThat(docs).containsExactly("doc-2");
    }

    @Test
    void listDocumentsOnNonExistentCorpusReturnsEmpty() {
        CorpusRef corpus = uniqueCorpus();
        List<String> docs = store.listDocuments(corpus).await().indefinitely();
        assertThat(docs).isEmpty();
    }

    @Test
    void ingestWorksWithoutCurrentPrincipal() throws Exception {
        ReactiveQdrantEmbeddingIngestor noTenantStore = new ReactiveQdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(null),
            RagTestFixtures.stubConfig()
        );

        CorpusRef corpus = uniqueCorpus();
        noTenantStore.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        )).await().indefinitely();
        List<String> docs = noTenantStore.listDocuments(corpus).await().indefinitely();
        assertThat(docs).containsExactly("doc-1");
    }

    @Test
    void ingestBatchesSplitCorrectly() {
        ReactiveQdrantEmbeddingIngestor batchedStore = new ReactiveQdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            new SparseEmbedder(InMemoryInferenceModel.returning(
                0.5f, 0.0f, 0.3f, 0.0f, 0.8f, 0.0f, 0.0f, 0.2f)),
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true, OptionalDouble.empty(), OptionalInt.empty(), 2, 64, 64, 40, 60, false, 10, false));

        CorpusRef corpus = uniqueCorpus();
        batchedStore.ingest(corpus, List.of(
            new ChunkInput("chunk 1", "doc-1", Map.of()),
            new ChunkInput("chunk 2", "doc-1", Map.of()),
            new ChunkInput("chunk 3", "doc-2", Map.of()),
            new ChunkInput("chunk 4", "doc-2", Map.of()),
            new ChunkInput("chunk 5", "doc-1", Map.of())))
            .await().indefinitely();

        List<String> docs = batchedStore.listDocuments(corpus).await().indefinitely();
        assertThat(docs).containsExactlyInAnyOrder("doc-1", "doc-2");
    }

    @Test
    void ingestCrossBatchDocumentContinuity() {
        ReactiveQdrantEmbeddingIngestor batchedStore = new ReactiveQdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true, OptionalDouble.empty(), OptionalInt.empty(), 2, 64, 64, 40, 60, false, 10, false));

        CorpusRef corpus = uniqueCorpus();
        batchedStore.ingest(corpus, List.of(
            new ChunkInput("a0", "doc-A", Map.of()),
            new ChunkInput("a1", "doc-A", Map.of()),
            new ChunkInput("a2", "doc-A", Map.of())))
            .await().indefinitely();

        List<String> docs = batchedStore.listDocuments(corpus).await().indefinitely();
        assertThat(docs).containsExactly("doc-A");

        batchedStore.ingest(corpus, List.of(
            new ChunkInput("a0", "doc-A", Map.of()),
            new ChunkInput("a1", "doc-A", Map.of()),
            new ChunkInput("a2", "doc-A", Map.of())))
            .await().indefinitely();

        docs = batchedStore.listDocuments(corpus).await().indefinitely();
        assertThat(docs).containsExactly("doc-A");
    }

    @Test
    void ingestBatchSizeOne() {
        ReactiveQdrantEmbeddingIngestor batchedStore = new ReactiveQdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true, OptionalDouble.empty(), OptionalInt.empty(), 1, 64, 64, 40, 60, false, 10, false));

        CorpusRef corpus = uniqueCorpus();
        batchedStore.ingest(corpus, List.of(
            new ChunkInput("chunk 1", "doc-1", Map.of()),
            new ChunkInput("chunk 2", "doc-2", Map.of()),
            new ChunkInput("chunk 3", "doc-1", Map.of())))
            .await().indefinitely();

        List<String> docs = batchedStore.listDocuments(corpus).await().indefinitely();
        assertThat(docs).containsExactlyInAnyOrder("doc-1", "doc-2");
    }

    @Test
    void constructorRejectsBatchSizeZero() {
        assertThatThrownBy(() -> new ReactiveQdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true, OptionalDouble.empty(), OptionalInt.empty(), 0, 64, 64, 40, 60, false, 10, false)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("batchSize");
    }

    @Test
    void ensureCollectionCreatesPayloadIndexes() throws Exception {
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        )).await().indefinitely();

        var info = client.getCollectionInfoAsync(
            TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus)).get();
        var schema = info.getPayloadSchemaMap();

        assertThat(schema).containsKey("content");
        assertThat(schema.get("content").getDataType())
            .isEqualTo(PayloadSchemaType.Text);

        assertThat(schema).containsKey("sourceDocumentId");
        assertThat(schema.get("sourceDocumentId").getDataType())
            .isEqualTo(PayloadSchemaType.Keyword);

        assertThat(schema).containsKey("tenantId");
        assertThat(schema.get("tenantId").getDataType())
            .isEqualTo(PayloadSchemaType.Keyword);
    }

    @Test
    void ensureCollectionAddsIndexesToExistingCollection() throws Exception {
        ReactiveQdrantEmbeddingIngestor noSparseStore = new ReactiveQdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig()
        );

        CorpusRef corpus = uniqueCorpus();
        String collection = TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus);

        var denseParams = io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
            .setSize(DENSE_DIM)
            .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
            .build();
        var paramsMap = io.qdrant.client.grpc.Collections.VectorParamsMap.newBuilder()
            .putMap("dense", denseParams)
            .build();
        client.createCollectionAsync(
            io.qdrant.client.grpc.Collections.CreateCollection.newBuilder()
                .setCollectionName(collection)
                .setVectorsConfig(io.qdrant.client.grpc.Collections.VectorsConfig.newBuilder()
                    .setParamsMap(paramsMap).build())
                .build()).get();

        noSparseStore.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        )).await().indefinitely();

        var info = client.getCollectionInfoAsync(collection).get();
        var schema = info.getPayloadSchemaMap();

        assertThat(schema).containsKey("content");
        assertThat(schema.get("content").getDataType()).isEqualTo(PayloadSchemaType.Text);
        assertThat(schema).containsKey("sourceDocumentId");
        assertThat(schema.get("sourceDocumentId").getDataType()).isEqualTo(PayloadSchemaType.Keyword);
        assertThat(schema).containsKey("tenantId");
        assertThat(schema.get("tenantId").getDataType()).isEqualTo(PayloadSchemaType.Keyword);
    }

    @Test
    void ensureCollectionIdempotentOnAlreadyIndexedCollection() throws Exception {
        CorpusRef corpus = uniqueCorpus();

        store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        )).await().indefinitely();

        ReactiveQdrantEmbeddingIngestor freshStore = new ReactiveQdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig()
        );

        freshStore.ingest(corpus, List.of(
            new ChunkInput("more content", "doc-2", Map.of())
        )).await().indefinitely();

        List<String> docs = freshStore.listDocuments(corpus).await().indefinitely();
        assertThat(docs).containsExactlyInAnyOrder("doc-1", "doc-2");
    }

    @Test
    void ensureCollectionThrowsOnIndexTypeMismatch() throws Exception {
        ReactiveQdrantEmbeddingIngestor noSparseStore = new ReactiveQdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig()
        );

        CorpusRef corpus = uniqueCorpus();
        String collection = TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus);

        var denseParams = io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
            .setSize(DENSE_DIM)
            .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
            .build();
        var paramsMap = io.qdrant.client.grpc.Collections.VectorParamsMap.newBuilder()
            .putMap("dense", denseParams)
            .build();
        client.createCollectionAsync(
            io.qdrant.client.grpc.Collections.CreateCollection.newBuilder()
                .setCollectionName(collection)
                .setVectorsConfig(io.qdrant.client.grpc.Collections.VectorsConfig.newBuilder()
                    .setParamsMap(paramsMap).build())
                .build()).get();

        client.createPayloadIndexAsync(collection, "content",
            PayloadSchemaType.Keyword, null, true, null, null).get();

        assertThatThrownBy(() -> noSparseStore.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        )).await().indefinitely())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("content")
            .hasMessageContaining("Text")
            .hasMessageContaining("Keyword");
    }

    @Test
    void ensureCollectionCreatesBm25SparseVector() throws Exception {
        ReactiveQdrantEmbeddingIngestor bm25Store = new ReactiveQdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true, OptionalDouble.empty(), OptionalInt.empty(), Integer.MAX_VALUE, 64, 64, 40, 60, false, 10, true));

        CorpusRef corpus = uniqueCorpus();
        bm25Store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        )).await().indefinitely();

        var info = client.getCollectionInfoAsync(
            TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus)).get();
        var sparseVectors = info.getConfig().getParams().getSparseVectorsConfig().getMapMap();

        assertThat(sparseVectors).containsKey("bm25");
        assertThat(sparseVectors.get("bm25").getModifier())
            .isEqualTo(io.qdrant.client.grpc.Collections.Modifier.Idf);
    }

    @Test
    void ensureCollectionRejectsMissingBm25Vector() throws Exception {
        CorpusRef corpus = uniqueCorpus();
        String collection = TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus);

        var denseParams = io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
            .setSize(DENSE_DIM)
            .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine).build();
        client.createCollectionAsync(
            io.qdrant.client.grpc.Collections.CreateCollection.newBuilder()
                .setCollectionName(collection)
                .setVectorsConfig(io.qdrant.client.grpc.Collections.VectorsConfig.newBuilder()
                    .setParamsMap(io.qdrant.client.grpc.Collections.VectorParamsMap.newBuilder()
                        .putMap("dense", denseParams).build()).build())
                .build()).get();

        ReactiveQdrantEmbeddingIngestor bm25Store = new ReactiveQdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true, OptionalDouble.empty(), OptionalInt.empty(), Integer.MAX_VALUE, 64, 64, 40, 60, false, 10, true));

        assertThatThrownBy(() -> bm25Store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        )).await().indefinitely())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("bm25");
    }

    @Test
    void ensureCollectionRejectsMissingSparseVector() throws Exception {
        CorpusRef corpus = uniqueCorpus();
        String collection = TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus);

        var denseParams = io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
            .setSize(DENSE_DIM)
            .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine).build();
        client.createCollectionAsync(
            io.qdrant.client.grpc.Collections.CreateCollection.newBuilder()
                .setCollectionName(collection)
                .setVectorsConfig(io.qdrant.client.grpc.Collections.VectorsConfig.newBuilder()
                    .setParamsMap(io.qdrant.client.grpc.Collections.VectorParamsMap.newBuilder()
                        .putMap("dense", denseParams).build()).build())
                .build()).get();

        assertThatThrownBy(() -> store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        )).await().indefinitely())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sparse");
    }

    // --- helpers ---

    private CorpusRef uniqueCorpus() {
        return new CorpusRef(TENANT, "rxcorpus" + corpusCounter.incrementAndGet());
    }
}
