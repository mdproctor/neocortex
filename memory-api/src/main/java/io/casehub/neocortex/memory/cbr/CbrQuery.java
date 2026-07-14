package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.fusion.FusionStrategy;
import io.casehub.neocortex.memory.MemoryDomain;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record CbrQuery(
        String tenantId,
        MemoryDomain domain,
        String caseType,
        Map<String, FeatureValue> features,
        Map<String, CbrFilter> filters,
        Map<String, Double> weights,
        int topK,
        double minSimilarity,
        Instant notBefore,
        String problem,
        double vectorWeight,
        RetrievalMode retrievalMode,
        FusionStrategy fusionStrategy,
        TemporalDecay temporalDecay
) {
    public CbrQuery {
        Objects.requireNonNull(tenantId, "tenantId required");
        Objects.requireNonNull(domain, "domain required");
        Objects.requireNonNull(caseType, "caseType required");
        Objects.requireNonNull(features, "features required");
        features = Map.copyOf(features);
        Objects.requireNonNull(filters, "filters required");
        filters = Map.copyOf(filters);
        Objects.requireNonNull(weights, "weights required");
        weights = Map.copyOf(weights);
        if (topK < 1) {throw new IllegalArgumentException("topK must be >= 1, got: " + topK);}
        if (minSimilarity < 0.0 || minSimilarity > 1.0) {
            throw new IllegalArgumentException("minSimilarity must be in [0,1], got: " + minSimilarity);
        }
        if (vectorWeight < 0.0 || vectorWeight > 1.0) {
            throw new IllegalArgumentException("vectorWeight must be in [0,1], got: " + vectorWeight);
        }
        for (Map.Entry<String, Double> w : weights.entrySet()) {
            if (w.getValue() < 0) {
                throw new IllegalArgumentException("weight for '" + w.getKey() + "' must be non-negative");
            }
        }
        if (problem != null && problem.isBlank()) {
            throw new IllegalArgumentException("problem must not be blank when provided");
        }
        Objects.requireNonNull(retrievalMode, "retrievalMode required");
        Objects.requireNonNull(fusionStrategy, "fusionStrategy required");
    }

    public static CbrQuery of(String tenantId, MemoryDomain domain,
                              String caseType, Map<String, FeatureValue> features, int topK) {
        return new CbrQuery(tenantId, domain, caseType, features, Map.of(), Map.of(), topK,
                            0.0, null, null, 0.5, RetrievalMode.HYBRID, FusionStrategy.RRF, null);
    }

    public CbrQuery withProblem(String problem) {
        return new CbrQuery(tenantId, domain, caseType, features, filters, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy, temporalDecay);
    }

    public CbrQuery withMinSimilarity(double minSimilarity) {
        return new CbrQuery(tenantId, domain, caseType, features, filters, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy, temporalDecay);
    }

    public CbrQuery withNotBefore(Instant notBefore) {
        return new CbrQuery(tenantId, domain, caseType, features, filters, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy, temporalDecay);
    }

    public CbrQuery withWeights(Map<String, Double> weights) {
        return new CbrQuery(tenantId, domain, caseType, features, filters, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy, temporalDecay);
    }

    public CbrQuery withWeight(String field, double weight) {
        Map<String, Double> newWeights = new java.util.HashMap<>(weights);
        newWeights.put(field, weight);
        return withWeights(newWeights);
    }

    public CbrQuery withVectorWeight(double vectorWeight) {
        return new CbrQuery(tenantId, domain, caseType, features, filters, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy, temporalDecay);
    }

    public CbrQuery withRetrievalMode(RetrievalMode retrievalMode) {
        return new CbrQuery(tenantId, domain, caseType, features, filters, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy, temporalDecay);
    }

    public CbrQuery withFusionStrategy(FusionStrategy fusionStrategy) {
        return new CbrQuery(tenantId, domain, caseType, features, filters, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy, temporalDecay);
    }

    public CbrQuery withFilter(String field, CbrFilter filter) {
        Map<String, CbrFilter> newFilters = new java.util.HashMap<>(filters);
        newFilters.put(field, filter);
        return withFilters(newFilters);
    }

    public CbrQuery withFilters(Map<String, CbrFilter> filters) {
        return new CbrQuery(tenantId, domain, caseType, features, filters, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy, temporalDecay);
    }

    public CbrQuery withTemporalDecay(TemporalDecay temporalDecay) {
        return new CbrQuery(tenantId, domain, caseType, features, filters, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy, temporalDecay);
    }
}
