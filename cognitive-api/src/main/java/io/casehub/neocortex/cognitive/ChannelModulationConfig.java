package io.casehub.neocortex.cognitive;

/**
 * Per-agent tuning for how curiosity channel state modulates search budget.
 * Each field controls the magnitude of one channel→budget modulation rule.
 * Magnitudes are additive offsets applied after base drive computation.
 *
 * @param specificDepthBoost                Berlyne SPECIFIC focus × gapProximity → +depth
 * @param specificBreadthPenalty            Berlyne SPECIFIC focus × gapProximity → −breadth
 * @param diversiveBreadthBoost             Berlyne DIVERSIVE focus → +breadth
 * @param diversiveLateralityBoost          Berlyne DIVERSIVE focus → +laterality
 * @param diversiveConvergencePenalty       Berlyne DIVERSIVE focus → −convergence
 * @param deprivationConvergenceBoost       Litman D_TYPE hedonic → +convergence
 * @param lowComprehensionLateralityBoost   Silvia (1−comprehension) → +laterality
 * @param highComprehensionConvergenceBoost Silvia comprehension → +convergence
 */
public record ChannelModulationConfig(
        double specificDepthBoost,
        double specificBreadthPenalty,
        double diversiveBreadthBoost,
        double diversiveLateralityBoost,
        double diversiveConvergencePenalty,
        double deprivationConvergenceBoost,
        double lowComprehensionLateralityBoost,
        double highComprehensionConvergenceBoost) {

    /**
     * Magnitudes calibrated empirically — small values (0.08–0.12) produce subtle
     * channel-specific modulation without dominating the base search budget.
     * Personality-specific values derived by CDE.
     */
    public static ChannelModulationConfig defaults() {
        return new ChannelModulationConfig(0.12, 0.08, 0.12, 0.10, 0.08, 0.10, 0.10, 0.10);
    }
}
