package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NumericRangeTest {

    @Test
    void exact_minEqualsMax() {
        NumericRange range = NumericRange.exact(42.0);
        assertThat(range.min()).isEqualTo(42.0);
        assertThat(range.max()).isEqualTo(42.0);
    }

    @Test
    void within_positiveCenter() {
        NumericRange range = NumericRange.within(100.0, 0.1);
        assertThat(range.min()).isEqualTo(90.0);
        assertThat(range.max()).isEqualTo(110.0);
    }

    @Test
    void within_zeroCenter() {
        NumericRange range = NumericRange.within(0.0, 0.5);
        assertThat(range.min()).isEqualTo(0.0);
        assertThat(range.max()).isEqualTo(0.0);
    }

    @Test
    void within_negativeCenter() {
        NumericRange range = NumericRange.within(-100.0, 0.1);
        assertThat(range.min()).isEqualTo(-110.0);
        assertThat(range.max()).isEqualTo(-90.0);
    }

    @Test
    void of_validRange() {
        NumericRange range = NumericRange.of(1.0, 5.0);
        assertThat(range.min()).isEqualTo(1.0);
        assertThat(range.max()).isEqualTo(5.0);
    }

    @Test
    void of_minGreaterThanMax_throws() {
        assertThatThrownBy(() -> NumericRange.of(5.0, 1.0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("min must be <= max");
    }

    @Test
    void within_negativeFraction_throws() {
        assertThatThrownBy(() -> NumericRange.within(100.0, -0.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("toleranceFraction must be >= 0");
    }

    @Test
    void contains_withinRange() {
        NumericRange range = NumericRange.of(10.0, 20.0);
        assertThat(range.contains(15.0)).isTrue();
    }

    @Test
    void contains_atBoundaries() {
        NumericRange range = NumericRange.of(10.0, 20.0);
        assertThat(range.contains(10.0)).isTrue();
        assertThat(range.contains(20.0)).isTrue();
    }

    @Test
    void contains_outsideRange() {
        NumericRange range = NumericRange.of(10.0, 20.0);
        assertThat(range.contains(9.99)).isFalse();
        assertThat(range.contains(20.01)).isFalse();
    }
}
