package io.casehub.neocortex.cognition.eval.divergence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TheoryOfMindDivergenceTest {

    @Test
    void tomPosteriorsDivergeOnSameObservation() {
        var open = DivergenceFixtures.openDefaults().socialCognition();
        var cautious = DivergenceFixtures.cautiousDefaults().socialCognition();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        assertThat(open.trustFormationRate())
                .as("open agent should form trust faster (cooperative disposition) — "
                    + "open=%.2f vs cautious=%.2f",
                        open.trustFormationRate(), cautious.trustFormationRate())
                .isGreaterThan(cautious.trustFormationRate());
    }

    @Test
    void suspiciousnessScalesEvidence() {
        var openFacets = DivergenceFixtures.OPEN_FACETS;
        var cautiousFacets = DivergenceFixtures.CAUTIOUS_FACETS;

        var openSuspicion = openFacets.stream()
                .filter(f -> "Suspiciousness".equals(f.facet()))
                .findFirst().orElseThrow();
        var cautiousSuspicion = cautiousFacets.stream()
                .filter(f -> "Suspiciousness".equals(f.facet()))
                .findFirst().orElseThrow();

        assertThat(cautiousSuspicion.score())
                .as("cautious agent should have higher suspiciousness (scales threat evidence) — "
                    + "open=%.2f vs cautious=%.2f",
                        openSuspicion.score(), cautiousSuspicion.score())
                .isGreaterThan(openSuspicion.score());

        assertThat(cautiousSuspicion.isHigh())
                .as("cautious suspiciousness should be high (>=0.7)")
                .isTrue();
        assertThat(openSuspicion.isLow())
                .as("open suspiciousness should be low (<=0.3)")
                .isTrue();
    }

    @Test
    void relationalBiasingShiftsActivations() {
        var open = DivergenceFixtures.openDefaults();
        var cautious = DivergenceFixtures.cautiousDefaults();

        var openBias = open.extractionBias();
        var cautiousBias = cautious.extractionBias();

        assertThat(openBias).isNotNull();
        assertThat(cautiousBias).isNotNull();

        assertThat(openBias.relationshipBias())
                .as("relational biasing should differ — open=%.2f vs cautious=%.2f",
                        openBias.relationshipBias(), cautiousBias.relationshipBias())
                .isNotEqualTo(cautiousBias.relationshipBias());
    }

    @Test
    void channelWeightsDivergeByPersonality() {
        var open = DivergenceFixtures.openDefaults();
        var cautious = DivergenceFixtures.cautiousDefaults();

        var openAppraisal = open.appraisalWeights();
        var cautiousAppraisal = cautious.appraisalWeights();

        assertThat(openAppraisal.fearOnsetThreshold())
                .as("fear onset threshold (channel weight driver) should differ — "
                    + "open=%.2f vs cautious=%.2f",
                        openAppraisal.fearOnsetThreshold(), cautiousAppraisal.fearOnsetThreshold())
                .isNotEqualTo(cautiousAppraisal.fearOnsetThreshold());

        assertThat(openAppraisal.urgencyWeight())
                .as("urgency weight (channel emphasis modulator) should differ — "
                    + "open=%.2f vs cautious=%.2f",
                        openAppraisal.urgencyWeight(), cautiousAppraisal.urgencyWeight())
                .isNotEqualTo(cautiousAppraisal.urgencyWeight());
    }
}
