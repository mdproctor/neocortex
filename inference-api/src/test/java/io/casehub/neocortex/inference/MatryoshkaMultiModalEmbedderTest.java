package io.casehub.neocortex.inference;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MatryoshkaMultiModalEmbedderTest {

    @Test
    void truncatesDenseToTargetDimension() {
        MultiModalEmbedder delegate = stubEmbedder(
            new float[]{1f, 2f, 3f, 4f}, null, null, 4);
        var matryoshka = new MatryoshkaMultiModalEmbedder(delegate, 2);

        MultiModalEmbedding result = matryoshka.embed("test");
        assertEquals(2, result.dense().length);
        assertEquals(2, matryoshka.denseDimension());
    }

    @Test
    void reNormalizesAfterTruncation() {
        float[] dense = {3f, 4f, 0f, 0f};
        MultiModalEmbedder delegate = stubEmbedder(dense, null, null, 4);
        var matryoshka = new MatryoshkaMultiModalEmbedder(delegate, 2);

        float[] result = matryoshka.embed("test").dense();
        double norm = Math.sqrt(result[0] * result[0] + result[1] * result[1]);
        assertEquals(1.0, norm, 1e-6);
    }

    @Test
    void sparsePassedThrough() {
        var sparse = Map.of(1, 0.5f);
        MultiModalEmbedder delegate = stubEmbedder(
            new float[]{1f, 2f}, sparse, null, 2);
        var matryoshka = new MatryoshkaMultiModalEmbedder(delegate, 1);

        assertNotNull(matryoshka.embed("test").sparse());
        assertEquals(0.5f, matryoshka.embed("test").sparse().get(1));
    }

    @Test
    void colbertPassedThrough() {
        float[][] colbert = {{1f, 2f}, {3f, 4f}};
        MultiModalEmbedder delegate = stubEmbedder(
            new float[]{1f, 2f}, null, colbert, 2);
        var matryoshka = new MatryoshkaMultiModalEmbedder(delegate, 1);

        assertNotNull(matryoshka.embed("test").colbert());
        assertEquals(2, matryoshka.embed("test").colbert().length);
    }

    @Test
    void colbertDimensionDelegated() {
        MultiModalEmbedder delegate = stubEmbedder(
            new float[]{1f}, null, null, 1);
        var matryoshka = new MatryoshkaMultiModalEmbedder(delegate, 1);
        assertEquals(delegate.colbertDimension(), matryoshka.colbertDimension());
    }

    @Test
    void maxSequenceLengthDelegated() {
        MultiModalEmbedder delegate = stubEmbedder(
            new float[]{1f, 2f}, null, null, 2);
        var matryoshka = new MatryoshkaMultiModalEmbedder(delegate, 1);
        assertEquals(512, matryoshka.maxSequenceLength());
    }

    @Test
    void targetExceedingDelegateRejected() {
        MultiModalEmbedder delegate = stubEmbedder(
            new float[]{1f, 2f}, null, null, 2);
        assertThrows(IllegalArgumentException.class,
            () -> new MatryoshkaMultiModalEmbedder(delegate, 5));
    }

    @Test
    void batchTruncatesAll() {
        MultiModalEmbedder delegate = stubEmbedder(
            new float[]{1f, 2f, 3f, 4f}, null, null, 4);
        var matryoshka = new MatryoshkaMultiModalEmbedder(delegate, 2);

        List<MultiModalEmbedding> results = matryoshka.embedBatch(List.of("a", "b"));
        assertEquals(2, results.size());
        for (var r : results) assertEquals(2, r.dense().length);
    }

    private static MultiModalEmbedder stubEmbedder(
            float[] dense, Map<Integer, Float> sparse, float[][] colbert, int dim) {
        return new MultiModalEmbedder() {
            @Override public MultiModalEmbedding embed(String text) {
                return new MultiModalEmbedding(dense.clone(), sparse, colbert);
            }
            @Override public List<MultiModalEmbedding> embedBatch(List<String> texts) {
                return texts.stream().map(t -> embed(t)).toList();
            }
            @Override public Set<EmbeddingMode> supportedModes() {
                return EnumSet.of(EmbeddingMode.DENSE);
            }
            @Override public int denseDimension() { return dim; }
            @Override public OptionalInt colbertDimension() { return OptionalInt.empty(); }
            @Override public int maxSequenceLength() { return 512; }
        };
    }
}
