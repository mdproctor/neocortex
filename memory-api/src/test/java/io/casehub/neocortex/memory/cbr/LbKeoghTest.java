package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class LbKeoghTest {

    private static final FeatureField.TimeSeries SCHEMA = (FeatureField.TimeSeries)
        FeatureField.timeSeries("ts", "t",
            FeatureField.numeric("t", 0, 10),
            FeatureField.numeric("val", 0, 100));

    private static Map<String, FeatureValue> obs(double t, double val) {
        return Map.of("t", FeatureValue.number(t), "val", FeatureValue.number(val));
    }

    @Test void envelope_singlePoint_upperEqualsLower() {
        var env = LbKeogh.computeEnvelope(List.of(obs(1, 50)), SCHEMA, 1);
        assertThat(env.length()).isEqualTo(1);
        assertThat(env.dimensions()).isEqualTo(1);
        assertThat(env.upper()[0][0]).isEqualTo(env.lower()[0][0]);
    }

    @Test void envelope_multiplePoints_windowExpands() {
        var env = LbKeogh.computeEnvelope(List.of(obs(1, 10), obs(2, 50), obs(3, 90)), SCHEMA, 1);
        assertThat(env.upper()[0][0]).isGreaterThan(env.lower()[0][0]);
        assertThat(env.upper()[1][0]).isGreaterThan(env.upper()[0][0]);
    }

    @Test void lowerBound_identicalSequences_returnsZero() {
        var seq = List.of(obs(1, 50), obs(2, 60));
        var env = LbKeogh.computeEnvelope(seq, SCHEMA, 1);
        assertThat(LbKeogh.lowerBound(seq, env, SCHEMA)).isEqualTo(0.0);
    }

    @Test void lowerBound_leqFullDtw() {
        var query = List.of(obs(1, 10), obs(2, 20), obs(3, 30));
        var caseSeq = List.of(obs(1, 80), obs(2, 70), obs(3, 60));
        int w = 1;
        var env = LbKeogh.computeEnvelope(caseSeq, SCHEMA, w);
        double lb = LbKeogh.lowerBound(query, env, SCHEMA);
        DtwResult dtw = DtwSimilarity.compute(query, caseSeq, SCHEMA, new WarpingConstraint.SakoeChibaBand(w));
        double dtwCost = (dtw.score() > 0) ? (1.0 / dtw.score() - 1.0) * Math.max(3, 3) : Double.MAX_VALUE;
        assertThat(lb).isLessThanOrEqualTo(dtwCost + 1e-9);
    }

    @Test void lowerBound_differentLengths_handled() {
        var query = List.of(obs(1, 50), obs(2, 60));
        var caseSeq = List.of(obs(1, 50), obs(2, 60), obs(3, 70), obs(4, 80));
        var env = LbKeogh.computeEnvelope(caseSeq, SCHEMA, 1);
        assertThat(LbKeogh.lowerBound(query, env, SCHEMA)).isGreaterThanOrEqualTo(0.0);
    }

    @Test void lowerBound_emptyQuery_returnsZero() {
        var env = LbKeogh.computeEnvelope(List.of(obs(1, 50)), SCHEMA, 1);
        assertThat(LbKeogh.lowerBound(List.of(), env, SCHEMA)).isEqualTo(0.0);
    }

    @Test void envelope_emptySequence_returnsEmpty() {
        var env = LbKeogh.computeEnvelope(List.of(), SCHEMA, 1);
        assertThat(env.length()).isEqualTo(0);
    }

    @Test void lowerBound_multiDimensional_sumsContributions() {
        var multiSchema = (FeatureField.TimeSeries) FeatureField.timeSeries("ts", "t",
            FeatureField.numeric("t", 0, 10),
            FeatureField.numeric("x", 0, 100),
            FeatureField.numeric("y", 0, 100));
        var query = List.of(Map.<String, FeatureValue>of("t", FeatureValue.number(1), "x", FeatureValue.number(0), "y", FeatureValue.number(0)));
        var caseSeq = List.of(Map.<String, FeatureValue>of("t", FeatureValue.number(1), "x", FeatureValue.number(100), "y", FeatureValue.number(100)));
        var env = LbKeogh.computeEnvelope(caseSeq, multiSchema, 1);
        assertThat(LbKeogh.lowerBound(query, env, multiSchema)).isGreaterThan(0.0);
    }
}
