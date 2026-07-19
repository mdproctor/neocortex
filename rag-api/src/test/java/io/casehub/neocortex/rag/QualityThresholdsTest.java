package io.casehub.neocortex.rag;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class QualityThresholdsTest {

    @Test
    void defaults_returnsExpectedValues() {
        var t = QualityThresholds.defaults();
        assertThat(t.minRetrievalsForQualityCheck()).isEqualTo(3);
        assertThat(t.minFeedbackForQualityCheck()).isEqualTo(3);
        assertThat(t.lowQualityRatio()).isEqualTo(0.7);
        assertThat(t.staleWindow()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void rejectsNegativeMinRetrievals() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new QualityThresholds(0, 3, 0.7, Duration.ofDays(90)));
    }

    @Test
    void rejectsNegativeMinFeedback() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new QualityThresholds(3, 0, 0.7, Duration.ofDays(90)));
    }

    @Test
    void rejectsRatioAboveOne() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new QualityThresholds(3, 3, 1.1, Duration.ofDays(90)));
    }

    @Test
    void rejectsNegativeRatio() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new QualityThresholds(3, 3, -0.1, Duration.ofDays(90)));
    }

    @Test
    void rejectsNullStaleWindow() {
        assertThatNullPointerException()
                .isThrownBy(() -> new QualityThresholds(3, 3, 0.7, null));
    }

    @Test
    void acceptsBoundaryValues() {
        assertThatNoException()
                .isThrownBy(() -> new QualityThresholds(1, 1, 0.0, Duration.ofDays(1)));
        assertThatNoException()
                .isThrownBy(() -> new QualityThresholds(1, 1, 1.0, Duration.ofDays(1)));
    }
}
