package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FeatureStatisticsTest {

    @Test void computeSingleValue() {
        var stats = FeatureStatistics.compute(new double[]{42.0});
        assertThat(stats.min()).isEqualTo(42.0);
        assertThat(stats.max()).isEqualTo(42.0);
        assertThat(stats.median()).isEqualTo(42.0);
        assertThat(stats.p75()).isEqualTo(42.0);
        assertThat(stats.sampleCount()).isEqualTo(1);
    }

    @Test void computeOddCount() {
        var stats = FeatureStatistics.compute(new double[]{3.0, 1.0, 2.0});
        assertThat(stats.min()).isEqualTo(1.0);
        assertThat(stats.max()).isEqualTo(3.0);
        assertThat(stats.median()).isEqualTo(2.0);
        assertThat(stats.p75()).isEqualTo(3.0);
        assertThat(stats.sampleCount()).isEqualTo(3);
    }

    @Test void computeEvenCount() {
        var stats = FeatureStatistics.compute(new double[]{4.0, 2.0, 1.0, 3.0});
        assertThat(stats.min()).isEqualTo(1.0);
        assertThat(stats.max()).isEqualTo(4.0);
        assertThat(stats.median()).isEqualTo(2.0);
        assertThat(stats.p75()).isEqualTo(3.0);
        assertThat(stats.sampleCount()).isEqualTo(4);
    }

    @Test void computeAllSameValues() {
        var stats = FeatureStatistics.compute(new double[]{5.0, 5.0, 5.0});
        assertThat(stats.min()).isEqualTo(5.0);
        assertThat(stats.max()).isEqualTo(5.0);
        assertThat(stats.median()).isEqualTo(5.0);
        assertThat(stats.p75()).isEqualTo(5.0);
    }

    @Test void computeDoesNotMutateInput() {
        double[] input = {3.0, 1.0, 2.0};
        FeatureStatistics.compute(input);
        assertThat(input).containsExactly(3.0, 1.0, 2.0);
    }

    @Test void computeEmptyArrayRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FeatureStatistics.compute(new double[0]));
    }

    @Test void computeNullRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FeatureStatistics.compute(null));
    }

    @Test void computeLargerDataset() {
        var stats = FeatureStatistics.compute(new double[]{
                10, 20, 30, 40, 50, 60, 70, 80, 90, 100});
        assertThat(stats.min()).isEqualTo(10.0);
        assertThat(stats.max()).isEqualTo(100.0);
        assertThat(stats.median()).isEqualTo(50.0);
        assertThat(stats.p75()).isEqualTo(80.0);
        assertThat(stats.sampleCount()).isEqualTo(10);
    }
}
