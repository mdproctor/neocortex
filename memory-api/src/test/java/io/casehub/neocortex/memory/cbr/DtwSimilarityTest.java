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
        assertThat(DtwSimilarity.compute(seq, seq, SCHEMA).score()).isEqualTo(1.0);}

    @Test
    void completelyDifferent_lowSimilarity() {
        var q = List.of(Map.<String, Object>of("t", 1, "val", 0));
        var c = List.of(Map.<String, Object>of("t", 1, "val", 100));
        assertThat(DtwSimilarity.compute(q, c, SCHEMA).score()).isLessThanOrEqualTo(0.5);}

    @Test
    void variableLength_dtwAligns() {
        var q = List.of(
                Map.<String, Object>of("t", 1, "val", 50),
                Map.<String, Object>of("t", 2, "val", 60));
        var c = List.of(
                Map.<String, Object>of("t", 1, "val", 50),
                Map.<String, Object>of("t", 2, "val", 55),
                Map.<String, Object>of("t", 3, "val", 60));
        assertThat(DtwSimilarity.compute(q, c, SCHEMA).score()).isGreaterThan(0.5);}

    @Test
    void singleObservation_works() {
        var q = List.of(Map.<String, Object>of("t", 1, "val", 50));
        var c = List.of(Map.<String, Object>of("t", 1, "val", 55));
        assertThat(DtwSimilarity.compute(q, c, SCHEMA).score()).isGreaterThan(0.9);}

    @Test
    void timestampFieldExcludedFromDistance() {
        var q = List.of(Map.<String, Object>of("t", 1, "val", 50));
        var c = List.of(Map.<String, Object>of("t", 29, "val", 50));
        assertThat(DtwSimilarity.compute(q, c, SCHEMA).score()).isEqualTo(1.0);}

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
        assertThat(DtwSimilarity.compute(query, close, SCHEMA).score())
                .isGreaterThan(DtwSimilarity.compute(query, far, SCHEMA).score());}

    @Test
    void multiDimensional_allNumericFieldsContribute() {
        var schema = (FeatureField.TimeSeries) FeatureField.timeSeries("s", "t",
                                                                       FeatureField.numeric("t", 0, 10),
                                                                       FeatureField.numeric("x", 0, 100),
                                                                       FeatureField.numeric("y", 0, 100));
        var q      = List.of(Map.<String, Object>of("t", 1, "x", 50, "y", 50));
        var cSameX = List.of(Map.<String, Object>of("t", 1, "x", 50, "y", 100));
        var cSameY = List.of(Map.<String, Object>of("t", 1, "x", 100, "y", 50));
        assertThat(DtwSimilarity.compute(q, cSameX, schema).score())
                .isCloseTo(DtwSimilarity.compute(q, cSameY, schema).score(), within(0.001));}

    @Test
    void bothEmpty_perfectSimilarity() {
        List<Map<String, Object>> empty = List.of();
        assertThat(DtwSimilarity.compute(empty, empty, SCHEMA).score()).isEqualTo(1.0);}

    @Test
    void oneEmpty_zeroSimilarity() {
        var                       q     = List.of(Map.<String, Object>of("t", 1, "val", 50));
        List<Map<String, Object>> empty = List.of();
        assertThat(DtwSimilarity.compute(q, empty, SCHEMA).score()).isEqualTo(0.0);
        assertThat(DtwSimilarity.compute(empty, q, SCHEMA).score()).isEqualTo(0.0);}

    @Test
    void alignmentPath_identicalSequences_diagonal() {
        var seq = List.of(
                Map.<String, Object>of("t", 1, "val", 50),
                Map.<String, Object>of("t", 2, "val", 60));
        var result = DtwSimilarity.compute(seq, seq, SCHEMA);
        assertThat(result.alignment()).containsExactly(
                new AlignmentPair(0, 0),
                new AlignmentPair(1, 1));
    }

    @Test
    void alignmentPath_stretchedAlignment() {
        var q = List.of(Map.<String, Object>of("t", 1, "val", 50));
        var c = List.of(
                Map.<String, Object>of("t", 1, "val", 50),
                Map.<String, Object>of("t", 2, "val", 50));
        var result = DtwSimilarity.compute(q, c, SCHEMA);
        assertThat(result.alignment()).hasSize(2);
        assertThat(result.alignment().get(0).queryIndex()).isEqualTo(0);
    }

    @Test
    void alignmentPath_lengthBounds() {
        var q = List.of(
                Map.<String, Object>of("t", 1, "val", 30),
                Map.<String, Object>of("t", 2, "val", 60),
                Map.<String, Object>of("t", 3, "val", 90));
        var c = List.of(
                Map.<String, Object>of("t", 1, "val", 30),
                Map.<String, Object>of("t", 2, "val", 90));
        var result = DtwSimilarity.compute(q, c, SCHEMA);
        assertThat(result.alignment()).hasSizeGreaterThanOrEqualTo(Math.max(q.size(), c.size()));
    }

    @Test
    void alignmentPath_bothEmpty_emptyPath() {
        List<Map<String, Object>> empty  = List.of();
        var                       result = DtwSimilarity.compute(empty, empty, SCHEMA);
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.alignment()).isEmpty();
    }

    @Test
    void alignmentPath_oneEmpty_emptyPath() {
        var                       q      = List.of(Map.<String, Object>of("t", 1, "val", 50));
        List<Map<String, Object>> empty  = List.of();
        var                       result = DtwSimilarity.compute(q, empty, SCHEMA);
        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.alignment()).isEmpty();
    }

    @Test
    void windowedDtw_sameResultAsFullWhenWindowLargerThanSequence() {
        var q = List.of(
                Map.<String, Object>of("t", 1, "val", 50),
                Map.<String, Object>of("t", 2, "val", 60));
        var c = List.of(
                Map.<String, Object>of("t", 1, "val", 52),
                Map.<String, Object>of("t", 2, "val", 58));
        var full     = DtwSimilarity.compute(q, c, SCHEMA);
        var windowed = DtwSimilarity.compute(q, c, SCHEMA, 100);
        assertThat(windowed.score()).isCloseTo(full.score(), within(0.0001));
    }

    @Test
    void windowedDtw_constrainedAlignment() {
        var q = List.of(
                Map.<String, Object>of("t", 1, "val", 10),
                Map.<String, Object>of("t", 2, "val", 20),
                Map.<String, Object>of("t", 3, "val", 30),
                Map.<String, Object>of("t", 4, "val", 40),
                Map.<String, Object>of("t", 5, "val", 50));
        var c = List.of(
                Map.<String, Object>of("t", 1, "val", 10),
                Map.<String, Object>of("t", 2, "val", 20),
                Map.<String, Object>of("t", 3, "val", 30),
                Map.<String, Object>of("t", 4, "val", 40),
                Map.<String, Object>of("t", 5, "val", 50));
        var result = DtwSimilarity.compute(q, c, SCHEMA, 1);
        for (var pair : result.alignment()) {
            assertThat(Math.abs(pair.queryIndex() - pair.caseIndex())).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void windowedDtw_windowClampedForUnequalLengths() {
        var q = List.of(
                Map.<String, Object>of("t", 1, "val", 50),
                Map.<String, Object>of("t", 2, "val", 60));
        var c = List.of(
                Map.<String, Object>of("t", 1, "val", 50),
                Map.<String, Object>of("t", 2, "val", 55),
                Map.<String, Object>of("t", 3, "val", 60),
                Map.<String, Object>of("t", 4, "val", 65),
                Map.<String, Object>of("t", 5, "val", 70));
        var result = DtwSimilarity.compute(q, c, SCHEMA, 1);
        assertThat(result.score()).isGreaterThan(0.0);
    }
}
