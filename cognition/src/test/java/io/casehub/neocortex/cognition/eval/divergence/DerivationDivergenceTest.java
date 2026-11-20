package io.casehub.neocortex.cognition.eval.divergence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DerivationDivergenceTest {

    @Test
    void moodBaselineDiverges() {
        var open = DivergenceFixtures.openDefaults().moodBaseline();
        var cautious = DivergenceFixtures.cautiousDefaults().moodBaseline();

        boolean differs = open.pleasure() != cautious.pleasure()
                || open.arousal() != cautious.arousal()
                || open.dominance() != cautious.dominance();

        assertThat(differs)
                .as("mood baseline PAD should differ — open[p=%.2f a=%.2f d=%.2f] vs cautious[p=%.2f a=%.2f d=%.2f]",
                        open.pleasure(), open.arousal(), open.dominance(),
                        cautious.pleasure(), cautious.arousal(), cautious.dominance())
                .isTrue();
    }

    @Test
    void copingDefaultsDiverge() {
        var open = DivergenceFixtures.openDefaults().copingDefaults();
        var cautious = DivergenceFixtures.cautiousDefaults().copingDefaults();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        assertThat(cautious.avoidanceTendency())
                .as("cautious agent should be more avoidant (high neuroticism)")
                .isGreaterThan(open.avoidanceTendency());
        assertThat(open.confrontationTendency())
                .as("open agent should be more confrontational (high extraversion)")
                .isGreaterThan(cautious.confrontationTendency());
        assertThat(cautious.disengagementTendency())
                .as("cautious agent should disengage more (high N, low C)")
                .isGreaterThan(open.disengagementTendency());
        assertThat(cautious.suppressionTendency())
                .as("cautious agent should suppress more (high N + strict rules)")
                .isGreaterThan(open.suppressionTendency());
    }

    @Test
    void regulationWeightsDiverge() {
        var open = DivergenceFixtures.openDefaults().regulationDefaults();
        var cautious = DivergenceFixtures.cautiousDefaults().regulationDefaults();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        boolean differs = open.reappraisalCapacity() != cautious.reappraisalCapacity()
                || open.suppressionCapacity() != cautious.suppressionCapacity()
                || open.redirectTendency() != cautious.redirectTendency()
                || open.socialRegulationTendency() != cautious.socialRegulationTendency();

        assertThat(differs)
                .as("regulation weights should differ — open[reap=%.2f supp=%.2f redir=%.2f social=%.2f] vs cautious[reap=%.2f supp=%.2f redir=%.2f social=%.2f]",
                        open.reappraisalCapacity(), open.suppressionCapacity(), open.redirectTendency(), open.socialRegulationTendency(),
                        cautious.reappraisalCapacity(), cautious.suppressionCapacity(), cautious.redirectTendency(), cautious.socialRegulationTendency())
                .isTrue();
    }

    @Test
    void cbrStrategyDiverges() {
        var open = DivergenceFixtures.openDefaults().cbrStrategy();
        var cautious = DivergenceFixtures.cautiousDefaults().cbrStrategy();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        boolean differs = open.minSimilarity() != cautious.minSimilarity()
                || open.temporalDecayDays() != cautious.temporalDecayDays()
                || open.retrievalMode() != cautious.retrievalMode();

        assertThat(differs)
                .as("CBR strategy should differ — open[sim=%.2f decay=%d mode=%s] vs cautious[sim=%.2f decay=%d mode=%s]",
                        open.minSimilarity(), open.temporalDecayDays(), open.retrievalMode(),
                        cautious.minSimilarity(), cautious.temporalDecayDays(), cautious.retrievalMode())
                .isTrue();
    }

    @Test
    void socialCognitionDiverges() {
        var open = DivergenceFixtures.openDefaults().socialCognition();
        var cautious = DivergenceFixtures.cautiousDefaults().socialCognition();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        boolean differs = open.trustFormationRate() != cautious.trustFormationRate()
                || open.conflictInterpretation() != cautious.conflictInterpretation();

        assertThat(differs)
                .as("social cognition should differ — open[trust=%.2f conflict=%s] vs cautious[trust=%.2f conflict=%s]",
                        open.trustFormationRate(), open.conflictInterpretation(),
                        cautious.trustFormationRate(), cautious.conflictInterpretation())
                .isTrue();
    }

    @Test
    void habituationConfigDiverges() {
        var open = DivergenceFixtures.openDefaults().habituationConfig();
        var cautious = DivergenceFixtures.cautiousDefaults().habituationConfig();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        boolean differs = open.habituationRate() != cautious.habituationRate()
                || open.noveltyThreshold() != cautious.noveltyThreshold()
                || open.repetitionTolerance() != cautious.repetitionTolerance();

        assertThat(differs)
                .as("habituation config should differ — open[rate=%.2f novelty=%.2f tolerance=%.2f] vs cautious[rate=%.2f novelty=%.2f tolerance=%.2f]",
                        open.habituationRate(), open.noveltyThreshold(), open.repetitionTolerance(),
                        cautious.habituationRate(), cautious.noveltyThreshold(), cautious.repetitionTolerance())
                .isTrue();
    }

    @Test
    void capacityConfigDiverges() {
        var open = DivergenceFixtures.openDefaults().capacityConfig();
        var cautious = DivergenceFixtures.cautiousDefaults().capacityConfig();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        boolean differs = !java.util.Objects.equals(open.relationalComplexity(), cautious.relationalComplexity())
                || !java.util.Objects.equals(open.associativeReach(), cautious.associativeReach())
                || !java.util.Objects.equals(open.workingMemorySpan(), cautious.workingMemorySpan());

        assertThat(differs)
                .as("capacity config should differ — open[rc=%s ar=%s wm=%s] vs cautious[rc=%s ar=%s wm=%s]",
                        open.relationalComplexity(), open.associativeReach(), open.workingMemorySpan(),
                        cautious.relationalComplexity(), cautious.associativeReach(), cautious.workingMemorySpan())
                .isTrue();
    }

    @Test
    void expressionStylesDiverge() {
        var open = DivergenceFixtures.openDefaults().expressionStyles();
        var cautious = DivergenceFixtures.cautiousDefaults().expressionStyles();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        assertThat(open.driveExpressions())
                .as("expression style drive labels should differ between personalities")
                .isNotEqualTo(cautious.driveExpressions());
    }

    @Test
    void recurringThoughtConfigDiverges() {
        var open = DivergenceFixtures.openDefaults().recurringThoughtConfig();
        var cautious = DivergenceFixtures.cautiousDefaults().recurringThoughtConfig();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        boolean differs = open.fixationThreshold() != cautious.fixationThreshold()
                || open.obsessionThreshold() != cautious.obsessionThreshold()
                || open.ruminationThreshold() != cautious.ruminationThreshold();

        assertThat(differs)
                .as("recurring thought config should differ — open[fix=%.2f obs=%.2f rum=%.2f] vs cautious[fix=%.2f obs=%.2f rum=%.2f]",
                        open.fixationThreshold(), open.obsessionThreshold(), open.ruminationThreshold(),
                        cautious.fixationThreshold(), cautious.obsessionThreshold(), cautious.ruminationThreshold())
                .isTrue();
    }
}
