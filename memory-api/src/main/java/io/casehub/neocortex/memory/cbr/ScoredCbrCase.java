package io.casehub.neocortex.memory.cbr;

import java.util.Map;
import java.util.Objects;

/**
 * @param cbrCase             the matched case
 * @param caseId              storage-level identifier assigned at store() time, null when unavailable
 * @param score               similarity score in [-1, 1] — 1.0 for filter-only matches,
 *                            cosine similarity for dense vector search results
 * @param featureSimilarities per-feature similarity contributions (never null, empty when unavailable)
 */
public record ScoredCbrCase<C extends CbrCase>(C cbrCase, String caseId, double score, boolean reranked,
                                               Map<String, Double> featureSimilarities) {
    public ScoredCbrCase {
        Objects.requireNonNull(cbrCase, "cbrCase required");
        if (!(score >= -1.0 && score <= 1.0)) {
            throw new IllegalArgumentException("score must be in [-1,1], got: " + score);
        }
        featureSimilarities = featureSimilarities != null ? Map.copyOf(featureSimilarities) : Map.of();
    }

    public ScoredCbrCase(C cbrCase, String caseId, double score) {
        this(cbrCase, caseId, score, false, Map.of());
    }

    public ScoredCbrCase(C cbrCase, double score) {
        this(cbrCase, null, score, false, Map.of());
    }

    public ScoredCbrCase(C cbrCase, double score, boolean reranked) {
        this(cbrCase, null, score, reranked, Map.of());
    }


    public ScoredCbrCase(C cbrCase, double score, boolean reranked,
                         Map<String, Double> featureSimilarities) {
        this(cbrCase, null, score, reranked, featureSimilarities);
    }

    public ScoredCbrCase<C> withReranked() {
        return new ScoredCbrCase<>(cbrCase, caseId, score, true, featureSimilarities);
    }
}
