package io.casehub.neocortex.memory.cbr;

import java.util.Map;
import java.util.Objects;

/**
 * Computes CBR similarity scores using per-field local similarity functions
 * and configurable per-field weights.
 *
 * <p>Local similarity functions use three-level precedence:
 * <ol>
 *   <li>Caller-provided override via {@code Map<String, LocalSimilarityFunction>}</li>
 *   <li>Field-attached {@link SimilaritySpec} (if present)</li>
 *   <li>Type default (exact match for Categorical/Text, linear decay for Numeric)</li>
 * </ol>
 *
 * <p>Type defaults:
 * <ul>
 *   <li>{@link FeatureField.Categorical} — exact match (1.0 or 0.0)</li>
 *   <li>{@link FeatureField.Numeric} — linear decay: {@code 1.0 - |query - case| / range},
 *       with {@link NumericRange} support (1.0 inside range, linear decay outside)</li>
 *   <li>{@link FeatureField.Text} — exact match (1.0 or 0.0)</li>
 * </ul>
 *
 * <p>Pure Java, Tier 1 — zero external dependencies.
 */
public final class CbrSimilarityScorer {

    private CbrSimilarityScorer() {}

    /**
     * Compute weighted similarity between query features and case features.
     *
     * @param queryFeatures the query's feature values
     * @param caseFeatures  the stored case's feature values
     * @param weights       per-field weights (default 1.0 for unspecified fields)
     * @param schema        the feature schema (null → return 1.0 for backward compat)
     * @return similarity in [0, 1]
     */
    public static double score(Map<String, Object> queryFeatures,
                                Map<String, Object> caseFeatures,
                                Map<String, Double> weights,
                                CbrFeatureSchema schema) {
        return score(queryFeatures, caseFeatures, weights, schema, Map.of());
    }

    /**
     * Compute weighted similarity between query features and case features with custom
     * local similarity functions for specific fields.
     *
     * @param queryFeatures the query's feature values
     * @param caseFeatures  the stored case's feature values
     * @param weights       per-field weights (default 1.0 for unspecified fields)
     * @param schema        the feature schema (null → return 1.0 for backward compat)
     * @param overrides     per-field custom similarity functions (empty → use default)
     * @return similarity in [0, 1]
     */
    public static double score(Map<String, Object> queryFeatures,
                                Map<String, Object> caseFeatures,
                                Map<String, Double> weights,
                                CbrFeatureSchema schema,
                                Map<String, LocalSimilarityFunction> overrides) {
        Objects.requireNonNull(overrides, "overrides");
        if (queryFeatures.isEmpty()) {return 1.0;}
        if (schema == null) {return 1.0;}

        double weightedSum = 0.0;
        double totalWeight = 0.0;

        for (Map.Entry<String, Object> entry : queryFeatures.entrySet()) {
            FeatureField field = findField(schema, entry.getKey());
            if (field == null) {continue;}
            if (field instanceof FeatureField.CategoricalList
                || field instanceof FeatureField.NestedObject
                || field instanceof FeatureField.ObjectList) {continue;}

            double weight    = weights.getOrDefault(entry.getKey(), 1.0);
            Object caseValue = caseFeatures.get(entry.getKey());
            double localSim = caseValue == null ? 0.0
                                                : localSimilarity(field, entry.getValue(), caseValue, overrides);

            weightedSum += weight * localSim;
            totalWeight += weight;
        }

        return totalWeight > 0 ? weightedSum / totalWeight : 1.0;}

    private static double localSimilarity(FeatureField field, Object queryVal, Object caseVal,
                                          Map<String, LocalSimilarityFunction> overrides) {
        LocalSimilarityFunction override = overrides.get(field.name());
        if (override != null) {return override.compute(queryVal, caseVal);}

        return switch (field) {
            case FeatureField.Numeric n -> numericSimilarity(n, queryVal, caseVal);
            case FeatureField.Categorical c -> categoricalSimilarity(c, queryVal, caseVal);
            case FeatureField.Text t -> queryVal.equals(caseVal) ? 1.0 : 0.0;
            case FeatureField.CategoricalList cl -> throw new IllegalStateException("Structured field in scorer");
            case FeatureField.NestedObject no -> throw new IllegalStateException("Structured field in scorer");
            case FeatureField.ObjectList ol -> throw new IllegalStateException("Structured field in scorer");
        };}

    private static double categoricalSimilarity(FeatureField.Categorical field,
                                                 Object queryVal, Object caseVal) {
        if (field.similaritySpec() == null) return queryVal.equals(caseVal) ? 1.0 : 0.0;
        return switch (field.similaritySpec()) {
            case SimilaritySpec.CategoricalTable ct -> {
                String q = (String) queryVal;
                String c = (String) caseVal;
                if (q.equals(c)) yield 1.0;
                yield ct.similarities().getOrDefault(q, Map.of()).getOrDefault(c, 0.0);
            }
            case SimilaritySpec.GaussianDecay gd -> throw new IllegalStateException(
                "Unexpected spec on Categorical: " + field.similaritySpec());
            case SimilaritySpec.StepDecay sd -> throw new IllegalStateException(
                "Unexpected spec on Categorical: " + field.similaritySpec());
            case SimilaritySpec.ExponentialDecay ed -> throw new IllegalStateException(
                "Unexpected spec on Categorical: " + field.similaritySpec());
        };
    }

    private static double numericSimilarity(FeatureField.Numeric field,
                                             Object queryVal, Object caseVal) {
        double range = field.max() - field.min();
        if (range <= 0) return queryVal.equals(caseVal) ? 1.0 : 0.0;

        double normalizedDistance = computeNormalizedDistance(field, queryVal, caseVal);

        if (field.similaritySpec() == null) {
            return Math.max(0.0, 1.0 - normalizedDistance);
        }
        return switch (field.similaritySpec()) {
            case SimilaritySpec.GaussianDecay gd ->
                Math.exp(-normalizedDistance * normalizedDistance / (2 * gd.sigma() * gd.sigma()));
            case SimilaritySpec.StepDecay sd ->
                normalizedDistance <= sd.tolerance() ? 1.0 : 0.0;
            case SimilaritySpec.ExponentialDecay ed ->
                Math.exp(-ed.decayRate() * normalizedDistance);
            case SimilaritySpec.CategoricalTable ct -> throw new IllegalStateException(
                "Unexpected spec on Numeric: " + field.similaritySpec());
        };
    }

    private static double computeNormalizedDistance(FeatureField.Numeric field,
                                                    Object queryVal, Object caseVal) {
        double range = field.max() - field.min();
        double caseNum = ((Number) caseVal).doubleValue();

        if (queryVal instanceof NumericRange nr) {
            if (caseNum >= nr.min() && caseNum <= nr.max()) return 0.0;
            double dist = caseNum < nr.min() ? nr.min() - caseNum : caseNum - nr.max();
            return dist / range;
        }

        double queryNum = ((Number) queryVal).doubleValue();
        return Math.abs(queryNum - caseNum) / range;
    }

    private static FeatureField findField(CbrFeatureSchema schema, String name) {
        for (FeatureField f : schema.fields()) {
            if (f.name().equals(name)) return f;
        }
        return null;
    }
}
