package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CbrSuggestionsTest {

    @Test void emptyConstant() {
        assertThat(CbrSuggestions.EMPTY.isEmpty()).isTrue();
        assertThat(CbrSuggestions.EMPTY.experienceCount()).isZero();
        assertThat(CbrSuggestions.EMPTY.historicalSuccessRate()).isZero();
        assertThat(CbrSuggestions.EMPTY.averageSimilarity()).isZero();
        assertThat(CbrSuggestions.EMPTY.featureStats()).isEmpty();
    }

    @Test void isEmptyWhenExperienceCountZero() {
        var suggestions = new CbrSuggestions(Map.of(), 0.5, 0, 0.8);
        assertThat(suggestions.isEmpty()).isTrue();
    }

    @Test void notEmptyWhenExperienceCountPositive() {
        var stats = FeatureStatistics.compute(new double[]{1.0, 2.0, 3.0});
        var suggestions = new CbrSuggestions(Map.of("f1", stats), 0.75, 5, 0.85);
        assertThat(suggestions.isEmpty()).isFalse();
        assertThat(suggestions.experienceCount()).isEqualTo(5);
        assertThat(suggestions.historicalSuccessRate()).isEqualTo(0.75);
        assertThat(suggestions.averageSimilarity()).isEqualTo(0.85);
    }

    @Test void featureStatsDefensivelyCopied() {
        var mutable = new HashMap<String, FeatureStatistics>();
        mutable.put("f1", FeatureStatistics.compute(new double[]{1.0}));
        var suggestions = new CbrSuggestions(mutable, 0.5, 1, 0.9);
        mutable.put("f2", FeatureStatistics.compute(new double[]{2.0}));
        assertThat(suggestions.featureStats()).doesNotContainKey("f2");
    }
}
