package io.casehub.neocortex.inference.bgem3;

import io.casehub.neocortex.inference.*;
import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BgeM3EmbedderTest {

    @Test
    void embedReturnsDenseSparseColbert() {
        InferenceModel model = stubBgeM3Model();
        BgeM3Embedder embedder = new BgeM3Embedder(model, 768);

        MultiModalEmbedding result = embedder.embed("test");
        assertNotNull(result.dense());
        assertNotNull(result.sparse());
        assertNotNull(result.colbert());
    }

    @Test
    void denseIsL2Normalized() {
        InferenceModel model = stubBgeM3Model();
        BgeM3Embedder embedder = new BgeM3Embedder(model, 768);

        float[] dense = embedder.embed("test").dense();
        double norm = 0;
        for (float f : dense) norm += f * f;
        assertEquals(1.0, Math.sqrt(norm), 1e-5);
    }

    @Test
    void sparseThresholdsAtPointZeroOne() {
        float[] sparseRaw = new float[100];
        sparseRaw[5] = 0.5f;
        sparseRaw[10] = 0.005f;  // below threshold
        sparseRaw[15] = 0.02f;

        InferenceModel model = stubWithSparse(sparseRaw);
        BgeM3Embedder embedder = new BgeM3Embedder(model, 768);

        Map<Integer, Float> sparse = embedder.embed("test").sparse();
        assertTrue(sparse.containsKey(5));
        assertFalse(sparse.containsKey(10));
        assertTrue(sparse.containsKey(15));
    }

    @Test
    void sparseNegativeValuesReLUdToZero() {
        float[] sparseRaw = new float[100];
        sparseRaw[5] = -1.0f;
        sparseRaw[10] = 0.5f;

        InferenceModel model = stubWithSparse(sparseRaw);
        BgeM3Embedder embedder = new BgeM3Embedder(model, 768);

        Map<Integer, Float> sparse = embedder.embed("test").sparse();
        assertFalse(sparse.containsKey(5));
        assertTrue(sparse.containsKey(10));
    }

    @Test
    void colbertRowsAreL2Normalized() {
        InferenceModel model = stubBgeM3Model();
        BgeM3Embedder embedder = new BgeM3Embedder(model, 768);

        float[][] colbert = embedder.embed("test").colbert();
        for (float[] row : colbert) {
            double norm = 0;
            for (float f : row) norm += f * f;
            assertEquals(1.0, Math.sqrt(norm), 1e-5);
        }
    }

    @Test
    void supportedModesIncludesAll() {
        BgeM3Embedder embedder = new BgeM3Embedder(stubBgeM3Model(), 768);
        assertEquals(Set.of(EmbeddingMode.DENSE, EmbeddingMode.SPARSE,
            EmbeddingMode.COLBERT), embedder.supportedModes());
    }

    @Test
    void dimensionsCorrect() {
        BgeM3Embedder embedder = new BgeM3Embedder(stubBgeM3Model(), 768);
        assertEquals(1024, embedder.denseDimension());
        assertEquals(OptionalInt.of(1024), embedder.colbertDimension());
        assertEquals(768, embedder.maxSequenceLength());
    }

    @Test
    void batchProducesOnePerInput() {
        BgeM3Embedder embedder = new BgeM3Embedder(stubBgeM3Model(), 768);
        List<MultiModalEmbedding> results = embedder.embedBatch(
            List.of("a", "b", "c"));
        assertEquals(3, results.size());
    }

    private static InferenceModel stubBgeM3Model() {
        return InMemoryInferenceModel.returningMulti(Map.of(
            "dense", new float[][]{{3f, 4f, 0f, 0f}},
            "sparse", new float[][]{{0f, 0f, 0f, 0f, 0f, 0.5f}},
            "colbert", new float[][]{{3f, 4f}, {1f, 0f}, {0f, 1f}}
        ));
    }

    private static InferenceModel stubWithSparse(float[] sparseRaw) {
        return InMemoryInferenceModel.returningMulti(Map.of(
            "dense", new float[][]{{1f, 0f}},
            "sparse", new float[][]{sparseRaw},
            "colbert", new float[][]{{1f, 0f}}
        ));
    }
}
