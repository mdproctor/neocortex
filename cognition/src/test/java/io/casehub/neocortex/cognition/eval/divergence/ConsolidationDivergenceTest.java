package io.casehub.neocortex.cognition.eval.divergence;

import io.casehub.neocortex.cognition.eval.CognitionTestHarness.CapsTrajectory;
import org.junit.jupiter.api.Test;

import static io.casehub.neocortex.cognition.eval.divergence.DivergenceFixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

class ConsolidationDivergenceTest {

    @Test
    void behavioralAttractorsDiverge() {
        var store   = DivergenceFixtures.seededCognitiveStore();
        var harness = DivergenceFixtures.fullBuilder(store).build();
        harness.consolidate("open", TENANT);
        DivergenceFixtures.assertAttractorsDiverge(harness);
    }

    @Test
    void capsTrajectoryAccessorMapsFromSettlingResult() {
        var store   = DivergenceFixtures.seededCognitiveStore();
        var harness = DivergenceFixtures.fullBuilder(store).build();
        harness.consolidate("open", TENANT);

        var trajectory = harness.capsTrajectory("open", TENANT);
        var settling   = harness.capsSettlingResult("open", TENANT);

        assertThat(trajectory).as("capsTrajectory should be present when settling result exists").isPresent();
        assertThat(settling).isPresent();

        CapsTrajectory t = trajectory.get();
        var s = settling.get();
        assertThat(t.iterations()).isEqualTo(s.iterations());
        assertThat(t.convergence()).isEqualTo(s.convergence());
        assertThat(t.saturationRatio()).isEqualTo(s.saturationRatio());
    }

    @Test
    void capsTrajectoryBothAgentsConverge() {
        var store   = DivergenceFixtures.seededCognitiveStore();
        var harness = DivergenceFixtures.fullBuilder(store).build();
        harness.consolidate("open", TENANT);

        var openTrajectory     = harness.capsTrajectory("open", TENANT);
        var cautiousTrajectory = harness.capsTrajectory("cautious", TENANT);

        assertThat(openTrajectory).as("open agent should have CAPS trajectory").isPresent();
        assertThat(cautiousTrajectory).as("cautious agent should have CAPS trajectory").isPresent();

        assertThat(openTrajectory.get().iterations()).as("open iterations").isGreaterThan(0);
        assertThat(cautiousTrajectory.get().iterations()).as("cautious iterations").isGreaterThan(0);
        assertThat(openTrajectory.get().convergence()).as("open convergence").isNotNull();
        assertThat(cautiousTrajectory.get().convergence()).as("cautious convergence").isNotNull();
        assertThat(openTrajectory.get().saturationRatio()).as("open saturation").isBetween(0.0, 1.0);
        assertThat(cautiousTrajectory.get().saturationRatio()).as("cautious saturation").isBetween(0.0, 1.0);
    }


    @Test
    void driveClassificationDiverges() {
        var open     = DivergenceFixtures.openDefaults();
        var cautious = DivergenceFixtures.cautiousDefaults();

        assertThat(open.cbrStrategy()).isNotNull();
        assertThat(cautious.cbrStrategy()).isNotNull();

        assertThat(open.cbrStrategy().retrievalMode())
                .as("CBR retrieval mode (drive classification strategy) should differ — "
                    + "open=%s vs cautious=%s",
                    open.cbrStrategy().retrievalMode(), cautious.cbrStrategy().retrievalMode())
                .isNotEqualTo(cautious.cbrStrategy().retrievalMode());
    }

    @Test
    void experienceGraduationDiverges() {
        var open = DivergenceFixtures.openDefaults().recurringThoughtConfig();
        var cautious = DivergenceFixtures.cautiousDefaults().recurringThoughtConfig();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        boolean thresholdsDiffer = open.fixationThreshold() != cautious.fixationThreshold()
                || open.obsessionThreshold() != cautious.obsessionThreshold()
                || open.ruminationThreshold() != cautious.ruminationThreshold();

        assertThat(thresholdsDiffer)
                .as("experience graduation thresholds should differ — "
                    + "open[fix=%.2f obs=%.2f rum=%.2f] vs cautious[fix=%.2f obs=%.2f rum=%.2f]",
                        open.fixationThreshold(), open.obsessionThreshold(), open.ruminationThreshold(),
                        cautious.fixationThreshold(), cautious.obsessionThreshold(), cautious.ruminationThreshold())
                .isTrue();
    }

    @Test
    void beliefRevisionRateDiverges() {
        var open = DivergenceFixtures.openDefaults().graphStructure();
        var cautious = DivergenceFixtures.cautiousDefaults().graphStructure();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        boolean differs = open.inferenceStyle() != cautious.inferenceStyle()
                || open.connectiveBias() != cautious.connectiveBias();

        assertThat(differs)
                .as("graph structure (belief revision) should differ — "
                    + "open[style=%s bias=%.2f] vs cautious[style=%s bias=%.2f]",
                        open.inferenceStyle(), open.connectiveBias(),
                        cautious.inferenceStyle(), cautious.connectiveBias())
                .isTrue();
    }

    @Test
    void goalPrioritizationDiverges() {
        var open = DivergenceFixtures.openDefaults().extractionBias();
        var cautious = DivergenceFixtures.cautiousDefaults().extractionBias();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        boolean differs = open.relationshipBias() != cautious.relationshipBias()
                || open.affectSensitivity() != cautious.affectSensitivity();

        assertThat(differs)
                .as("extraction bias (goal prioritization) should differ — "
                    + "open[rel=%.2f affect=%.2f] vs cautious[rel=%.2f affect=%.2f]",
                        open.relationshipBias(), open.affectSensitivity(),
                        cautious.relationshipBias(), cautious.affectSensitivity())
                .isTrue();
    }

    @Test
    void capsTrajectoryDiverges() {
        var store   = DivergenceFixtures.seededCognitiveStore();
        var harness = DivergenceFixtures.fullBuilder(store).build();
        harness.consolidate("open", TENANT);

        var capsEngine = harness.capsEngine();
        assertThat(capsEngine).as("CapsEngine should be available").isNotNull();

        var openState    = capsEngine.loadState(TENANT, "open");
        var cautiousState = capsEngine.loadState(TENANT, "cautious");
        assertThat(openState).as("open CAPS state should be initialized").isNotNull();
        assertThat(cautiousState).as("cautious CAPS state should be initialized").isNotNull();

        java.util.Map<String, Double> sharedInputs = java.util.Map.of(
                "reward", 0.7,
                "social_threat", 0.6,
                "mastery", 0.5);

        var openResult    = capsEngine.settle(openState, sharedInputs);
        var cautiousResult = capsEngine.settle(cautiousState, sharedInputs);

        assertThat(openResult.iterations())
                .as("open and cautious should converge at different rates — "
                    + "open=%d vs cautious=%d",
                    openResult.iterations(), cautiousResult.iterations())
                .isNotEqualTo(cautiousResult.iterations());
    }

    @Test
    void capsInputActivationsDiverge() {
        var store = DivergenceFixtures.seededCognitiveStore();
        var harness = DivergenceFixtures.fullBuilder(store)
                                        .withAppraisalEnabled(true)
                                        .build();

        harness.consolidate("open", TENANT);

        harness.observe("open", TENANT, "someone expressed strong disagreement");
        harness.tick("open", TENANT);
        harness.observe("cautious", TENANT, "someone expressed strong disagreement");
        harness.tick("cautious", TENANT);

        var openTrace     = harness.currentTrace("open", TENANT);
        var cautiousTrace = harness.currentTrace("cautious", TENANT);

        assertThat(openTrace).isPresent();
        assertThat(cautiousTrace).isPresent();
        assertThat(openTrace.get().capsInputActivations())
                .as("open agent should have capsInputActivations")
                .isNotEmpty();
        assertThat(cautiousTrace.get().capsInputActivations())
                .as("cautious agent should have capsInputActivations")
                .isNotEmpty();
        assertThat(openTrace.get().capsInputActivations())
                .as("capsInputActivations should differ — open=%s vs cautious=%s",
                    openTrace.get().capsInputActivations(), cautiousTrace.get().capsInputActivations())
                .isNotEqualTo(cautiousTrace.get().capsInputActivations());
    }

}
