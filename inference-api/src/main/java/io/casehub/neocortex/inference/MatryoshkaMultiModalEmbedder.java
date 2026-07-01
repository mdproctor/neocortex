package io.casehub.neocortex.inference;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Matryoshka decorator for multi-modal embedders — truncates dense to target dimension
 * and L2-re-normalizes, passing sparse and ColBERT through unchanged.
 * <p>
 * Preserves existing Matryoshka truncation capability for models trained with
 * Matryoshka Representation Learning.
 */
public final class MatryoshkaMultiModalEmbedder implements MultiModalEmbedder {
    private final MultiModalEmbedder delegate;
    private final int targetDimension;

    /**
     * @param delegate         Embedder producing full-dimension dense embeddings
     * @param targetDimension  Target dense dimension (must be ≤ delegate's dense dimension)
     * @throws IllegalArgumentException if targetDimension > delegate.denseDimension()
     */
    public MatryoshkaMultiModalEmbedder(MultiModalEmbedder delegate, int targetDimension) {
        if (targetDimension > delegate.denseDimension()) {
            throw new IllegalArgumentException(
                "Target dimension " + targetDimension + " exceeds delegate dimension " + delegate.denseDimension());
        }
        this.delegate = delegate;
        this.targetDimension = targetDimension;
    }

    @Override
    public MultiModalEmbedding embed(String text) {
        MultiModalEmbedding embedding = delegate.embed(text);
        return truncateAndRenormalize(embedding);
    }

    @Override
    public List<MultiModalEmbedding> embedBatch(List<String> texts) {
        return delegate.embedBatch(texts).stream()
            .map(this::truncateAndRenormalize)
            .toList();
    }

    @Override
    public Set<EmbeddingMode> supportedModes() {
        return delegate.supportedModes();
    }

    @Override
    public int denseDimension() {
        return targetDimension;
    }

    @Override
    public OptionalInt colbertDimension() {
        return delegate.colbertDimension();
    }

    private MultiModalEmbedding truncateAndRenormalize(MultiModalEmbedding embedding) {
        float[] truncated = Arrays.copyOf(embedding.dense(), targetDimension);

        // L2 normalize
        double sumSquares = 0.0;
        for (float v : truncated) {
            sumSquares += v * v;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int i = 0; i < truncated.length; i++) {
                truncated[i] /= norm;
            }
        }

        return new MultiModalEmbedding(truncated, embedding.sparse(), embedding.colbert());
    }
}
