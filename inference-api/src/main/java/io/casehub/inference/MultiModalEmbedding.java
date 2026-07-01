package io.casehub.inference;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Multi-modal embedding output — dense, sparse, and/or ColBERT representations from a single pass.
 * <p>
 * Dense is mandatory. Sparse and ColBERT are optional (null when not produced by the embedder).
 * All arrays are deep-copied on construction and access to prevent external mutation.
 */
public final class MultiModalEmbedding {
    private final float[] dense;
    private final Map<Integer, Float> sparse;
    private final float[][] colbert;

    /**
     * @param dense   Dense embedding vector (mandatory, must not be null)
     * @param sparse  Sparse embedding as term-index → weight map (nullable)
     * @param colbert ColBERT token embeddings as 2D array [tokens][dim] (nullable)
     * @throws NullPointerException if dense is null
     */
    public MultiModalEmbedding(float[] dense, Map<Integer, Float> sparse, float[][] colbert) {
        this.dense = Objects.requireNonNull(dense, "dense must not be null").clone();
        this.sparse = sparse != null ? Collections.unmodifiableMap(sparse) : null;
        this.colbert = colbert != null ? deepCopy(colbert) : null;
    }

    /**
     * @return Dense embedding (defensive copy)
     */
    public float[] dense() {
        return dense.clone();
    }

    /**
     * @return Sparse embedding map (unmodifiable view, null if not available)
     */
    public Map<Integer, Float> sparse() {
        return sparse;
    }

    /**
     * @return ColBERT token embeddings (defensive copy, null if not available)
     */
    public float[][] colbert() {
        return colbert != null ? deepCopy(colbert) : null;
    }

    private static float[][] deepCopy(float[][] src) {
        float[][] copy = new float[src.length][];
        for (int i = 0; i < src.length; i++) {
            copy[i] = src[i].clone();
        }
        return copy;
    }
}
