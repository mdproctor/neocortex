package io.casehub.neocortex.memory.cbr;

import java.util.Map;
import java.util.Objects;

public record CbrSuggestions(
        Map<String, FeatureStatistics> featureStats,
        double historicalSuccessRate,
        int experienceCount,
        double averageSimilarity) {

    public static final CbrSuggestions EMPTY = new CbrSuggestions(Map.of(), 0.0, 0, 0.0);

    public CbrSuggestions {
        Objects.requireNonNull(featureStats, "featureStats");
        featureStats = Map.copyOf(featureStats);
    }

    public boolean isEmpty() {
        return experienceCount == 0;
    }
}
