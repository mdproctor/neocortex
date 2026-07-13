package io.casehub.neocortex.memory.cbr;

import static io.casehub.neocortex.memory.cbr.FeatureValue.*;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CbrCaseTest {

    @Test
    void textualCbrCase_valid() {
        var c = new TextualCbrCase("problem", "solution", "WIN", 0.9);
        assertThat(c.problem()).isEqualTo("problem");
        assertThat(c.solution()).isEqualTo("solution");
        assertThat(c.outcome()).isEqualTo("WIN");
        assertThat(c.confidence()).isEqualTo(0.9);
    }

    @Test
    void textualCbrCase_nullOutcomeAllowed() {
        var c = new TextualCbrCase("problem", "solution", null, null);
        assertThat(c.outcome()).isNull();
        assertThat(c.confidence()).isNull();
    }

    @Test
    void textualCbrCase_nullProblemRejected() {
        assertThatThrownBy(() -> new TextualCbrCase(null, "solution", null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void textualCbrCase_blankProblemRejected() {
        assertThatThrownBy(() -> new TextualCbrCase("  ", "solution", null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textualCbrCase_confidenceOutOfRange() {
        assertThatThrownBy(() -> new TextualCbrCase("p", "s", null, 1.1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textualCbrCase_implementsCbrCase() {
        CbrCase c = new TextualCbrCase("p", "s", null, null);
        assertThat(c.problem()).isEqualTo("p");
    }

    @Test
    void featureVectorCbrCase_valid() {
        var features = Map.<String, FeatureValue>of("race", string("Zerg"), "ratio", number(0.7));
        var c = new FeatureVectorCbrCase("problem", "solution", "WIN", 0.8, features);
        assertThat(c.features()).containsEntry("race", string("Zerg"));
    }

    @Test
    void featureVectorCbrCase_featuresDefensivelyCopied() {
        var features = new java.util.HashMap<String, FeatureValue>();
        features.put("race", string("Zerg"));
        var c = new FeatureVectorCbrCase("p", "s", null, null, features);
        features.put("extra", string("value"));
        assertThat(c.features()).doesNotContainKey("extra");
    }

    @Test
    void featureVectorCbrCase_nullFeaturesRejected() {
        assertThatThrownBy(() -> new FeatureVectorCbrCase("p", "s", null, null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void textualCbrCase_cbrType_returns_textual() {
        var c = new TextualCbrCase("problem", "solution", null, null);
        assertThat(c.cbrType()).isEqualTo("textual");
    }

    @Test
    void featureVectorCbrCase_cbrType_returns_feature_vector() {
        var c = new FeatureVectorCbrCase("p", "s", null, null, Map.of("race", string("Zerg")));
        assertThat(c.cbrType()).isEqualTo("feature-vector");
    }

    @Test
    void cbrType_constants_match_method_return() {
        assertThat(TextualCbrCase.CBR_TYPE).isEqualTo("textual");
        assertThat(FeatureVectorCbrCase.CBR_TYPE).isEqualTo("feature-vector");
        assertThat(new TextualCbrCase("p", "s", null, null).cbrType()).isEqualTo(TextualCbrCase.CBR_TYPE);
        assertThat(new FeatureVectorCbrCase("p", "s", null, null, Map.of()).cbrType()).isEqualTo(FeatureVectorCbrCase.CBR_TYPE);
    }

    @Test
    void scoredCbrCase_rejectsScoreAboveOne() {
        var c = new TextualCbrCase("p", "s", null, null);
        assertThatThrownBy(() -> new ScoredCbrCase<>(c, 1.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("[-1,1]");
    }

    @Test
    void scoredCbrCase_rejectsScoreBelowNegativeOne() {
        var c = new TextualCbrCase("p", "s", null, null);
        assertThatThrownBy(() -> new ScoredCbrCase<>(c, -1.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("[-1,1]");
    }

    @Test
    void scoredCbrCase_rejectsNaN() {
        var c = new TextualCbrCase("p", "s", null, null);
        assertThatThrownBy(() -> new ScoredCbrCase<>(c, Double.NaN))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scoredCbrCase_rejectsPositiveInfinity() {
        var c = new TextualCbrCase("p", "s", null, null);
        assertThatThrownBy(() -> new ScoredCbrCase<>(c, Double.POSITIVE_INFINITY))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scoredCbrCase_acceptsBoundaryValues() {
        var c = new TextualCbrCase("p", "s", null, null);
        assertThatCode(() -> new ScoredCbrCase<>(c, 1.0)).doesNotThrowAnyException();
        assertThatCode(() -> new ScoredCbrCase<>(c, -1.0)).doesNotThrowAnyException();
        assertThatCode(() -> new ScoredCbrCase<>(c, 0.0)).doesNotThrowAnyException();
        assertThatCode(() -> new ScoredCbrCase<>(c, 0.75)).doesNotThrowAnyException();
    }
}
