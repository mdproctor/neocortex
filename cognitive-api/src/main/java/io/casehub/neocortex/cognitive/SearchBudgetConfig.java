package io.casehub.neocortex.cognitive;

/**
 * Per-agent tuning for search budget activation functions.
 * <p>
 * Each of the four search budget parameters (breadth, laterality, depth,
 * convergence) has a parameterised logistic activation function
 * {@code 1/(1+exp(-k*(x-x0)))} where k controls steepness and x0 the
 * midpoint. This generalises CapsSettler's bare logistic (equivalent to
 * k=1, x0=0) following Mischel & Shoda (1995).
 * <p>
 * Default steepness k=6.0 calibrated from CapsSettler logistic range.
 * Default midpoint x0=0.5 from {@code AppraisalThresholds.driveActivationThreshold}.
 * Depth uses lower k=3.0 for near-linear mid-range — Conscientiousness-driven
 * systematic search is more linearly graded with effort.
 *
 * @param lateralityThreatFloor threat intensity above which laterality clamps
 *                              to 0 (Frijda, 1986 approach/avoidance)
 * @param pathologicalThreshold anxiousness + habituation failure sum above which
 *                              convergence collapses to 0 (Davis & Nolen-Hoeksema, 1998)
 * @param channelModulation     nullable curiosity channel modulation magnitudes;
 *                              null disables channel-based search budget modulation
 * @param extremePeak           motivation intensity above which inverted-U decline
 *                              and modulation scaling activate (Yerkes-Dodson)
 * @param extremeInteraction    nullable per-parameter decay and amplification rates;
 *                              null disables inverted-U and modulation scaling
 * @param fatigueEffects               nullable per-parameter fatigue reallocation effects
 *                              (Hockey 1997); null disables fatigue dynamics
 * @see io.casehub.neocortex.cognitive.HabituationConfig
 */
public record SearchBudgetConfig(
        double breadthSteepness,
        double breadthMidpoint,
        double lateralitySteepness,
        double lateralityMidpoint,
        double lateralityThreatFloor,
        double depthSteepness,
        double depthMidpoint,
        double convergenceSteepness,
        double convergenceMidpoint,
        double pathologicalThreshold,
        ChannelModulationConfig channelModulation,
        double extremePeak,
        ExtremeInteractionConfig extremeInteraction,
        SearchFatigueEffects fatigueEffects) {

    public SearchBudgetConfig {
        validateSteepness("breadthSteepness", breadthSteepness);
        validateSteepness("lateralitySteepness", lateralitySteepness);
        validateSteepness("depthSteepness", depthSteepness);
        validateSteepness("convergenceSteepness", convergenceSteepness);
        validateUnit("breadthMidpoint", breadthMidpoint);
        validateUnit("lateralityMidpoint", lateralityMidpoint);
        validateUnit("depthMidpoint", depthMidpoint);
        validateUnit("convergenceMidpoint", convergenceMidpoint);
        validateUnit("lateralityThreatFloor", lateralityThreatFloor);
        if (pathologicalThreshold < 0.0) {
            throw new IllegalArgumentException("pathologicalThreshold must be >= 0, got " + pathologicalThreshold);
        }
        if (extremePeak < 0.0 || extremePeak > 1.0) {
            throw new IllegalArgumentException("extremePeak must be in [0,1], got " + extremePeak);
        }
    }

    /**
     * Balanced defaults for a moderate personality profile.
     * <p>
     * Steepness k=6.0 for breadth/laterality/convergence — calibrated from CapsSettler logistic range.
     * Depth uses k=3.0 for near-linear mid-range. All midpoints 0.5 from AppraisalThresholds.
     * lateralityThreatFloor 0.7 — Frijda (1986) approach/avoidance, calibrated empirically.
     * pathologicalThreshold 1.4 — Davis &amp; Nolen-Hoeksema (1998), calibrated to trigger when
     * combined anxiousness + habituation failure indicate perseverative processing.
     * extremePeak 0.85 — Yerkes-Dodson inverted-U, calibrated empirically;
     * personality-specific peaks derived by CDE.
     */
    public static SearchBudgetConfig defaults() {
        return new SearchBudgetConfig(6.0, 0.5, 6.0, 0.5, 0.7, 3.0, 0.5, 6.0, 0.5, 1.4, null, 0.85, null, null);
    }

    public SearchBudgetConfig withExtremePeak(double v) {
        return new SearchBudgetConfig(breadthSteepness, breadthMidpoint, lateralitySteepness, lateralityMidpoint, lateralityThreatFloor, depthSteepness, depthMidpoint, convergenceSteepness, convergenceMidpoint, pathologicalThreshold, channelModulation, v, extremeInteraction, fatigueEffects);
    }

    public SearchBudgetConfig withExtremeInteraction(ExtremeInteractionConfig v) {
        return new SearchBudgetConfig(breadthSteepness, breadthMidpoint, lateralitySteepness, lateralityMidpoint, lateralityThreatFloor, depthSteepness, depthMidpoint, convergenceSteepness, convergenceMidpoint, pathologicalThreshold, channelModulation, extremePeak, v, fatigueEffects);
    }

    public SearchBudgetConfig withFatigueEffects(SearchFatigueEffects v) {
        return new SearchBudgetConfig(breadthSteepness, breadthMidpoint, lateralitySteepness, lateralityMidpoint, lateralityThreatFloor, depthSteepness, depthMidpoint, convergenceSteepness, convergenceMidpoint, pathologicalThreshold, channelModulation, extremePeak, extremeInteraction, v);
    }

    private static void validateSteepness(String name, double value) {
        if (value <= 0.0) {throw new IllegalArgumentException(name + " must be > 0, got " + value);}
    }

    private static void validateUnit(String name, double value) {
        if (value < 0.0 || value > 1.0) {throw new IllegalArgumentException(name + " must be in [0,1], got " + value);}
    }
}
