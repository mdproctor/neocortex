package io.casehub.inference;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MultiModalEmbeddingTest {

    @Test
    void denseDefensiveCopy() {
        float[] dense = {1f, 2f, 3f};
        var emb = new MultiModalEmbedding(dense, null, null);
        dense[0] = 999f;
        assertEquals(1f, emb.dense()[0]);
        emb.dense()[0] = 999f;
        assertEquals(1f, emb.dense()[0]);
    }

    @Test
    void sparseImmutable() {
        var sparse = Map.of(1, 0.5f, 2, 0.8f);
        var emb = new MultiModalEmbedding(new float[]{1f}, sparse, null);
        assertEquals(0.5f, emb.sparse().get(1));
        assertThrows(UnsupportedOperationException.class,
            () -> emb.sparse().put(3, 1.0f));
    }

    @Test
    void colbertDefensiveCopy() {
        float[][] colbert = {{1f, 2f}, {3f, 4f}};
        var emb = new MultiModalEmbedding(new float[]{1f}, null, colbert);
        colbert[0][0] = 999f;
        assertEquals(1f, emb.colbert()[0][0]);
        emb.colbert()[0][0] = 999f;
        assertEquals(1f, emb.colbert()[0][0]);
    }

    @Test
    void nullSparseAndColbertAllowed() {
        var emb = new MultiModalEmbedding(new float[]{1f}, null, null);
        assertNull(emb.sparse());
        assertNull(emb.colbert());
    }

    @Test
    void nullDenseRejected() {
        assertThrows(NullPointerException.class,
            () -> new MultiModalEmbedding(null, null, null));
    }
}
