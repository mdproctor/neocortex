package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;
import io.casehub.neocortex.inference.tasks.TextClassifier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("smoke")
class ScoringDemoTest {

    @Test
    void sentimentDimensionProducesResults() {
        var model = InMemoryInferenceModel.returning(0.1f, 0.9f);
        var classifier = new TextClassifier(model, List.of("negative", "positive"));

        List<ScoringDemo.ScoredText> results = ScoringDemo.runSentiment(classifier);

        assertThat(results).hasSizeGreaterThanOrEqualTo(3);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.text()).isNotBlank();
            assertThat(r.label()).isNotBlank();
            assertThat(r.score()).isBetween(0.0f, 1.0f);
        });
    }

    @Test
    void scoresSpanRange() {
        var model = InMemoryInferenceModel.withFunction(2, input -> {
            String text = input.texts().get(0);
            if (text.contains("terrible")) return new float[]{2.0f, -1.0f};
            if (text.contains("excellent") || text.contains("Excellent")) return new float[]{-1.0f, 2.0f};
            return new float[]{0.1f, 0.1f};
        });
        var classifier = new TextClassifier(model, List.of("negative", "positive"));

        List<ScoringDemo.ScoredText> results = ScoringDemo.runSentiment(classifier);

        var scores = results.stream().map(ScoringDemo.ScoredText::score).toList();
        float min = scores.stream().min(Float::compare).orElseThrow();
        float max = scores.stream().max(Float::compare).orElseThrow();
        assertThat(max - min).isGreaterThan(0.1f);
    }
}
