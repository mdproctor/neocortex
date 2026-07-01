package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.inference.MultiModalEmbedding;
import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import io.qdrant.client.grpc.Points.PointStruct;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class QdrantPointBuilderTest {

    private static final RagConfig CONFIG = RagTestFixtures.stubConfig();

    @Test
    void computeChunkIndicesEmpty() {
        assertThat(QdrantPointBuilder.computeChunkIndices(List.of())).isEmpty();
    }

    @Test
    void computeChunkIndicesSingleChunk() {
        List<ChunkInput> chunks = List.of(
            new ChunkInput("text", "doc-1", Map.of()));
        assertThat(QdrantPointBuilder.computeChunkIndices(chunks))
            .containsExactly(0);
    }

    @Test
    void computeChunkIndicesAllSameDocument() {
        List<ChunkInput> chunks = List.of(
            new ChunkInput("a", "doc-1", Map.of()),
            new ChunkInput("b", "doc-1", Map.of()),
            new ChunkInput("c", "doc-1", Map.of()));
        assertThat(QdrantPointBuilder.computeChunkIndices(chunks))
            .containsExactly(0, 1, 2);
    }

    @Test
    void computeChunkIndicesAllDifferentDocuments() {
        List<ChunkInput> chunks = List.of(
            new ChunkInput("a", "doc-1", Map.of()),
            new ChunkInput("b", "doc-2", Map.of()),
            new ChunkInput("c", "doc-3", Map.of()));
        assertThat(QdrantPointBuilder.computeChunkIndices(chunks))
            .containsExactly(0, 0, 0);
    }

    @Test
    void computeChunkIndicesInterleavedDocuments() {
        List<ChunkInput> chunks = List.of(
            new ChunkInput("a", "doc-A", Map.of()),
            new ChunkInput("b", "doc-A", Map.of()),
            new ChunkInput("c", "doc-B", Map.of()),
            new ChunkInput("d", "doc-A", Map.of()),
            new ChunkInput("e", "doc-B", Map.of()));
        assertThat(QdrantPointBuilder.computeChunkIndices(chunks))
            .containsExactly(0, 1, 0, 2, 1);
    }

    @Test
    void buildPointDeterministicUuid() {
        ChunkInput chunk = new ChunkInput("text", "doc-1", Map.of());
        MultiModalEmbedding embedding = new MultiModalEmbedding(
            new float[]{0.1f, 0.2f, 0.3f}, null, null);

        PointStruct p1 = QdrantPointBuilder.buildPoint(
            chunk, new CorpusRef("t1", "corpus"), embedding, 0, CONFIG);
        PointStruct p2 = QdrantPointBuilder.buildPoint(
            chunk, new CorpusRef("t1", "corpus"), embedding, 0, CONFIG);

        assertThat(p1.getId()).isEqualTo(p2.getId());
    }

    @Test
    void buildPointDifferentChunkIndexProducesDifferentUuid() {
        ChunkInput chunk = new ChunkInput("text", "doc-1", Map.of());
        MultiModalEmbedding embedding = new MultiModalEmbedding(
            new float[]{0.1f, 0.2f, 0.3f}, null, null);
        CorpusRef corpus = new CorpusRef("t1", "corpus");

        PointStruct p0 = QdrantPointBuilder.buildPoint(
            chunk, corpus, embedding, 0, CONFIG);
        PointStruct p1 = QdrantPointBuilder.buildPoint(
            chunk, corpus, embedding, 1, CONFIG);

        assertThat(p0.getId()).isNotEqualTo(p1.getId());
    }

    @Test
    void buildPointDenseOnly() {
        ChunkInput chunk = new ChunkInput("text", "doc-1", Map.of("key", "val"));
        MultiModalEmbedding embedding = new MultiModalEmbedding(
            new float[]{0.1f, 0.2f}, null, null);
        CorpusRef corpus = new CorpusRef("t1", "corpus");

        PointStruct point = QdrantPointBuilder.buildPoint(
            chunk, corpus, embedding, 0, CONFIG);

        assertThat(point.getVectors().getVectors().getVectorsMap()).containsKey("dense");
        assertThat(point.getVectors().getVectors().getVectorsMap()).doesNotContainKey("sparse");
        assertThat(point.getPayloadMap().get("content").getStringValue()).isEqualTo("text");
        assertThat(point.getPayloadMap().get("sourceDocumentId").getStringValue()).isEqualTo("doc-1");
        assertThat(point.getPayloadMap().get("tenantId").getStringValue()).isEqualTo("t1");
        assertThat(point.getPayloadMap().get("key").getStringValue()).isEqualTo("val");
    }

    @Test
    void buildPointWithSparse() {
        ChunkInput chunk = new ChunkInput("text", "doc-1", Map.of());
        MultiModalEmbedding embedding = new MultiModalEmbedding(
            new float[]{0.1f, 0.2f}, Map.of(5, 0.9f, 10, 0.3f), null);
        CorpusRef corpus = new CorpusRef("t1", "corpus");

        PointStruct point = QdrantPointBuilder.buildPoint(
            chunk, corpus, embedding, 0, CONFIG);

        assertThat(point.getVectors().getVectors().getVectorsMap()).containsKey("dense");
        assertThat(point.getVectors().getVectors().getVectorsMap()).containsKey("sparse");
    }

    @Test
    void buildPointRejectsTenantIdMetadata() {
        ChunkInput chunk = new ChunkInput("text", "doc-1", Map.of("tenantId", "evil"));
        MultiModalEmbedding embedding = new MultiModalEmbedding(
            new float[]{0.1f, 0.2f}, null, null);
        CorpusRef corpus = new CorpusRef("t1", "corpus");

        assertThat(catchThrowable(() -> QdrantPointBuilder.buildPoint(
            chunk, corpus, embedding, 0, CONFIG)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenantId");
    }

    @Test
    void buildPointRejectsTenantIdInListMetadata() {
        ChunkInput chunk = new ChunkInput("text", "doc-1", Map.of(),
            Map.of("tenantId", List.of("evil")));
        MultiModalEmbedding embedding = new MultiModalEmbedding(
            new float[]{0.1f, 0.2f}, null, null);
        CorpusRef corpus = new CorpusRef("t1", "corpus");

        assertThat(catchThrowable(() -> QdrantPointBuilder.buildPoint(
            chunk, corpus, embedding, 0, CONFIG)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenantId");
    }

    @Test
    void buildPointWithBm25Vector() {
        ChunkInput chunk = new ChunkInput("ConcurrentHashMap is useful", "doc-1", Map.of());
        MultiModalEmbedding embedding = new MultiModalEmbedding(
            new float[]{0.1f, 0.2f}, null, null);
        CorpusRef corpus = new CorpusRef("t1", "corpus");

        RagConfig bm25Config = RagTestFixtures.stubConfig("dense", "sparse", "bm25",
            TenancyStrategy.SEPARATE_COLLECTIONS, DenseQuantization.NONE, true,
            OptionalDouble.empty(), OptionalInt.empty(), Integer.MAX_VALUE,
            64, 64, 40, 60, false, 10, true);

        PointStruct point = QdrantPointBuilder.buildPoint(
            chunk, corpus, embedding, 0, bm25Config);

        assertThat(point.getVectors().getVectors().getVectorsMap()).containsKey("bm25");
        var bm25Vector = point.getVectors().getVectors().getVectorsMap().get("bm25");
        assertThat(bm25Vector.hasDocument()).isTrue();
        assertThat(bm25Vector.getDocument().getText()).contains("Concurrent Hash Map");
        assertThat(bm25Vector.getDocument().getModel()).isEqualTo("qdrant/bm25");
    }

    @Test
    void buildPointWithoutBm25WhenDisabled() {
        ChunkInput chunk = new ChunkInput("text", "doc-1", Map.of());
        MultiModalEmbedding embedding = new MultiModalEmbedding(
            new float[]{0.1f, 0.2f}, null, null);
        CorpusRef corpus = new CorpusRef("t1", "corpus");

        PointStruct point = QdrantPointBuilder.buildPoint(
            chunk, corpus, embedding, 0, CONFIG);

        assertThat(point.getVectors().getVectors().getVectorsMap()).doesNotContainKey("bm25");
    }
}
