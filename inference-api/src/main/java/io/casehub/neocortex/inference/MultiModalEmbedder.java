package io.casehub.neocortex.inference;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    default MultiModalEmbedding embed(Map<EmbeddingMode, String> textsByMode) {
        Objects.requireNonNull(textsByMode.get(EmbeddingMode.DENSE), "DENSE text is required");
        Map<String, MultiModalEmbedding> cache = new LinkedHashMap<>();
        for (String text : textsByMode.values()) {
            cache.computeIfAbsent(text, this::embed);
        }
        float[] dense       = cache.get(textsByMode.get(EmbeddingMode.DENSE)).dense();
        var     sparseText  = textsByMode.get(EmbeddingMode.SPARSE);
        var     colbertText = textsByMode.get(EmbeddingMode.COLBERT);
        return new MultiModalEmbedding(
                dense,
                sparseText != null ? cache.get(sparseText).sparse() : null,
                colbertText != null ? cache.get(colbertText).colbert() : null);
    }

    default MultiModalEmbedding embedSeparate(String denseText, String nonDenseText) {
        Objects.requireNonNull(denseText, "denseText must not be null");
        Objects.requireNonNull(nonDenseText, "nonDenseText must not be null");
        if (denseText.equals(nonDenseText)) {return embed(denseText);}
        Map<EmbeddingMode, String> map = new EnumMap<>(EmbeddingMode.class);
        map.put(EmbeddingMode.DENSE, denseText);
        for (EmbeddingMode mode : supportedModes()) {
            if (mode != EmbeddingMode.DENSE) {
                map.put(mode, nonDenseText);
            }
        }
        return embed(map);
    }


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

    /**
     * @return Maximum token sequence length — bounds ColBERT output rows per point
     */
    int maxSequenceLength();
}
