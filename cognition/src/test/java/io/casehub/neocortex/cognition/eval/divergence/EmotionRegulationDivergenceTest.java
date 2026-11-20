package io.casehub.neocortex.cognition.eval.divergence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmotionRegulationDivergenceTest {

    @Test
    void regulationStrategyDiverges() {
        var open = DivergenceFixtures.openDefaults().regulationDefaults();
        var cautious = DivergenceFixtures.cautiousDefaults().regulationDefaults();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        assertThat(open.reappraisalCapacity())
                .as("open agent should have higher reappraisal capacity (flexible regulation) — "
                    + "open=%.2f vs cautious=%.2f",
                        open.reappraisalCapacity(), cautious.reappraisalCapacity())
                .isGreaterThan(cautious.reappraisalCapacity());

        assertThat(cautious.suppressionCapacity())
                .as("cautious agent should rely more on suppression (rigid regulation) — "
                    + "open=%.2f vs cautious=%.2f",
                        open.suppressionCapacity(), cautious.suppressionCapacity())
                .isGreaterThan(open.suppressionCapacity());
    }

    @Test
    void copingStrategyDiverges() {
        var open = DivergenceFixtures.openDefaults().copingDefaults();
        var cautious = DivergenceFixtures.cautiousDefaults().copingDefaults();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        assertThat(open.confrontationTendency())
                .as("open agent should confront more (approach-oriented coping)")
                .isGreaterThan(cautious.confrontationTendency());

        assertThat(cautious.avoidanceTendency())
                .as("cautious agent should avoid more (avoidance-oriented coping)")
                .isGreaterThan(open.avoidanceTendency());
    }

    @Test
    void situationalDisplacementAccumulates() {
        var openFacets = DivergenceFixtures.OPEN_FACETS;
        var cautiousFacets = DivergenceFixtures.CAUTIOUS_FACETS;

        var openAnxiousness = openFacets.stream()
                .filter(f -> "Anxiousness".equals(f.facet()))
                .findFirst().orElseThrow();
        var cautiousAnxiousness = cautiousFacets.stream()
                .filter(f -> "Anxiousness".equals(f.facet()))
                .findFirst().orElseThrow();

        var openLability = openFacets.stream()
                .filter(f -> "Emotional Lability".equals(f.facet()))
                .findFirst().orElseThrow();
        var cautiousLability = cautiousFacets.stream()
                .filter(f -> "Emotional Lability".equals(f.facet()))
                .findFirst().orElseThrow();

        assertThat(cautiousAnxiousness.score())
                .as("cautious anxiousness should be much higher (displacement accumulates faster) — "
                    + "open=%.2f vs cautious=%.2f",
                        openAnxiousness.score(), cautiousAnxiousness.score())
                .isGreaterThan(openAnxiousness.score() + 0.4);

        assertThat(cautiousLability.score())
                .as("cautious emotional lability should be much higher (deformation sensitivity) — "
                    + "open=%.2f vs cautious=%.2f",
                        openLability.score(), cautiousLability.score())
                .isGreaterThan(openLability.score() + 0.4);
    }

    @Test
    void modeDetectionDiffers() {
        var open = DivergenceFixtures.openDefaults();
        var cautious = DivergenceFixtures.cautiousDefaults();

        var openHabit = open.habituationConfig();
        var cautiousHabit = cautious.habituationConfig();

        assertThat(openHabit).isNotNull();
        assertThat(cautiousHabit).isNotNull();

        assertThat(openHabit.noveltyThreshold())
                .as("novelty threshold (mode detection sensitivity) should differ — "
                    + "open=%.2f vs cautious=%.2f",
                        openHabit.noveltyThreshold(), cautiousHabit.noveltyThreshold())
                .isNotEqualTo(cautiousHabit.noveltyThreshold());
    }
}
