package io.casehub.neocortex.memory.cbr.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DefaultOutcomeWeightingFunctionTest {

    @Test void confidenceOne_noChange() {
        var fn = new DefaultOutcomeWeightingFunction(0.3);
        assertThat(fn.apply(0.85, 1.0)).isCloseTo(0.85, within(1e-9));
    }

    @Test void confidenceZero_reducedByAlpha() {
        var fn = new DefaultOutcomeWeightingFunction(0.3);
        assertThat(fn.apply(0.85, 0.0)).isCloseTo(0.595, within(1e-9));
    }

    @Test void alphaZero_noEffect() {
        var fn = new DefaultOutcomeWeightingFunction(0.0);
        assertThat(fn.apply(0.85, 0.0)).isCloseTo(0.85, within(1e-9));
        assertThat(fn.apply(0.85, 0.5)).isCloseTo(0.85, within(1e-9));
    }

    @Test void alphaOne_fullMultiplication() {
        var fn = new DefaultOutcomeWeightingFunction(1.0);
        assertThat(fn.apply(0.85, 0.5)).isCloseTo(0.425, within(1e-9));
    }

    @Test void halfConfidence_defaultAlpha() {
        var fn = new DefaultOutcomeWeightingFunction(0.3);
        assertThat(fn.apply(1.0, 0.5)).isCloseTo(0.85, within(1e-9));
    }

    @Test void negativeScore_handledCorrectly() {
        var fn = new DefaultOutcomeWeightingFunction(0.3);
        assertThat(fn.apply(-0.5, 1.0)).isCloseTo(-0.5, within(1e-9));
    }
}
