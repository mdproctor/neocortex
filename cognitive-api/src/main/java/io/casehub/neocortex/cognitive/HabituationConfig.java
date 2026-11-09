package io.casehub.neocortex.cognitive;

import java.util.Map;

/**
 * Per-agent tuning for habituation dynamics within the Scherer SEC appraisal
 * pipeline. Controls how quickly observations become familiar, the threshold
 * for novelty detection, and tolerance for repetitive stimuli.
 * <p>
 * Derived by CDE from PID-5 facets: Risk Taking → habituationRate (bold agents
 * habituate faster), riskAppetite → noveltyThreshold, ruleFollowing → repetitionTolerance.
 * Default values calibrated empirically.
 *
 * @param habituationRate     rate at which observation count reduces novelty score (calibrated empirically)
 * @param noveltyThreshold    novelty score below which an observation is considered habituated (calibrated empirically)
 * @param repetitionTolerance observation count above which habituation failure is flagged (calibrated empirically)
 * @param domainModulation    per-domain habituation rate multipliers; empty map applies uniform rate
 */
public record HabituationConfig(
        double habituationRate,
        double noveltyThreshold,
        double repetitionTolerance,
        Map<String, Double> domainModulation) {
    public HabituationConfig {
        if (domainModulation == null) domainModulation = Map.of();
        else domainModulation = Map.copyOf(domainModulation);
    }

    public static HabituationConfig defaults() {
        return new HabituationConfig(0.2, 0.3, 5.0, Map.of());
    }
}
