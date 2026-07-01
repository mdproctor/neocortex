package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.casehub.neocortex.inference.tasks.TextClassifier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class ScoringDemoIT {

    private static final Path MODEL_DIR = Path.of("target/models/distilbert-sst2");

    private static InferenceModel model;
    private static TextClassifier classifier;

    @BeforeAll
    static void setUp() {
        model = new OnnxInferenceModel(new ModelConfig(
            MODEL_DIR.resolve("model.onnx"), MODEL_DIR.resolve("tokenizer.json")));
        classifier = new TextClassifier(model, List.of("negative", "positive"));
    }

    @AfterAll
    static void tearDown() {
        if (model != null) model.close();
    }

    @Test
    void positiveTextScoresHigherThanNegative() {
        var positiveResult = classifier.classify("The new version fixes every issue I had. Excellent work.");
        var negativeResult = classifier.classify("The service was absolutely terrible, I waited two hours.");
        assertThat(positiveResult.scores().get("positive"))
            .isGreaterThan(negativeResult.scores().get("positive"));
    }

    @Test
    void sentimentDemoProducesResults() {
        List<ScoringDemo.ScoredText> results = ScoringDemo.runSentiment(classifier);
        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.score()).isBetween(0.0f, 1.0f);
            assertThat(r.label()).isIn("negative", "positive");
        });
    }
}
