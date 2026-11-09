package io.casehub.neocortex.cognitive;

import java.time.Duration;
import java.util.Map;

/**
 * Per-agent tuning for recurring thought continuum classification.
 * Controls the thresholds at which recurring thought transitions from
 * healthy curiosity through fixation and obsession to rumination.
 * <p>
 * Grounded in Nolen-Hoeksema (1991) response styles theory of rumination,
 * Ehring &amp; Watkins (2008) transdiagnostic model of repetitive negative
 * thinking, and Eysenck (1967) neuroticism × negative affect interaction.
 * Thresholds derived by CDE from PID-5 facets; default values calibrated
 * empirically.
 *
 * @param symptomWeights      per-symptom score multipliers; empty map uses equal weights.
 *                            Seven symptoms: thoughtRecurrence, habituationFailure,
 *                            gapClosureResistance, attentionalCapture, arousalTrajectory,
 *                            valence, actionTendency
 * @param fixationThreshold   composite score above which position is FIXATION (calibrated empirically)
 * @param obsessionThreshold  composite score above which position is OBSESSION (calibrated empirically)
 * @param ruminationThreshold composite score above which position is RUMINATION (calibrated empirically)
 * @param recurrenceWindow    temporal window for experience frequency scoring (calibrated empirically)
 * @param minObservations     minimum observations before scoring activates (calibrated empirically)
 * @see io.casehub.neocortex.mindmap.RecurringThoughtAssessment
 * @see io.casehub.neocortex.mindmap.ContinuumPosition
 */
public record RecurringThoughtConfig(
        Map<String, Double> symptomWeights,
        double fixationThreshold,
        double obsessionThreshold,
        double ruminationThreshold,
        Duration recurrenceWindow,
        int minObservations) {

    public RecurringThoughtConfig {
        if (symptomWeights == null) symptomWeights = Map.of();
        else symptomWeights = Map.copyOf(symptomWeights);
    }

    public static RecurringThoughtConfig defaults() {
        return new RecurringThoughtConfig(
                Map.of(),
                0.3, 0.6, 0.8,
                Duration.ofDays(7),
                3);
    }
}
