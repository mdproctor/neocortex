package io.casehub.neocortex.cognition.appraisal;

import io.casehub.neocortex.cognitive.HabituationConfig;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HabituationStateTest {

    @Test
    void emptyState() {
        var state = HabituationState.empty();
        assertThat(state.observationCounts()).isEmpty();
        assertThat(state.noveltyScores()).isEmpty();
    }

    @Test
    void withObservationIncrementsCount() {
        var state = HabituationState.empty()
                .withObservation("abc", 0.9)
                .withObservation("abc", 0.7);
        assertThat(state.observationCounts().get("abc")).isEqualTo(2);
        assertThat(state.noveltyScores().get("abc")).isCloseTo(0.7, within(0.001));
    }

    @Test
    void immutability() {
        var s1 = HabituationState.empty();
        var s2 = s1.withObservation("x", 0.5);
        assertThat(s1.observationCounts()).isEmpty();
        assertThat(s2.observationCounts().get("x")).isEqualTo(1);
    }

    @Test
    void multipleDistinctObservations() {
        var state = HabituationState.empty()
                .withObservation("a", 1.0)
                .withObservation("b", 0.8);
        assertThat(state.observationCounts()).hasSize(2);
        assertThat(state.noveltyScores()).hasSize(2);
    }

    @Test
    void habituationConfigDefaults() {
        var config = HabituationConfig.defaults();
        assertThat(config.habituationRate()).isCloseTo(0.2, within(0.001));
        assertThat(config.noveltyThreshold()).isCloseTo(0.3, within(0.001));
        assertThat(config.repetitionTolerance()).isCloseTo(5.0, within(0.001));
        assertThat(config.domainModulation()).isEmpty();
    }

}
