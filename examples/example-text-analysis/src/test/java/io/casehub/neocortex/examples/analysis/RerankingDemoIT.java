package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.inference.tasks.RankedResult;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class RerankingDemoIT {

    private static final Path MODEL_DIR = Path.of("target/models/ms-marco-MiniLM-L-6-v2");

    private static InferenceModel model;
    private static CrossEncoderReranker reranker;

    @BeforeAll
    static void setUp() {
        model = new OnnxInferenceModel(new ModelConfig(
            MODEL_DIR.resolve("model.onnx"), MODEL_DIR.resolve("tokenizer.json")));
        reranker = new CrossEncoderReranker(model);
    }

    @AfterAll
    static void tearDown() {
        if (model != null) model.close();
    }

    @Test
    void rerankingActuallyReorders() {
        RerankingDemo.RerankResult result = RerankingDemo.run(reranker);
        List<Integer> rankedIndices = result.ranked().stream()
            .map(RankedResult::originalIndex).toList();
        List<Integer> naturalOrder = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(rankedIndices).isNotEqualTo(naturalOrder);
    }

    @Test
    void topResultIsOnnxRelated() {
        RerankingDemo.RerankResult result = RerankingDemo.run(reranker);
        String topText = result.ranked().get(0).text().toLowerCase();
        assertThat(topText).containsAnyOf("onnx", "inference", "jvm", "model");
    }

    @Test
    void scoresAreDescending() {
        RerankingDemo.RerankResult result = RerankingDemo.run(reranker);
        for (int i = 1; i < result.ranked().size(); i++) {
            assertThat(result.ranked().get(i).score())
                .isLessThanOrEqualTo(result.ranked().get(i - 1).score());
        }
    }
}
