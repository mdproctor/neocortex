package io.casehub.neocortex.cognitive;

/**
 * Per-agent tuning for how personality modulates search budget activation
 * at extreme motivation intensity.
 * <p>
 * Decay values control the rate of exponential decline past the parameter's
 * peak threshold. Zero decay means the parameter is protected (no decline).
 * Amplification rates scale drive modulations past the peak: positive amplifies,
 * negative compresses.
 * <p>
 * Each parameter can have its own peak threshold (Yerkes-Dodson: complex tasks
 * peak at lower arousal than simple tasks). When a per-parameter peak is null,
 * the global {@link SearchBudgetConfig#extremePeak()} is used as fallback.
 *
 * @param depthDecay               exponential decay rate for depth past peak; Yerkes-Dodson (calibrated empirically)
 * @param breadthDecay             exponential decay rate for breadth past peak; Easterbrook (1959) cue-utilization (calibrated empirically)
 * @param lateralityDecay          exponential decay rate for laterality past peak (calibrated empirically)
 * @param convergenceDecay         exponential decay rate for convergence past peak (calibrated empirically)
 * @param depthAmplification       drive modulation scaling rate for depth (calibrated empirically)
 * @param breadthAmplification     drive modulation scaling rate for breadth (calibrated empirically)
 * @param lateralityAmplification  drive modulation scaling rate for laterality (calibrated empirically)
 * @param convergenceAmplification drive modulation scaling rate for convergence (calibrated empirically)
 * @param depthPeak                per-parameter peak for depth, null = use global (calibrated empirically)
 * @param breadthPeak              per-parameter peak for breadth, null = use global (calibrated empirically)
 * @param lateralityPeak           per-parameter peak for laterality, null = use global (calibrated empirically)
 * @param convergencePeak          per-parameter peak for convergence, null = use global (calibrated empirically)
 * @param amplificationThreshold   intensity threshold where drive modulation scaling
 *                                 activates, null = use resolved per-parameter peak (calibrated empirically)
 */
public record ExtremeInteractionConfig(
        double depthDecay,
        double breadthDecay,
        double lateralityDecay,
        double convergenceDecay,
        double depthAmplification,
        double breadthAmplification,
        double lateralityAmplification,
        double convergenceAmplification,
        Double depthPeak,
        Double breadthPeak,
        Double lateralityPeak,
        Double convergencePeak,
        Double amplificationThreshold) {

    public ExtremeInteractionConfig {
        validateNonNegative("depthDecay", depthDecay);
        validateNonNegative("breadthDecay", breadthDecay);
        validateNonNegative("lateralityDecay", lateralityDecay);
        validateNonNegative("convergenceDecay", convergenceDecay);
        validatePeak("depthPeak", depthPeak);
        validatePeak("breadthPeak", breadthPeak);
        validatePeak("lateralityPeak", lateralityPeak);
        validatePeak("convergencePeak", convergencePeak);
        validatePeak("amplificationThreshold", amplificationThreshold);
    }

    /**
     * Approximates a moderate personality: conscientiousness ~0.5,
     * openness ~0.5, anxiousness ~0.3. All magnitudes calibrated empirically;
     * personality-specific values derived by CDE.
     * <p>
     * Decay values (Yerkes-Dodson / Easterbrook 1959 cue-utilization):
     * depth protected (0.0), breadth narrows moderately (1.5),
     * laterality narrows faster (2.0), convergence slightly compressed (0.5).
     * <p>
     * Amplification values (situational strength modulation, calibrated empirically):
     * depth modest boost (0.5), breadth compresses (-1.0),
     * laterality compresses (-0.8), convergence compresses strongly (-1.5).
     * All per-parameter peaks null (fall back to global extremePeak).
     */
    public static ExtremeInteractionConfig defaults() {
        return new ExtremeInteractionConfig(
                0.0, 1.5, 2.0, 0.5,
                0.5, -1.0, -0.8, -1.5,
                null, null, null, null, null);
    }

    public double depthPeakOrDefault(double fallback) {
        return depthPeak != null ? depthPeak : fallback;
    }

    public double breadthPeakOrDefault(double fallback) {
        return breadthPeak != null ? breadthPeak : fallback;
    }

    public double lateralityPeakOrDefault(double fallback) {
        return lateralityPeak != null ? lateralityPeak : fallback;
    }

    public double convergencePeakOrDefault(double fallback) {
        return convergencePeak != null ? convergencePeak : fallback;
    }

    public double amplificationThresholdOrDefault(double fallback) {
        return amplificationThreshold != null ? amplificationThreshold : fallback;
    }


    public ExtremeInteractionConfig withDepthDecay(double v) {
        return new ExtremeInteractionConfig(v, breadthDecay, lateralityDecay, convergenceDecay, depthAmplification, breadthAmplification, lateralityAmplification, convergenceAmplification, depthPeak, breadthPeak, lateralityPeak, convergencePeak, amplificationThreshold);
    }

    public ExtremeInteractionConfig withBreadthDecay(double v) {
        return new ExtremeInteractionConfig(depthDecay, v, lateralityDecay, convergenceDecay, depthAmplification, breadthAmplification, lateralityAmplification, convergenceAmplification, depthPeak, breadthPeak, lateralityPeak, convergencePeak, amplificationThreshold);
    }

    public ExtremeInteractionConfig withLateralityDecay(double v) {
        return new ExtremeInteractionConfig(depthDecay, breadthDecay, v, convergenceDecay, depthAmplification, breadthAmplification, lateralityAmplification, convergenceAmplification, depthPeak, breadthPeak, lateralityPeak, convergencePeak, amplificationThreshold);
    }

    public ExtremeInteractionConfig withConvergenceDecay(double v) {
        return new ExtremeInteractionConfig(depthDecay, breadthDecay, lateralityDecay, v, depthAmplification, breadthAmplification, lateralityAmplification, convergenceAmplification, depthPeak, breadthPeak, lateralityPeak, convergencePeak, amplificationThreshold);
    }

    public ExtremeInteractionConfig withDepthAmplification(double v) {
        return new ExtremeInteractionConfig(depthDecay, breadthDecay, lateralityDecay, convergenceDecay, v, breadthAmplification, lateralityAmplification, convergenceAmplification, depthPeak, breadthPeak, lateralityPeak, convergencePeak, amplificationThreshold);
    }

    public ExtremeInteractionConfig withBreadthAmplification(double v) {
        return new ExtremeInteractionConfig(depthDecay, breadthDecay, lateralityDecay, convergenceDecay, depthAmplification, v, lateralityAmplification, convergenceAmplification, depthPeak, breadthPeak, lateralityPeak, convergencePeak, amplificationThreshold);
    }

    public ExtremeInteractionConfig withLateralityAmplification(double v) {
        return new ExtremeInteractionConfig(depthDecay, breadthDecay, lateralityDecay, convergenceDecay, depthAmplification, breadthAmplification, v, convergenceAmplification, depthPeak, breadthPeak, lateralityPeak, convergencePeak, amplificationThreshold);
    }

    public ExtremeInteractionConfig withConvergenceAmplification(double v) {
        return new ExtremeInteractionConfig(depthDecay, breadthDecay, lateralityDecay, convergenceDecay, depthAmplification, breadthAmplification, lateralityAmplification, v, depthPeak, breadthPeak, lateralityPeak, convergencePeak, amplificationThreshold);
    }

    public ExtremeInteractionConfig withDepthPeak(Double v) {
        return new ExtremeInteractionConfig(depthDecay, breadthDecay, lateralityDecay, convergenceDecay, depthAmplification, breadthAmplification, lateralityAmplification, convergenceAmplification, v, breadthPeak, lateralityPeak, convergencePeak, amplificationThreshold);
    }

    public ExtremeInteractionConfig withBreadthPeak(Double v) {
        return new ExtremeInteractionConfig(depthDecay, breadthDecay, lateralityDecay, convergenceDecay, depthAmplification, breadthAmplification, lateralityAmplification, convergenceAmplification, depthPeak, v, lateralityPeak, convergencePeak, amplificationThreshold);
    }

    public ExtremeInteractionConfig withLateralityPeak(Double v) {
        return new ExtremeInteractionConfig(depthDecay, breadthDecay, lateralityDecay, convergenceDecay, depthAmplification, breadthAmplification, lateralityAmplification, convergenceAmplification, depthPeak, breadthPeak, v, convergencePeak, amplificationThreshold);
    }

    public ExtremeInteractionConfig withConvergencePeak(Double v) {
        return new ExtremeInteractionConfig(depthDecay, breadthDecay, lateralityDecay, convergenceDecay, depthAmplification, breadthAmplification, lateralityAmplification, convergenceAmplification, depthPeak, breadthPeak, lateralityPeak, v, amplificationThreshold);
    }

    public ExtremeInteractionConfig withAmplificationThreshold(Double v) {
        return new ExtremeInteractionConfig(depthDecay, breadthDecay, lateralityDecay, convergenceDecay, depthAmplification, breadthAmplification, lateralityAmplification, convergenceAmplification, depthPeak, breadthPeak, lateralityPeak, convergencePeak, v);
    }


    private static void validateNonNegative(String name, double value) {
        if (value < 0.0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + value);
        }
    }

    private static void validatePeak(String name, Double value) {
        if (value != null && (value < 0.0 || value > 1.0)) {
            throw new IllegalArgumentException(name + " must be in [0,1], got " + value);
        }
    }
}
