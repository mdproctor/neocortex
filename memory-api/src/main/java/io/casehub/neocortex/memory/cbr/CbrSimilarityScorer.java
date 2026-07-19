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

    public record SimilarityBreakdown(double score, Map<String, Double> featureSimilarities) {
        public SimilarityBreakdown {
            featureSimilarities = Map.copyOf(featureSimilarities);
        }
    }

    public static double score(Map<String, FeatureValue> queryFeatures,
                               Map<String, FeatureValue> caseFeatures,
                               Map<String, Double> weights,
                               CbrFeatureSchema schema) {
        return score(queryFeatures, caseFeatures, weights, schema, Map.of());
    }

    public static double score(Map<String, FeatureValue> queryFeatures,
                               Map<String, FeatureValue> caseFeatures,
                               Map<String, Double> weights,
                               CbrFeatureSchema schema,
                               Map<String, LocalSimilarityFunction> overrides) {
        return scoreDetailed(queryFeatures, caseFeatures, weights, schema, overrides).score();
    }

    public static SimilarityBreakdown scoreDetailed(Map<String, FeatureValue> queryFeatures,
                                                    Map<String, FeatureValue> caseFeatures,
                                                    Map<String, Double> weights,
                                                    CbrFeatureSchema schema,
                                                    Map<String, LocalSimilarityFunction> overrides) {
        Objects.requireNonNull(overrides, "overrides");
        if (queryFeatures.isEmpty()) {return new SimilarityBreakdown(1.0, Map.of());}
        if (schema == null) {return new SimilarityBreakdown(1.0, Map.of());}

        double              weightedSum      = 0.0;
        double              totalWeight      = 0.0;
        Map<String, Double> rawContributions = new java.util.LinkedHashMap<>();

        for (Map.Entry<String, FeatureValue> entry : queryFeatures.entrySet()) {
            FeatureField field = findField(schema, entry.getKey());
            if (field == null) {continue;}
            LocalSimilarityFunction override = overrides.get(entry.getKey());
            if (override == null
                && (field instanceof FeatureField.CategoricalList
                    || field instanceof FeatureField.NumericList
                    || field instanceof FeatureField.NestedObject
                    || field instanceof FeatureField.ObjectList)) {continue;}

            double       weight    = weights.getOrDefault(entry.getKey(), 1.0);
            FeatureValue caseValue = caseFeatures.get(entry.getKey());
            double localSim = caseValue == null ? 0.0
                                                : localSimilarity(field, entry.getValue(), caseValue, overrides);

            double contribution = weight * localSim;
            weightedSum += contribution;
            totalWeight += weight;
            rawContributions.put(entry.getKey(), contribution);
        }

        double              score       = totalWeight > 0 ? weightedSum / totalWeight : 1.0;
        Map<String, Double> featureSims = new java.util.LinkedHashMap<>();
        if (totalWeight > 0) {
            for (var e : rawContributions.entrySet()) {
                featureSims.put(e.getKey(), e.getValue() / totalWeight);
            }
        }
        return new SimilarityBreakdown(score, featureSims);
    }

    public static SimilarityBreakdown scoreDetailed(Map<String, FeatureValue> queryFeatures,
                                                    Map<String, FeatureValue> caseFeatures,
                                                    Map<String, Double> weights,
                                                    CbrFeatureSchema schema,
                                                    Map<String, LocalSimilarityFunction> overrides,
                                                    double dtwAbandonCostThreshold) {
        Objects.requireNonNull(overrides, "overrides");
        if (queryFeatures.isEmpty()) {return new SimilarityBreakdown(1.0, Map.of());}
        if (schema == null) {return new SimilarityBreakdown(1.0, Map.of());}

        double              weightedSum      = 0.0;
        double              totalWeight      = 0.0;
        Map<String, Double> rawContributions = new java.util.LinkedHashMap<>();

        for (Map.Entry<String, FeatureValue> entry : queryFeatures.entrySet()) {
            FeatureField field = findField(schema, entry.getKey());
            if (field == null) {continue;}
            LocalSimilarityFunction override2 = overrides.get(entry.getKey());
            if (override2 == null
                && (field instanceof FeatureField.CategoricalList
                    || field instanceof FeatureField.NumericList
                    || field instanceof FeatureField.NestedObject
                    || field instanceof FeatureField.ObjectList)) {continue;}

            double       weight    = weights.getOrDefault(entry.getKey(), 1.0);
            FeatureValue caseValue = caseFeatures.get(entry.getKey());
            double localSim = caseValue == null ? 0.0
                                                : localSimilarity(field, entry.getValue(), caseValue, overrides, dtwAbandonCostThreshold);

            double contribution = weight * localSim;
            weightedSum += contribution;
            totalWeight += weight;
            rawContributions.put(entry.getKey(), contribution);
        }

        double              score       = totalWeight > 0 ? weightedSum / totalWeight : 1.0;
        Map<String, Double> featureSims = new java.util.LinkedHashMap<>();
        if (totalWeight > 0) {
            for (var e : rawContributions.entrySet()) {
                featureSims.put(e.getKey(), e.getValue() / totalWeight);
            }
        }
        return new SimilarityBreakdown(score, featureSims);
    }


    private static double localSimilarity(FeatureField field, FeatureValue queryVal, FeatureValue caseVal,
                                          Map<String, LocalSimilarityFunction> overrides) {
        return localSimilarity(field, queryVal, caseVal, overrides, Double.POSITIVE_INFINITY);
    }

    private static double localSimilarity(FeatureField field, FeatureValue queryVal, FeatureValue caseVal,
                                          Map<String, LocalSimilarityFunction> overrides,
                                          double dtwAbandonCostThreshold) {
        LocalSimilarityFunction override = overrides.get(field.name());
        if (override != null) {return override.compute(queryVal, caseVal);}

        return switch (field) {
            case FeatureField.Numeric n -> numericSimilarity(n, queryVal, caseVal);
            case FeatureField.Categorical c -> categoricalSimilarity(c, queryVal, caseVal);
            case FeatureField.Text t -> queryVal.equals(caseVal) ? 1.0 : 0.0;
            case FeatureField.CategoricalList cl -> 0.0;
            case FeatureField.NumericList nl -> 0.0;
            case FeatureField.NestedObject no -> 0.0;
            case FeatureField.ObjectList ol -> 0.0;
            case FeatureField.TimeSeries ts -> dtwSimilarity(ts, queryVal, caseVal, dtwAbandonCostThreshold);
            case FeatureField.DiscreteSequence ds -> editDistanceSimilarity(ds, queryVal, caseVal);
        };
    }

    private static double categoricalSimilarity(FeatureField.Categorical field,
                                                FeatureValue queryVal, FeatureValue caseVal) {
        if (field.similaritySpec() == null) {return queryVal.equals(caseVal) ? 1.0 : 0.0;}
        return switch (field.similaritySpec()) {
            case SimilaritySpec.CategoricalTable ct -> {
                if (queryVal instanceof FeatureValue.StringVal qs && caseVal instanceof FeatureValue.StringVal cs) {
                    if (qs.value().equals(cs.value())) {yield 1.0;}
                    yield ct.similarities().getOrDefault(qs.value(), Map.of()).getOrDefault(cs.value(), 0.0);
                }
                yield 0.0;
            }
            case SimilaritySpec.GaussianDecay gd -> throw new IllegalStateException(
                    "Unexpected spec on Categorical: " + field.similaritySpec());
            case SimilaritySpec.StepDecay sd -> throw new IllegalStateException(
                    "Unexpected spec on Categorical: " + field.similaritySpec());
            case SimilaritySpec.ExponentialDecay ed -> throw new IllegalStateException(
                    "Unexpected spec on Categorical: " + field.similaritySpec());
            case SimilaritySpec.DtwSpec ds -> throw new IllegalStateException(
                    "Unexpected spec on Categorical: " + field.similaritySpec());
            case SimilaritySpec.EditDistanceSpec es -> throw new IllegalStateException(
                    "Unexpected spec on Categorical: " + field.similaritySpec());
        };
    }

    private static double numericSimilarity(FeatureField.Numeric field,
                                            FeatureValue queryVal, FeatureValue caseVal) {
        double range = field.max() - field.min();
        if (range <= 0) {return queryVal.equals(caseVal) ? 1.0 : 0.0;}

        double normalizedDistance = computeNormalizedDistance(field, queryVal, caseVal);

        if (field.similaritySpec() == null) {
            return Math.max(0.0, 1.0 - normalizedDistance);
        }
        return switch (field.similaritySpec()) {
            case SimilaritySpec.GaussianDecay gd -> Math.exp(-normalizedDistance * normalizedDistance / (2 * gd.sigma() * gd.sigma()));
            case SimilaritySpec.StepDecay sd -> normalizedDistance <= sd.tolerance() ? 1.0 : 0.0;
            case SimilaritySpec.ExponentialDecay ed -> Math.exp(-ed.decayRate() * normalizedDistance);
            case SimilaritySpec.CategoricalTable ct -> throw new IllegalStateException(
                    "Unexpected spec on Numeric: " + field.similaritySpec());
            case SimilaritySpec.DtwSpec ds -> throw new IllegalStateException(
                    "Unexpected spec on Numeric: " + field.similaritySpec());
            case SimilaritySpec.EditDistanceSpec es -> throw new IllegalStateException(
                    "Unexpected spec on Numeric: " + field.similaritySpec());
        };
    }

    private static double computeNormalizedDistance(FeatureField.Numeric field,
                                                    FeatureValue queryVal, FeatureValue caseVal) {
        double range = field.max() - field.min();
        if (!(caseVal instanceof FeatureValue.NumberVal cn)) {return 1.0;}
        double caseNum = cn.value();

        if (queryVal instanceof FeatureValue.RangeVal rv) {
            if (caseNum >= rv.min() && caseNum <= rv.max()) {return 0.0;}
            double dist = caseNum < rv.min() ? rv.min() - caseNum : caseNum - rv.max();
            return dist / range;
        }

        if (queryVal instanceof FeatureValue.NumberVal qn) {
            return Math.abs(qn.value() - caseNum) / range;
        }
        return 1.0;
    }

    private static double dtwSimilarity(FeatureField.TimeSeries ts,
                                        FeatureValue queryVal, FeatureValue caseVal,
                                        double abandonCostThreshold) {
        WarpingConstraint constraint = ts.similaritySpec() instanceof SimilaritySpec.DtwSpec ds
                                       ? ds.constraint() : new WarpingConstraint.Unconstrained();
        if (queryVal instanceof FeatureValue.StructListVal qObs
            && caseVal instanceof FeatureValue.StructListVal cObs) {
            return DtwSimilarity.compute(qObs.items(), cObs.items(), ts, constraint,
                                         abandonCostThreshold).score();
        }
        return 0.0;
    }

    private static double editDistanceSimilarity(FeatureField.DiscreteSequence ds,
                                                 FeatureValue queryVal, FeatureValue caseVal) {
        java.util.Map<String, java.util.Map<String, Double>> subSim     = null;
        Double                                               insertCost = null;
        Double                                               deleteCost = null;
        if (ds.similaritySpec() instanceof SimilaritySpec.EditDistanceSpec es) {
            subSim     = es.substitutionSimilarities();
            insertCost = es.insertCost();
            deleteCost = es.deleteCost();
        }
        if (queryVal instanceof FeatureValue.StringListVal qSeq
            && caseVal instanceof FeatureValue.StringListVal cSeq) {
            return EditDistanceSimilarity.compute(qSeq.values(), cSeq.values(), subSim, insertCost, deleteCost).score();
        }
        return 0.0;
    }

    private static FeatureField findField(CbrFeatureSchema schema, String name) {
        for (FeatureField f : schema.fields()) {
            if (f.name().equals(name)) return f;
        }
        return null;
    }
}
