package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.casehub.neocortex.memory.cbr.FeatureValue.number;
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
                Map.<String, FeatureValue>of("t", number(1), "val", number(50)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(60)));
        assertThat(DtwSimilarity.compute(seq, seq, SCHEMA).score()).isEqualTo(1.0);}

    @Test
    void completelyDifferent_lowSimilarity() {
        var q = List.of(Map.<String, FeatureValue>of("t", number(1), "val", number(0)));
        var c = List.of(Map.<String, FeatureValue>of("t", number(1), "val", number(100)));
        assertThat(DtwSimilarity.compute(q, c, SCHEMA).score()).isLessThanOrEqualTo(0.5);}

    @Test
    void variableLength_dtwAligns() {
        var q = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(50)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(60)));
        var c = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(50)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(55)),
                Map.<String, FeatureValue>of("t", number(3), "val", number(60)));
        assertThat(DtwSimilarity.compute(q, c, SCHEMA).score()).isGreaterThan(0.5);}

    @Test
    void singleObservation_works() {
        var q = List.of(Map.<String, FeatureValue>of("t", number(1), "val", number(50)));
        var c = List.of(Map.<String, FeatureValue>of("t", number(1), "val", number(55)));
        assertThat(DtwSimilarity.compute(q, c, SCHEMA).score()).isGreaterThan(0.9);}

    @Test
    void timestampFieldExcludedFromDistance() {
        var q = List.of(Map.<String, FeatureValue>of("t", number(1), "val", number(50)));
        var c = List.of(Map.<String, FeatureValue>of("t", number(29), "val", number(50)));
        assertThat(DtwSimilarity.compute(q, c, SCHEMA).score()).isEqualTo(1.0);}

    @Test
    void closerTrajectory_ranksHigher() {
        var query = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(30)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(60)));
        var close = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(32)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(58)));
        var far = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(80)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(10)));
        assertThat(DtwSimilarity.compute(query, close, SCHEMA).score())
                .isGreaterThan(DtwSimilarity.compute(query, far, SCHEMA).score());}

    @Test
    void multiDimensional_allNumericFieldsContribute() {
        var schema = (FeatureField.TimeSeries) FeatureField.timeSeries("s", "t",
                                                                       FeatureField.numeric("t", 0, 10),
                                                                       FeatureField.numeric("x", 0, 100),
                                                                       FeatureField.numeric("y", 0, 100));
        var q      = List.of(Map.<String, FeatureValue>of("t", number(1), "x", number(50), "y", number(50)));
        var cSameX = List.of(Map.<String, FeatureValue>of("t", number(1), "x", number(50), "y", number(100)));
        var cSameY = List.of(Map.<String, FeatureValue>of("t", number(1), "x", number(100), "y", number(50)));
        assertThat(DtwSimilarity.compute(q, cSameX, schema).score())
                .isCloseTo(DtwSimilarity.compute(q, cSameY, schema).score(), within(0.001));}

    @Test
    void bothEmpty_perfectSimilarity() {
        List<Map<String, FeatureValue>> empty = List.of();
        assertThat(DtwSimilarity.compute(empty, empty, SCHEMA).score()).isEqualTo(1.0);}

    @Test
    void oneEmpty_zeroSimilarity() {
        var                       q     = List.of(Map.<String, FeatureValue>of("t", number(1), "val", number(50)));
        List<Map<String, FeatureValue>> empty = List.of();
        assertThat(DtwSimilarity.compute(q, empty, SCHEMA).score()).isEqualTo(0.0);
        assertThat(DtwSimilarity.compute(empty, q, SCHEMA).score()).isEqualTo(0.0);}

    @Test
    void alignmentPath_identicalSequences_diagonal() {
        var seq = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(50)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(60)));
        var result = DtwSimilarity.compute(seq, seq, SCHEMA);
        assertThat(result.alignment()).containsExactly(
                new AlignmentPair(0, 0),
                new AlignmentPair(1, 1));
    }

    @Test
    void alignmentPath_stretchedAlignment() {
        var q = List.of(Map.<String, FeatureValue>of("t", number(1), "val", number(50)));
        var c = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(50)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(50)));
        var result = DtwSimilarity.compute(q, c, SCHEMA);
        assertThat(result.alignment()).hasSize(2);
        assertThat(result.alignment().get(0).queryIndex()).isEqualTo(0);
    }

    @Test
    void alignmentPath_lengthBounds() {
        var q = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(30)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(60)),
                Map.<String, FeatureValue>of("t", number(3), "val", number(90)));
        var c = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(30)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(90)));
        var result = DtwSimilarity.compute(q, c, SCHEMA);
        assertThat(result.alignment()).hasSizeGreaterThanOrEqualTo(Math.max(q.size(), c.size()));
    }

    @Test
    void alignmentPath_bothEmpty_emptyPath() {
        List<Map<String, FeatureValue>> empty  = List.of();
        var                       result = DtwSimilarity.compute(empty, empty, SCHEMA);
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.alignment()).isEmpty();
    }

    @Test
    void alignmentPath_oneEmpty_emptyPath() {
        var                       q      = List.of(Map.<String, FeatureValue>of("t", number(1), "val", number(50)));
        List<Map<String, FeatureValue>> empty  = List.of();
        var                       result = DtwSimilarity.compute(q, empty, SCHEMA);
        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.alignment()).isEmpty();
    }

    @Test
    void windowedDtw_sameResultAsFullWhenWindowLargerThanSequence() {
        var q = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(50)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(60)));
        var c = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(52)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(58)));
        var full     = DtwSimilarity.compute(q, c, SCHEMA);
        var windowed = DtwSimilarity.compute(q, c, SCHEMA, new WarpingConstraint.SakoeChibaBand(100));
        assertThat(windowed.score()).isCloseTo(full.score(), within(0.0001));
    }

    @Test
    void windowedDtw_constrainedAlignment() {
        var q = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(10)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(20)),
                Map.<String, FeatureValue>of("t", number(3), "val", number(30)),
                Map.<String, FeatureValue>of("t", number(4), "val", number(40)),
                Map.<String, FeatureValue>of("t", number(5), "val", number(50)));
        var c = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(10)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(20)),
                Map.<String, FeatureValue>of("t", number(3), "val", number(30)),
                Map.<String, FeatureValue>of("t", number(4), "val", number(40)),
                Map.<String, FeatureValue>of("t", number(5), "val", number(50)));
        var result = DtwSimilarity.compute(q, c, SCHEMA, new WarpingConstraint.SakoeChibaBand(1));
        for (var pair : result.alignment()) {
            assertThat(Math.abs(pair.queryIndex() - pair.caseIndex())).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void windowedDtw_windowClampedForUnequalLengths() {
        var q = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(50)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(60)));
        var c = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(50)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(55)),
                Map.<String, FeatureValue>of("t", number(3), "val", number(60)),
                Map.<String, FeatureValue>of("t", number(4), "val", number(65)),
                Map.<String, FeatureValue>of("t", number(5), "val", number(70)));
        var result = DtwSimilarity.compute(q, c, SCHEMA, new WarpingConstraint.SakoeChibaBand(1));
        assertThat(result.score()).isGreaterThan(0.0);
    }

    @Test
    void itakura_identicalSequences_perfectScore() {
        var seq = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(50)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(60)));
        var result = DtwSimilarity.compute(seq, seq, SCHEMA, new WarpingConstraint.ItakuraParallelogram(2.0));
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.alignment()).hasSize(2);
    }

    @Test
    void itakura_feasible_similarSequences_positiveScore() {
        var query = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(10)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(20)),
                Map.<String, FeatureValue>of("t", number(3), "val", number(30)));
        var caseSeq = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(12)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(22)),
                Map.<String, FeatureValue>of("t", number(3), "val", number(28)));
        var result = DtwSimilarity.compute(query, caseSeq, SCHEMA, new WarpingConstraint.ItakuraParallelogram(2.0));
        assertThat(result.score()).isGreaterThan(0.5);
        assertThat(result.alignment()).isNotEmpty();
    }

    @Test
    void itakura_infeasible_extremeLengthMismatch_returnsZero() {
        var query = List.of(Map.<String, FeatureValue>of("t", number(1), "val", number(50)));
        var caseSeq = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(50)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(60)),
                Map.<String, FeatureValue>of("t", number(3), "val", number(70)),
                Map.<String, FeatureValue>of("t", number(4), "val", number(80)));
        var result = DtwSimilarity.compute(query, caseSeq, SCHEMA, new WarpingConstraint.ItakuraParallelogram(1.5));
        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.alignment()).isEmpty();
    }

    @Test
    void itakura_ceilFloorEdgeCase_n4m3_slope1_5_infeasible() {
        var query = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(10)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(20)),
                Map.<String, FeatureValue>of("t", number(3), "val", number(30)),
                Map.<String, FeatureValue>of("t", number(4), "val", number(40)));
        var caseSeq = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(10)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(20)),
                Map.<String, FeatureValue>of("t", number(3), "val", number(30)));
        var result = DtwSimilarity.compute(query, caseSeq, SCHEMA, new WarpingConstraint.ItakuraParallelogram(1.5));
        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.alignment()).isEmpty();
    }

    @Test
    void itakura_feasible_unequalLengths_withWideSlope() {
        var query = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(10)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(20)),
                Map.<String, FeatureValue>of("t", number(3), "val", number(30)));
        var caseSeq = List.of(
                Map.<String, FeatureValue>of("t", number(1), "val", number(12)),
                Map.<String, FeatureValue>of("t", number(2), "val", number(22)),
                Map.<String, FeatureValue>of("t", number(3), "val", number(28)),
                Map.<String, FeatureValue>of("t", number(4), "val", number(35)));
        var result = DtwSimilarity.compute(query, caseSeq, SCHEMA, new WarpingConstraint.ItakuraParallelogram(3.0));
        assertThat(result.score()).isGreaterThan(0.0);
        assertThat(result.alignment()).isNotEmpty();
    }


    @Test
    void earlyAbandon_tightThreshold_returnsZero() {
        var query   = List.of(obs(1, 0.0), obs(2, 0.0));
        var caseSeq = List.of(obs(1, 100.0), obs(2, 100.0));
        DtwResult result = DtwSimilarity.compute(query, caseSeq, SCHEMA,
                                                 new WarpingConstraint.Unconstrained(), 0.001);
        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.alignment()).isEmpty();
    }

    @Test
    void earlyAbandon_looseThreshold_completesNormally() {
        var obs = obs(1, 50.0);
        DtwResult result = DtwSimilarity.compute(List.of(obs), List.of(obs), SCHEMA,
                                                 new WarpingConstraint.Unconstrained(), 1000.0);
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.alignment()).isNotEmpty();
    }

    @Test
    void earlyAbandon_infinity_behavesLikeNoAbandon() {
        var       query     = List.of(obs(1, 10.0));
        var       caseSeq   = List.of(obs(1, 90.0));
        DtwResult noAbandon = DtwSimilarity.compute(query, caseSeq, SCHEMA);
        DtwResult withInf = DtwSimilarity.compute(query, caseSeq, SCHEMA,
                                                  new WarpingConstraint.Unconstrained(), Double.POSITIVE_INFINITY);
        assertThat(withInf.score()).isEqualTo(noAbandon.score());
    }

    @Test
    void earlyAbandon_worksWithSakoeChibaBand() {
        var query   = List.of(obs(1, 0.0), obs(2, 0.0), obs(3, 0.0));
        var caseSeq = List.of(obs(1, 100.0), obs(2, 100.0), obs(3, 100.0));
        DtwResult result = DtwSimilarity.compute(query, caseSeq, SCHEMA,
                                                 new WarpingConstraint.SakoeChibaBand(1), 0.001);
        assertThat(result.score()).isEqualTo(0.0);
    }

    private static Map<String, FeatureValue> obs(double time, double val) {
        return Map.of("t", FeatureValue.number(time), "val", FeatureValue.number(val));
    }
}
