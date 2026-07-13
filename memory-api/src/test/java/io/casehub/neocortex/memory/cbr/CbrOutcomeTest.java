package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class CbrOutcomeTest {

    private static final Instant NOW = Instant.parse("2026-07-13T10:00:00Z");

    @Test
    void of_successRate1_isSuccess() {
        CbrOutcome outcome = CbrOutcome.of(1.0, "all passed", NOW);
        assertThat(outcome.result()).isEqualTo(CbrOutcome.Outcome.SUCCESS);
        assertThat(outcome.successRate()).isEqualTo(1.0);
        assertThat(outcome.detail()).isEqualTo("all passed");
        assertThat(outcome.observedAt()).isEqualTo(NOW);
    }

    @Test
    void of_successRate0_isFailure() {
        CbrOutcome outcome = CbrOutcome.of(0.0, null, NOW);
        assertThat(outcome.result()).isEqualTo(CbrOutcome.Outcome.FAILURE);
    }

    @Test
    void of_successRateBetween_isPartial() {
        CbrOutcome outcome = CbrOutcome.of(0.75, "3 of 4", NOW);
        assertThat(outcome.result()).isEqualTo(CbrOutcome.Outcome.PARTIAL);
    }

    @Test
    void constructor_rejectsNegativeRate() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CbrOutcome(CbrOutcome.Outcome.FAILURE, -0.1, null, NOW));
    }

    @Test
    void constructor_rejectsRateAboveOne() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CbrOutcome(CbrOutcome.Outcome.SUCCESS, 1.1, null, NOW));
    }

    @Test
    void constructor_rejectsNullResult() {
        assertThatNullPointerException()
            .isThrownBy(() -> new CbrOutcome(null, 0.5, null, NOW));
    }

    @Test
    void constructor_rejectsNullObservedAt() {
        assertThatNullPointerException()
            .isThrownBy(() -> new CbrOutcome(CbrOutcome.Outcome.SUCCESS, 1.0, null, null));
    }

    @Test
    void adjustConfidence_emaFormula() {
        double result = CbrOutcome.adjustConfidence(0.8, 1.0, 0.2);
        assertThat(result).isCloseTo(0.84, within(0.001));
    }

    @Test
    void adjustConfidence_failure_decreases() {
        double result = CbrOutcome.adjustConfidence(0.8, 0.0, 0.2);
        assertThat(result).isCloseTo(0.64, within(0.001));
    }

    @Test
    void adjustConfidence_partial() {
        double result = CbrOutcome.adjustConfidence(0.8, 0.5, 0.2);
        assertThat(result).isCloseTo(0.74, within(0.001));
    }

    @Test
    void adjustConfidence_nullOldConfidence_treatsAsOne() {
        double result = CbrOutcome.adjustConfidence(null, 0.0, 0.2);
        assertThat(result).isCloseTo(0.8, within(0.001));
    }

    @Test
    void adjustConfidence_convergesToObservedRate() {
        double confidence = 0.5;
        for (int i = 0; i < 50; i++) {
            confidence = CbrOutcome.adjustConfidence(confidence, 1.0, 0.2);
        }
        assertThat(confidence).isCloseTo(1.0, within(0.01));
    }

    @Test
    void defaultLearningRate() {
        assertThat(CbrOutcome.DEFAULT_LEARNING_RATE).isEqualTo(0.2);
    }
}
