package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DtwSimilarityTest {

    private static final FeatureField.TimeSeries SCHEMA =
        (FeatureField.TimeSeries) FeatureField.timeSeries("curve", "t",
            FeatureField.numeric("t", 0, 30),
            FeatureField.numeric("val", 0, 100));

    @Test
    void identicalSequences_perfectSimilarity() {
        var seq = List.of(
            Map.<String, Object>of("t", 1, "val", 50),
            Map.<String, Object>of("t", 2, "val", 60));
        assertThat(DtwSimilarity.compute(seq, seq, SCHEMA)).isEqualTo(1.0);
    }

    @Test
    void completelyDifferent_lowSimilarity() {
        var q = List.of(Map.<String, Object>of("t", 1, "val", 0));
        var c = List.of(Map.<String, Object>of("t", 1, "val", 100));
        assertThat(DtwSimilarity.compute(q, c, SCHEMA)).isLessThanOrEqualTo(0.5);
    }

    @Test
    void variableLength_dtwAligns() {
        var q = List.of(
            Map.<String, Object>of("t", 1, "val", 50),
            Map.<String, Object>of("t", 2, "val", 60));
        var c = List.of(
            Map.<String, Object>of("t", 1, "val", 50),
            Map.<String, Object>of("t", 2, "val", 55),
            Map.<String, Object>of("t", 3, "val", 60));
        double sim = DtwSimilarity.compute(q, c, SCHEMA);
        assertThat(sim).isGreaterThan(0.5);
    }

    @Test
    void singleObservation_works() {
        var q = List.of(Map.<String, Object>of("t", 1, "val", 50));
        var c = List.of(Map.<String, Object>of("t", 1, "val", 55));
        double sim = DtwSimilarity.compute(q, c, SCHEMA);
        assertThat(sim).isGreaterThan(0.9);
    }

    @Test
    void timestampFieldExcludedFromDistance() {
        var q = List.of(Map.<String, Object>of("t", 1, "val", 50));
        var c = List.of(Map.<String, Object>of("t", 29, "val", 50));
        assertThat(DtwSimilarity.compute(q, c, SCHEMA)).isEqualTo(1.0);
    }

    @Test
    void closerTrajectory_ranksHigher() {
        var query = List.of(
            Map.<String, Object>of("t", 1, "val", 30),
            Map.<String, Object>of("t", 2, "val", 60));
        var close = List.of(
            Map.<String, Object>of("t", 1, "val", 32),
            Map.<String, Object>of("t", 2, "val", 58));
        var far = List.of(
            Map.<String, Object>of("t", 1, "val", 80),
            Map.<String, Object>of("t", 2, "val", 10));
        assertThat(DtwSimilarity.compute(query, close, SCHEMA))
            .isGreaterThan(DtwSimilarity.compute(query, far, SCHEMA));
    }

    @Test
    void multiDimensional_allNumericFieldsContribute() {
        var schema = (FeatureField.TimeSeries) FeatureField.timeSeries("s", "t",
            FeatureField.numeric("t", 0, 10),
            FeatureField.numeric("x", 0, 100),
            FeatureField.numeric("y", 0, 100));
        var q = List.of(Map.<String, Object>of("t", 1, "x", 50, "y", 50));
        var cSameX = List.of(Map.<String, Object>of("t", 1, "x", 50, "y", 100));
        var cSameY = List.of(Map.<String, Object>of("t", 1, "x", 100, "y", 50));
        assertThat(DtwSimilarity.compute(q, cSameX, schema))
            .isCloseTo(DtwSimilarity.compute(q, cSameY, schema), within(0.001));
    }

    @Test
    void bothEmpty_perfectSimilarity() {
        List<Map<String, Object>> empty = List.of();
        assertThat(DtwSimilarity.compute(empty, empty, SCHEMA)).isEqualTo(1.0);
    }

    @Test
    void oneEmpty_zeroSimilarity() {
        var q = List.of(Map.<String, Object>of("t", 1, "val", 50));
        List<Map<String, Object>> empty = List.of();
        assertThat(DtwSimilarity.compute(q, empty, SCHEMA)).isEqualTo(0.0);
        assertThat(DtwSimilarity.compute(empty, q, SCHEMA)).isEqualTo(0.0);
    }
}
