package io.casehub.neocortex.memory.cbr;

/**
 * Computes local similarity between a query feature value and a case feature value.
 *
 * <p>Implementations return a score in [0, 1], where:
 * <ul>
 *   <li>1.0 — perfect match (most similar)</li>
 *   <li>0.0 — no match (completely dissimilar)</li>
 * </ul>
 *
 * <p>Pure Java, Tier 1 — zero external dependencies.
 */
@FunctionalInterface
public interface LocalSimilarityFunction {

    double compute(FeatureValue queryValue, FeatureValue caseValue);

    LocalSimilarityFunction EXACT_MATCH = (q, c) -> q.equals(c) ? 1.0 : 0.0;
}
