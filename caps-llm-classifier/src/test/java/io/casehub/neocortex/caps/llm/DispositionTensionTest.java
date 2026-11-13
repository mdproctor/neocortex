package io.casehub.neocortex.caps.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DispositionTensionTest {

    @Test
    void boldStrict_maximumTension() {
        var meta = Map.of(
            "disposition.riskAppetite", "bold",
            "disposition.ruleFollowing", "strict");

        assertThat(DispositionTension.maxTension(meta)).isEqualTo(1.0);

        var top = DispositionTension.topTension(meta);
        assertThat(top).isPresent();
        assertThat(top.get().conflict()).isEqualTo("approach vs inhibition");
        assertThat(top.get().narrative()).contains("act decisively");
    }

    @Test
    void highAutonomyAvoidant_highTension() {
        var meta = Map.of(
            "disposition.autonomy", "high",
            "disposition.conflictMode", "avoidant");

        assertThat(DispositionTension.maxTension(meta)).isEqualTo(0.9);
    }

    @Test
    void cooperativeHighAutonomy_tension() {
        var meta = Map.of(
            "disposition.socialOrient", "cooperative",
            "disposition.autonomy", "high");

        assertThat(DispositionTension.maxTension(meta)).isEqualTo(0.8);
    }

    @Test
    void boldAvoidant_tension() {
        var meta = Map.of(
            "disposition.riskAppetite", "bold",
            "disposition.conflictMode", "avoidant");

        assertThat(DispositionTension.maxTension(meta)).isEqualTo(0.7);
    }

    @Test
    void independentLowAutonomy_tension() {
        var meta = Map.of(
            "disposition.socialOrient", "independent",
            "disposition.autonomy", "low");

        assertThat(DispositionTension.maxTension(meta)).isEqualTo(0.7);
    }

    @Test
    void competitiveStrict_tension() {
        var meta = Map.of(
            "disposition.socialOrient", "competitive",
            "disposition.ruleFollowing", "strict");

        assertThat(DispositionTension.maxTension(meta)).isEqualTo(0.6);
    }

    @Test
    void noConflictingAxes_zeroTension() {
        var meta = Map.of(
            "disposition.socialOrient", "cooperative",
            "disposition.ruleFollowing", "moderate",
            "disposition.riskAppetite", "calculated",
            "disposition.autonomy", "moderate",
            "disposition.conflictMode", "collaborative");

        assertThat(DispositionTension.maxTension(meta)).isEqualTo(0.0);
        assertThat(DispositionTension.topTension(meta)).isEmpty();
    }

    @Test
    void emptyMetadata_zeroTension() {
        assertThat(DispositionTension.maxTension(Map.of())).isEqualTo(0.0);
        assertThat(DispositionTension.topTension(Map.of())).isEmpty();
    }

    @Test
    void nullMetadata_zeroTension() {
        assertThat(DispositionTension.maxTension(null)).isEqualTo(0.0);
        assertThat(DispositionTension.topTension(null)).isEmpty();
    }

    @Test
    void multipleTensions_returnsHighest() {
        var meta = Map.of(
            "disposition.riskAppetite", "bold",
            "disposition.ruleFollowing", "strict",
            "disposition.autonomy", "high",
            "disposition.conflictMode", "avoidant");

        assertThat(DispositionTension.maxTension(meta)).isEqualTo(1.0);
        assertThat(DispositionTension.topTension(meta).get().score()).isEqualTo(1.0);
    }

    @Test
    void partialMetadata_matchesAvailablePairs() {
        var meta = Map.of("disposition.riskAppetite", "bold");

        assertThat(DispositionTension.maxTension(meta)).isEqualTo(0.0);
    }
}
