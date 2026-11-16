package io.casehub.neocortex.caps.engine;

/**
 * Configuration for CAPS network settling — attractor extraction and
 * distortion thresholds during iterative activation propagation.
 * <p>
 * The settling process propagates activations through the CAPS network
 * until convergence or oscillation, then extracts behavioral attractors
 * from output nodes. These parameters control noise filtering and
 * saturation detection during that process.
 * <p>
 * Iteration count, convergence epsilon, and weight caps are configured
 * per-topology via {@code WeightUpdateParameters}. This config covers
 * the remaining global thresholds not tied to a specific topology.
 * <p>
 * Parameter interactions: minEffectiveDistortionThreshold controls which
 * distortions fire during settling — it operates independently of the
 * output-stage parameters. attractorStrengthMinFilter and
 * saturationActivationThreshold both affect what the settling result
 * reports: the former filters individual attractors, the latter
 * determines the saturation ratio which modulates attractor metadata
 * (highSat flag). saturationActivationThreshold works in tandem with
 * saturationWarningThreshold in WeightUpdateParameters — one defines
 * what counts as saturated, the other defines when to warn about it.
 *
 * @see Mischel, W. & Shoda, Y. (1995). A cognitive-affective system
 * theory of personality. Psychological Review, 102(2), 246-268.
 */
public record CapsSettlingConfig(
        /**
         * Minimum effective distortion threshold after modulation.
         *
         * Distortion definitions can modulate their base threshold based on
         * current activation polarity. This floor prevents the effective
         * threshold from reaching zero, which would cause distortions to
         * fire on arbitrarily small activations.
         *
         * Higher = distortions require stronger activations to fire,
         * producing more stable settling; lower = distortions fire more
         * easily, amplifying cognitive biases.
         *
         * Range: [0.01, 0.5]. Default 0.05 — allows distortions to become
         * very sensitive but not infinitely so.
         */
        double minEffectiveDistortionThreshold,

        /**
         * Minimum attractor strength for inclusion in settling results.
         *
         * Output nodes with activation below this threshold are not
         * reported as behavioral attractors. Filters noise from weakly
         * activated outputs that would generate meaningless behavioral
         * tendencies.
         *
         * Higher = only strong behavioral tendencies are reported,
         * producing fewer but more decisive attractors; lower = weaker
         * signals surface, producing more nuanced but noisier behavior.
         * Related: saturationActivationThreshold.
         *
         * Range: [0.01, 0.5]. Default 0.1 — consistent with typical
         * connectionist activation noise floors.
         */
        double attractorStrengthMinFilter,

        /**
         * Activation magnitude above which a mediating node is considered
         * saturated (pegged at its activation ceiling).
         *
         * High saturation ratios indicate the network has lost discriminative
         * power — most mediating nodes are at their extremes. This triggers
         * saturation warnings and modulates attractor reporting.
         *
         * Higher (closer to 1.0) = only extreme activations count as
         * saturated, fewer warnings; lower = more nodes count as
         * saturated, more frequent saturation warnings.
         * Related: attractorStrengthMinFilter,
         * saturationWarningThreshold (in WeightUpdateParameters).
         *
         * Range: [0.9, 1.0]. Default 0.99 — only near-ceiling activations
         * count as saturated, following standard neural network saturation
         * definitions.
         */
        double saturationActivationThreshold,

        /**
         * Momentum damping factor for activation updates.
         *
         * Each iteration blends the new computed activation with the previous
         * value: newAct = damping * oldAct + (1 - damping) * f(netInput).
         * This smooths multi-cycle oscillations that two-cycle detection misses
         * — particularly in topologies with competing excitatory pathways
         * (e.g. withdrawal vs reactive aggression under social threat).
         *
         * Higher = more inertia, slower convergence but fewer oscillations;
         * lower = faster convergence but oscillation-prone.
         *
         * Range: [0.0, 0.5]. Default 0.3 — standard Hopfield network damping.
         *
         * @see Hopfield, J.J. (1984). Neurons with graded response have
         * collective computational properties like those of two-state neurons.
         * PNAS, 81(10), 3088-3092.
         */
        double dampingFactor
) {
    public static CapsSettlingConfig defaults() {
        return new CapsSettlingConfig(0.05, 0.1, 0.99, 0.3);
    }
}
