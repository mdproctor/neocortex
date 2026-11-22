package io.casehub.neocortex.caps.engine;

import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.ConvergenceType;
import io.casehub.neocortex.caps.SettlingResult;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackLoopDivergenceTest {

    private static final DispositionAxes OPEN =
            new DispositionAxes("cooperative", "moderate", "bold", "high", "cooperative");

    private static final DispositionAxes CAUTIOUS =
            new DispositionAxes("independent", "strict", "conservative", "low", "avoidant");

    private static CapsTopology topology;

    @BeforeAll
    static void loadTopology() {
        topology = new CapsTopologyLoader().loadFromClasspath("caps-topology.yaml");
    }

    @Test
    void feedbackLoopsProducePersonalityDependentConvergenceRate() {
        var settler = new CapsSettler(topology);

        var openState = new DispositionWeightMapper(topology)
                .initializeWeights("t1", "open", OPEN);
        var cautiousState = new DispositionWeightMapper(topology)
                .initializeWeights("t1", "cautious", CAUTIOUS);

        Map<String, Double> sharedInputs = Map.of(
                "reward", 0.7,
                "social_threat", 0.6,
                "mastery", 0.5);

        SettlingResult openResult = settler.settle(openState, sharedInputs);
        SettlingResult cautiousResult = settler.settle(cautiousState, sharedInputs);

        assertThat(openResult.iterations())
                .as("open and cautious should converge at different rates — "
                    + "open=%d vs cautious=%d",
                    openResult.iterations(), cautiousResult.iterations())
                .isNotEqualTo(cautiousResult.iterations());
    }

    @Test
    void bothProfilesStillConverge() {
        var settler = new CapsSettler(topology);

        var openState = new DispositionWeightMapper(topology)
                .initializeWeights("t1", "open", OPEN);
        var cautiousState = new DispositionWeightMapper(topology)
                .initializeWeights("t1", "cautious", CAUTIOUS);

        Map<String, Double> inputs = Map.of(
                "reward", 0.7,
                "social_threat", 0.5);

        SettlingResult openResult = settler.settle(openState, inputs);
        SettlingResult cautiousResult = settler.settle(cautiousState, inputs);

        assertThat(openResult.convergence())
                .as("open should still converge")
                .isEqualTo(ConvergenceType.CONVERGED);
        assertThat(cautiousResult.convergence())
                .as("cautious should still converge")
                .isEqualTo(ConvergenceType.CONVERGED);
    }
}
