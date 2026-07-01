package io.casehub.neocortex.memory.cbr;

import java.util.Map;
import java.util.Objects;

public record FeatureVectorCbrCase(String problem, String solution,
                                    String outcome, Double confidence,
                                    Map<String, Object> features) implements CbrCase {
    public static final String CBR_TYPE = "feature-vector";
    @Override public String cbrType() { return CBR_TYPE; }
    public FeatureVectorCbrCase {
        Objects.requireNonNull(problem, "problem required");
        if (problem.isBlank()) throw new IllegalArgumentException("problem must not be blank");
        Objects.requireNonNull(solution, "solution required");
        if (solution.isBlank()) throw new IllegalArgumentException("solution must not be blank");
        if (confidence != null && (confidence < 0.0 || confidence > 1.0))
            throw new IllegalArgumentException("confidence must be in [0,1], got: " + confidence);
        Objects.requireNonNull(features, "features required");
        features = Map.copyOf(features);
    }
}
