package io.casehub.neocortex.caps.testing;

import io.casehub.neocortex.caps.AgentCapsState;
import io.casehub.neocortex.caps.CapsAttractor;
import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.ConvergenceType;
import io.casehub.neocortex.caps.SettlingResult;
import io.casehub.neocortex.caps.WeightUpdateParameters;
import io.casehub.neocortex.caps.engine.CapsSettler;
import io.casehub.neocortex.caps.engine.CapsSettlingConfig;
import io.casehub.neocortex.caps.engine.CapsTopologyLoader;
import io.casehub.neocortex.caps.engine.DispositionWeightMapper;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for #1027: CAPS settling fails to converge for
 * social-threat inputs — MAX_ITERATIONS, zero attractors.
 */
class SocialThreatOscillationTest {

    private static final DispositionAxes COOPERATIVE_CAUTIOUS = new DispositionAxes(
            "cooperative", "strict", "conservative", "moderate", "cooperative");

    private static final Map<String, Double> SOCIAL_THREAT_INPUTS = Map.of(
            "exclusion", 0.95,
            "social_threat", 0.95,
            "rejection", 0.85,
            "abandonment", 0.85);

    private static CapsTopology withMaxIterations(CapsTopology base, int maxIter) {
        var wu = base.weightUpdate();
        return new CapsTopology(base.version(), base.nodes(), base.connections(),
                base.dispositionModifiers(), base.distortions(),
                new WeightUpdateParameters(wu.alphaMin(), wu.alphaMax(),
                        wu.betaMin(), wu.betaMax(), wu.temporalDiscount(),
                        wu.scheduleModifiers(), wu.vicariousDiscount(),
                        maxIter, wu.epsilon(), wu.effectiveWeightCap(),
                        wu.compoundDistortionCap(), wu.saturationWarningThreshold()));
    }

    @Test
    void lowIterationLimitDetectsOscillation() {
        // Verifies the #1027 fix: when maxIterations is too low for the
        // two-cycle or window-variance detectors, the multi-cycle oscillation
        // detector at the MAX_ITERATIONS fallback reclassifies to OSCILLATION
        // and uses peak activations for attractor extraction.
        var base = new CapsTopologyLoader().loadFromClasspath("caps-topology.yaml");
        var topology = withMaxIterations(base, 4);
        var settler = new CapsSettler(topology,
                new CapsSettlingConfig(0.05, 0.55, 0.99, 0.0));
        var mapper = new DispositionWeightMapper(base);
        AgentCapsState state = mapper.initializeWeights("test", "agent", COOPERATIVE_CAUTIOUS);

        SettlingResult result = settler.settle(state, SOCIAL_THREAT_INPUTS);

        assertThat(result.convergence())
                .as("Oscillation should be detected even with low iteration limit")
                .isEqualTo(ConvergenceType.OSCILLATION);
        assertThat(result.attractors())
                .as("Peak-based extraction should produce meaningful attractors")
                .isNotEmpty();
    }

    @Test
    void maxIterationsOscillationShouldProduceAttractors() {
        // The fix: when settling hits MAX_ITERATIONS but output nodes show
        // oscillation (significant amplitude variation in the window), detect
        // as OSCILLATION and extract attractors from peak activations.
        var base = new CapsTopologyLoader().loadFromClasspath("caps-topology.yaml");
        var topology = withMaxIterations(base, 4);
        var settler = new CapsSettler(topology,
                new CapsSettlingConfig(0.05, 0.55, 0.99, 0.0));
        var mapper = new DispositionWeightMapper(base);
        AgentCapsState state = mapper.initializeWeights("test", "agent", COOPERATIVE_CAUTIOUS);

        SettlingResult result = settler.settle(state, SOCIAL_THREAT_INPUTS);

        assertThat(result.attractors())
                .as("Oscillating network should produce attractors from peak activations, " +
                    "not zero from averaged dilution")
                .isNotEmpty();

        assertThat(result.convergence())
                .as("Should be reclassified as OSCILLATION, not MAX_ITERATIONS")
                .isEqualTo(ConvergenceType.OSCILLATION);
    }

    @Test
    void maxIterationsOscillationIncludesDefensiveAttractors() {
        var base = new CapsTopologyLoader().loadFromClasspath("caps-topology.yaml");
        var topology = withMaxIterations(base, 4);
        var settler = new CapsSettler(topology,
                new CapsSettlingConfig(0.05, 0.55, 0.99, 0.0));
        var mapper = new DispositionWeightMapper(base);
        AgentCapsState state = mapper.initializeWeights("test", "agent", COOPERATIVE_CAUTIOUS);

        SettlingResult result = settler.settle(state, SOCIAL_THREAT_INPUTS);

        boolean hasDefensiveAttractor = result.attractors().stream()
                .anyMatch(a -> "withdraw".equals(a.nodeId())
                        || "cautious_approach".equals(a.nodeId())
                        || "distance_maintain".equals(a.nodeId())
                        || "social_withdrawal".equals(a.nodeId()));

        assertThat(hasDefensiveAttractor)
                .as("Social-threat oscillation should produce at least one defensive attractor")
                .isTrue();
    }

    @Test
    void dampedSocialThreatStillConverges() {
        var topology = new CapsTopologyLoader().loadFromClasspath("caps-topology.yaml");
        var settler = new CapsSettler(topology); // default damping=0.4
        var mapper = new DispositionWeightMapper(topology);
        AgentCapsState state = mapper.initializeWeights("test", "agent", COOPERATIVE_CAUTIOUS);

        SettlingResult result = settler.settle(state, SOCIAL_THREAT_INPUTS);

        assertThat(result.convergence())
                .as("With default damping, social-threat should converge")
                .isNotEqualTo(ConvergenceType.MAX_ITERATIONS);
        assertThat(result.attractors())
                .as("Damped settling should produce attractors")
                .isNotEmpty();
    }
}
