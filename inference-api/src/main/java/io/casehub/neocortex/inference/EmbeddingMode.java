package io.casehub.neocortex.inference;

/**
 * Embedding output mode supported by a multi-modal embedder.
 */
public enum EmbeddingMode {
    /**
     * Dense vector embedding — fixed-dimension real-valued vector.
     */
    DENSE,

    /**
     * Sparse embedding — term-weight pairs, typically SPLADE-style learned sparse representation.
     */
    SPARSE,

    /**
     * ColBERT late-interaction embeddings — sequence of token-level dense vectors.
     */
    COLBERT
}
