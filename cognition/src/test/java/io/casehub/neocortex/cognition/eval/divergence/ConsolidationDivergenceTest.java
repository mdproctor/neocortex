package io.casehub.neocortex.cognition.eval.divergence;

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
}
