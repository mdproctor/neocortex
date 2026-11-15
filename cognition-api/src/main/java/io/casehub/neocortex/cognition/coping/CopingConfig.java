package io.casehub.neocortex.cognition.coping;

public record CopingConfig(
    double copingOpportunityThreshold,
    int copingMaxIterations
) {
    public CopingConfig {
        if (copingOpportunityThreshold < 0 || copingOpportunityThreshold > 1.0)
            throw new IllegalArgumentException("copingOpportunityThreshold must be in [0, 1]");
        if (copingMaxIterations < 1)
            throw new IllegalArgumentException("copingMaxIterations must be >= 1");
    }

    public static CopingConfig defaults() {
        return new CopingConfig(0.6, 3);
    }
}
