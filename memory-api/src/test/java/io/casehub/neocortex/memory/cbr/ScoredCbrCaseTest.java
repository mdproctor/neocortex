package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ScoredCbrCaseTest {

    @Test
    void constructor_validScoreRange_succeeds() {
        var cbrCase = new TextualCbrCase("problem", "solution", null, null);
        assertThat(new ScoredCbrCase<>(cbrCase, 1.0).score()).isEqualTo(1.0);
        assertThat(new ScoredCbrCase<>(cbrCase, 0.0).score()).isEqualTo(0.0);
        assertThat(new ScoredCbrCase<>(cbrCase, -1.0).score()).isEqualTo(-1.0);
    }

    @Test
    void constructor_scoreAboveOne_throws() {
        var cbrCase = new TextualCbrCase("problem", "solution", null, null);
        assertThatThrownBy(() -> new ScoredCbrCase<>(cbrCase, 1.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("score must be in [-1,1]");
    }

    @Test
    void constructor_scoreBelowMinusOne_throws() {
        var cbrCase = new TextualCbrCase("problem", "solution", null, null);
        assertThatThrownBy(() -> new ScoredCbrCase<>(cbrCase, -1.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("score must be in [-1,1]");
    }

    @Test
    void constructor_scoreNaN_throws() {
        var cbrCase = new TextualCbrCase("problem", "solution", null, null);
        assertThatThrownBy(() -> new ScoredCbrCase<>(cbrCase, Double.NaN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("score must be in [-1,1]");
    }

    @Test
    void constructor_scorePositiveInfinity_throws() {
        var cbrCase = new TextualCbrCase("problem", "solution", null, null);
        assertThatThrownBy(() -> new ScoredCbrCase<>(cbrCase, Double.POSITIVE_INFINITY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("score must be in [-1,1]");
    }

    @Test
    void constructor_scoreNegativeInfinity_throws() {
        var cbrCase = new TextualCbrCase("problem", "solution", null, null);
        assertThatThrownBy(() -> new ScoredCbrCase<>(cbrCase, Double.NEGATIVE_INFINITY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("score must be in [-1,1]");
    }

    @Test
    void constructor_nullCase_throws() {
        assertThatThrownBy(() -> new ScoredCbrCase<>(null, 0.5))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("cbrCase required");
    }

    @Test
    void constructor_twoArg_defaultsRerankedFalse() {
        var cbrCase = new TextualCbrCase("problem", "solution", null, null);
        assertThat(new ScoredCbrCase<>(cbrCase, 0.5).reranked()).isFalse();
    }

    @Test
    void constructor_threeArg_setsReranked() {
        var cbrCase = new TextualCbrCase("problem", "solution", null, null);
        assertThat(new ScoredCbrCase<>(cbrCase, 0.5, true).reranked()).isTrue();
    }

    @Test
    void withReranked_returnsNewInstanceWithRerankedTrue() {
        var cbrCase = new TextualCbrCase("problem", "solution", null, null);
        var original = new ScoredCbrCase<>(cbrCase, 0.8);
        var reranked = original.withReranked();
        assertThat(reranked.reranked()).isTrue();
        assertThat(reranked.score()).isEqualTo(0.8);
        assertThat(reranked.cbrCase()).isSameAs(cbrCase);
        assertThat(original.reranked()).isFalse();
    }
}
