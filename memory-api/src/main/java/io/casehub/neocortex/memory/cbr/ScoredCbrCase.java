package io.casehub.neocortex.memory.cbr;

import java.util.Objects;

/**
 * @param cbrCase the matched case
 * @param score similarity score in [-1, 1] — 1.0 for filter-only matches,
 *              cosine similarity for dense vector search results
 */
public record ScoredCbrCase<C extends CbrCase>(C cbrCase, double score) {
    public ScoredCbrCase {
        Objects.requireNonNull(cbrCase, "cbrCase required");
        if (!(score >= -1.0 && score <= 1.0))
            throw new IllegalArgumentException("score must be in [-1,1], got: " + score);
    }
}
