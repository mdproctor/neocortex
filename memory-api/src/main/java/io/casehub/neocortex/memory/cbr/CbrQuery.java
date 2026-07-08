package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record CbrQuery(
    String tenantId,
    MemoryDomain domain,
    String caseType,
    Map<String, Object> features,
    Map<String, Double> weights,
    int topK,
    double minSimilarity,
    Instant notBefore,
    String problem,
    double vectorWeight,
    RetrievalMode retrievalMode,
    CbrFusionStrategy fusionStrategy
) {
    public CbrQuery {
        Objects.requireNonNull(tenantId, "tenantId required");
        Objects.requireNonNull(domain, "domain required");
        Objects.requireNonNull(caseType, "caseType required");
        Objects.requireNonNull(features, "features required");
        features = Map.copyOf(features);
        Objects.requireNonNull(weights, "weights required");
        weights = Map.copyOf(weights);
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1, got: " + topK);
        if (minSimilarity < 0.0 || minSimilarity > 1.0)
            throw new IllegalArgumentException("minSimilarity must be in [0,1], got: " + minSimilarity);
        if (vectorWeight < 0.0 || vectorWeight > 1.0)
            throw new IllegalArgumentException("vectorWeight must be in [0,1], got: " + vectorWeight);
        for (Map.Entry<String, Double> w : weights.entrySet()) {
            if (w.getValue() < 0)
                throw new IllegalArgumentException("weight for '" + w.getKey() + "' must be non-negative");
        }
        if (problem != null && problem.isBlank())
            throw new IllegalArgumentException("problem must not be blank when provided");
        Objects.requireNonNull(retrievalMode, "retrievalMode required");
        Objects.requireNonNull(fusionStrategy, "fusionStrategy required");
    }

    public static CbrQuery of(String tenantId, MemoryDomain domain,
                               String caseType, Map<String, Object> features, int topK) {
        return new CbrQuery(tenantId, domain, caseType, features, Map.of(), topK,
                            0.0, null, null, 0.5, RetrievalMode.HYBRID, CbrFusionStrategy.RRF);
    }

    public CbrQuery withProblem(String problem) {
        return new CbrQuery(tenantId, domain, caseType, features, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy);
    }

    public CbrQuery withMinSimilarity(double minSimilarity) {
        return new CbrQuery(tenantId, domain, caseType, features, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy);
    }

    public CbrQuery withNotBefore(Instant notBefore) {
        return new CbrQuery(tenantId, domain, caseType, features, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy);
    }

    public CbrQuery withWeights(Map<String, Double> weights) {
        return new CbrQuery(tenantId, domain, caseType, features, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy);
    }

    public CbrQuery withWeight(String field, double weight) {
        Map<String, Double> newWeights = new java.util.HashMap<>(weights);
        newWeights.put(field, weight);
        return withWeights(newWeights);
    }

    public CbrQuery withVectorWeight(double vectorWeight) {
        return new CbrQuery(tenantId, domain, caseType, features, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy);
    }

    public CbrQuery withRetrievalMode(RetrievalMode retrievalMode) {
        return new CbrQuery(tenantId, domain, caseType, features, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy);
    }

    public CbrQuery withFusionStrategy(CbrFusionStrategy fusionStrategy) {
        return new CbrQuery(tenantId, domain, caseType, features, weights, topK,
                            minSimilarity, notBefore, problem, vectorWeight, retrievalMode, fusionStrategy);
    }
}
