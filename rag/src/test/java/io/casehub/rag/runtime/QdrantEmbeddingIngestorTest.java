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

        store = new QdrantEmbeddingIngestor(
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
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig()
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

    @Test
    void ingestWorksWithoutCurrentPrincipal() throws Exception {
        QdrantEmbeddingIngestor noTenantStore = new QdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(null),
            RagTestFixtures.stubConfig()
        );

        CorpusRef corpus = uniqueCorpus();
        noTenantStore.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        ));
        assertThat(noTenantStore.listDocuments(corpus)).containsExactly("doc-1");

        noTenantStore.deleteDocument(corpus, "doc-1");
        assertThat(noTenantStore.listDocuments(corpus)).isEmpty();
    }

    @Test
    void ingestBatchesSplitCorrectly() {
        // batchSize=2, 5 chunks → 3 batches (2+2+1)
        QdrantEmbeddingIngestor batchedStore = new QdrantEmbeddingIngestor(
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
            new ChunkInput("chunk 5", "doc-1", Map.of())));

        assertThat(batchedStore.listDocuments(corpus))
            .containsExactlyInAnyOrder("doc-1", "doc-2");
    }

    @Test
    void ingestCrossBatchDocumentContinuity() {
        // doc-A has chunks in both batch 1 and batch 2 — indices must be continuous
        QdrantEmbeddingIngestor batchedStore = new QdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null, // dense-only for simplicity
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true, OptionalDouble.empty(), OptionalInt.empty(), 2, 64, 64, 40, 60, false, 10, false));

        CorpusRef corpus = uniqueCorpus();
        // Batch 1: [A#0, A#1], Batch 2: [A#2]
        batchedStore.ingest(corpus, List.of(
            new ChunkInput("a0", "doc-A", Map.of()),
            new ChunkInput("a1", "doc-A", Map.of()),
            new ChunkInput("a2", "doc-A", Map.of())));

        assertThat(batchedStore.listDocuments(corpus)).containsExactly("doc-A");

        // Re-ingest with same content — idempotent, no duplicates
        batchedStore.ingest(corpus, List.of(
            new ChunkInput("a0", "doc-A", Map.of()),
            new ChunkInput("a1", "doc-A", Map.of()),
            new ChunkInput("a2", "doc-A", Map.of())));

        assertThat(batchedStore.listDocuments(corpus)).containsExactly("doc-A");
    }

    @Test
    void ingestBatchSizeOne() {
        QdrantEmbeddingIngestor batchedStore = new QdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true, OptionalDouble.empty(), OptionalInt.empty(), 1, 64, 64, 40, 60, false, 10, false));

        CorpusRef corpus = uniqueCorpus();
        batchedStore.ingest(corpus, List.of(
            new ChunkInput("chunk 1", "doc-1", Map.of()),
            new ChunkInput("chunk 2", "doc-2", Map.of()),
            new ChunkInput("chunk 3", "doc-1", Map.of())));

        assertThat(batchedStore.listDocuments(corpus))
            .containsExactlyInAnyOrder("doc-1", "doc-2");
    }

    @Test
    void constructorRejectsBatchSizeZero() {
        assertThatThrownBy(() -> new QdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true, OptionalDouble.empty(), OptionalInt.empty(), 0, 64, 64, 40, 60, false, 10, false)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("batchSize");
    }

    @Test
    void ensureCollectionAppliesBinaryQuantization() throws Exception {
        QdrantEmbeddingIngestor quantizedStore = new QdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.BINARY, true, OptionalDouble.empty(), OptionalInt.empty(), Integer.MAX_VALUE, 64, 64, 40, 60, false, 10, false)
        );

        CorpusRef corpus = uniqueCorpus();
        quantizedStore.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        ));

        var info = client.getCollectionInfoAsync(
            TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus)).get();
        var denseParams = info.getConfig().getParams().getVectorsConfig()
            .getParamsMap().getMapMap().get("dense");
        assertThat(denseParams.hasQuantizationConfig()).isTrue();
        assertThat(denseParams.getQuantizationConfig().hasBinary()).isTrue();
        assertThat(denseParams.getQuantizationConfig().getBinary().getAlwaysRam()).isTrue();
    }

    @Test
    void ensureCollectionAppliesScalarQuantization() throws Exception {
        QdrantEmbeddingIngestor quantizedStore = new QdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.SCALAR, true, OptionalDouble.empty(), OptionalInt.empty(), Integer.MAX_VALUE, 64, 64, 40, 60, false, 10, false)
        );

        CorpusRef corpus = uniqueCorpus();
        quantizedStore.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        ));

        var info = client.getCollectionInfoAsync(
            TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus)).get();
        var denseParams = info.getConfig().getParams().getVectorsConfig()
            .getParamsMap().getMapMap().get("dense");
        assertThat(denseParams.hasQuantizationConfig()).isTrue();
        assertThat(denseParams.getQuantizationConfig().hasScalar()).isTrue();
        assertThat(denseParams.getQuantizationConfig().getScalar().getType())
            .isEqualTo(io.qdrant.client.grpc.Collections.QuantizationType.Int8);
        assertThat(denseParams.getQuantizationConfig().getScalar().getAlwaysRam()).isTrue();
    }

    @Test
    void ensureCollectionNoQuantizationByDefault() throws Exception {
        // Existing store uses DenseQuantization.NONE
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        ));

        var info = client.getCollectionInfoAsync(
            TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus)).get();
        var denseParams = info.getConfig().getParams().getVectorsConfig()
            .getParamsMap().getMapMap().get("dense");
        assertThat(denseParams.hasQuantizationConfig()).isFalse();
    }

    @Test
    void ensureCollectionRejectsDimensionMismatch() {
        // Create collection with dim=4 (via existing store)
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        ));

        // Try to ingest with dim=2 — should fail with dimension mismatch
        QdrantEmbeddingIngestor wrongDimStore = new QdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(2),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig()
        );

        assertThatThrownBy(() -> wrongDimStore.ingest(corpus, List.of(
            new ChunkInput("content", "doc-2", Map.of()))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dimension");
    }

    @Test
    void ensureCollectionCreatesPayloadIndexes() throws Exception {
        CorpusRef corpus = uniqueCorpus();
        store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        ));

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
        CorpusRef corpus = uniqueCorpus();
        String collection = TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus);

        // Create collection manually — vectors only, no payload indexes
        var denseParams = io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
            .setSize(DENSE_DIM)
            .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
            .build();
        var paramsMap = io.qdrant.client.grpc.Collections.VectorParamsMap.newBuilder()
            .putMap("dense", denseParams)
            .build();
        var sparseConfig = io.qdrant.client.grpc.Collections.SparseVectorConfig.newBuilder()
            .putMap("sparse", io.qdrant.client.grpc.Collections.SparseVectorParams.getDefaultInstance())
            .build();
        client.createCollectionAsync(
            io.qdrant.client.grpc.Collections.CreateCollection.newBuilder()
                .setCollectionName(collection)
                .setVectorsConfig(io.qdrant.client.grpc.Collections.VectorsConfig.newBuilder()
                    .setParamsMap(paramsMap).build())
                .setSparseVectorsConfig(sparseConfig)
                .build()).get();

        // Ingest triggers ensureCollection on existing collection
        store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        ));

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
        String collection = TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus);

        // First ingest creates collection + indexes
        store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())
        ));

        // Fresh ingestor — knownCollections cache is empty, forces re-check
        QdrantEmbeddingIngestor freshStore = new QdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig()
        );

        // Second ingest on existing collection with indexes already present
        freshStore.ingest(corpus, List.of(
            new ChunkInput("more content", "doc-2", Map.of())
        ));

        assertThat(freshStore.listDocuments(corpus))
            .containsExactlyInAnyOrder("doc-1", "doc-2");
    }

    @Test
    void ensureCollectionThrowsOnIndexTypeMismatch() throws Exception {
        CorpusRef corpus = uniqueCorpus();
        String collection = TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus);

        // Create collection manually
        var denseParams = io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
            .setSize(DENSE_DIM)
            .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
            .build();
        var paramsMap = io.qdrant.client.grpc.Collections.VectorParamsMap.newBuilder()
            .putMap("dense", denseParams)
            .build();
        var sparseConfig = io.qdrant.client.grpc.Collections.SparseVectorConfig.newBuilder()
            .putMap("sparse", io.qdrant.client.grpc.Collections.SparseVectorParams.getDefaultInstance())
            .build();
        client.createCollectionAsync(
            io.qdrant.client.grpc.Collections.CreateCollection.newBuilder()
                .setCollectionName(collection)
                .setVectorsConfig(io.qdrant.client.grpc.Collections.VectorsConfig.newBuilder()
                    .setParamsMap(paramsMap).build())
                .setSparseVectorsConfig(sparseConfig)
                .build()).get();

        // Create a Keyword index on 'content' — wrong type (should be Text)
        client.createPayloadIndexAsync(collection, "content",
            PayloadSchemaType.Keyword, null, true, null, null).get();

        // Ingest should detect type mismatch and throw
        assertThatThrownBy(() -> store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of()))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("content")
            .hasMessageContaining("Text")
            .hasMessageContaining("Keyword");
    }

    @Test
    void ensureCollectionCreatesBm25SparseVector() throws Exception {
        QdrantEmbeddingIngestor bm25Store = new QdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true, OptionalDouble.empty(), OptionalInt.empty(), Integer.MAX_VALUE, 64, 64, 40, 60, false, 10, true));

        CorpusRef corpus = uniqueCorpus();
        bm25Store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of())));

        var info = client.getCollectionInfoAsync(
            TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus)).get();
        var sparseVectors = info.getConfig().getParams().getSparseVectorsConfig().getMapMap();

        assertThat(sparseVectors).containsKey("bm25");
        assertThat(sparseVectors.get("bm25").getModifier())
            .isEqualTo(io.qdrant.client.grpc.Collections.Modifier.Idf);
    }

    @Test
    void ensureCollectionRejectsMissingBm25Vector() throws Exception {
        // Create collection without BM25 vector
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

        // Try ingesting with BM25 enabled — should fail
        QdrantEmbeddingIngestor bm25Store = new QdrantEmbeddingIngestor(
            client,
            new RagTestFixtures.StubEmbeddingModel(DENSE_DIM),
            null,
            TenantGuard.of(RagTestFixtures.stubPrincipal(TENANT)),
            RagTestFixtures.stubConfig("dense", "sparse", "bm25", TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true, OptionalDouble.empty(), OptionalInt.empty(), Integer.MAX_VALUE, 64, 64, 40, 60, false, 10, true));

        assertThatThrownBy(() -> bm25Store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of()))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("bm25");
    }

    @Test
    void ensureCollectionRejectsMissingSparseVector() throws Exception {
        // Create collection without SPLADE sparse vector
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

        // Try ingesting with SPLADE enabled — should fail
        assertThatThrownBy(() -> store.ingest(corpus, List.of(
            new ChunkInput("content", "doc-1", Map.of()))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sparse");
    }

    // --- helpers ---

    private CorpusRef uniqueCorpus() {
        return new CorpusRef(TENANT, "corpus" + corpusCounter.incrementAndGet());
    }
}
