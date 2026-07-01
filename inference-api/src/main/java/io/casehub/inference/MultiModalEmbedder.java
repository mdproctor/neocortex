package io.casehub.inference;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Embedder that produces multi-modal output — dense, sparse, and/or ColBERT representations.
 * <p>
 * All embedders produce dense vectors. Sparse and ColBERT are optional capabilities
 * reported by {@link #supportedModes()}.
 */
public interface MultiModalEmbedder {
    /**
     * Embed a single text.
     *
     * @param text Input text
     * @return Multi-modal embedding
     */
    MultiModalEmbedding embed(String text);

    /**
     * Embed a batch of texts.
     *
     * @param texts Input texts
     * @return Multi-modal embeddings in the same order as inputs
     */
    List<MultiModalEmbedding> embedBatch(List<String> texts);

    /**
     * @return Embedding modes produced by this embedder (always includes {@code DENSE})
     */
    Set<EmbeddingMode> supportedModes();

    /**
     * @return Dense vector dimension
     */
    int denseDimension();

    /**
     * @return ColBERT token dimension (empty if ColBERT not supported)
     */
    OptionalInt colbertDimension();
}
