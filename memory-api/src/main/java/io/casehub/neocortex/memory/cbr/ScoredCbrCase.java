package io.casehub.neocortex.memory.cbr;

import java.util.Objects;

/**
 * @param cbrCase the matched case
 * @param score similarity score in [-1, 1] — 1.0 for filter-only matches,
 *              cosine similarity for dense vector search results
 */
public record ScoredCbrCase<C extends CbrCase>(C cbrCase, double score, boolean reranked) {
    public ScoredCbrCase {
        Objects.requireNonNull(cbrCase, "cbrCase required");
        if (!(score >= -1.0 && score <= 1.0))
            throw new IllegalArgumentException("score must be in [-1,1], got: " + score);
    }

    public ScoredCbrCase(C cbrCase, double score) {
        this(cbrCase, score, false);
    }

    public ScoredCbrCase<C> withReranked() {
        return new ScoredCbrCase<>(cbrCase, score, true);
    }
}
