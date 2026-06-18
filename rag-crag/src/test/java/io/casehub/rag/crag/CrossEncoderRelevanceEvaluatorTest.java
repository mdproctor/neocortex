package io.casehub.rag.crag;

import io.casehub.inference.inmem.InMemoryInferenceModel;
import io.casehub.inference.tasks.CrossEncoderReranker;
import io.casehub.rag.RelevanceGrade;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossEncoderRelevanceEvaluatorTest {

    @Test
    void scoreAboveCorrectThresholdReturnsCorrect() {
        var evaluator = evaluatorReturningScore(0.85f, 0.7, 0.3);
        assertThat(evaluator.evaluate("query", "chunk")).isEqualTo(RelevanceGrade.CORRECT);
    }

    @Test
    void scoreAtCorrectThresholdReturnsCorrect() {
        var evaluator = evaluatorReturningScore(0.7f, 0.7, 0.3);
        assertThat(evaluator.evaluate("query", "chunk")).isEqualTo(RelevanceGrade.CORRECT);
    }

    @Test
    void scoreBelowIncorrectThresholdReturnsIncorrect() {
        var evaluator = evaluatorReturningScore(0.1f, 0.7, 0.3);
        assertThat(evaluator.evaluate("query", "chunk")).isEqualTo(RelevanceGrade.INCORRECT);
    }

    @Test
    void scoreAtIncorrectThresholdReturnsIncorrect() {
        var evaluator = evaluatorReturningScore(0.3f, 0.7, 0.3);
        assertThat(evaluator.evaluate("query", "chunk")).isEqualTo(RelevanceGrade.INCORRECT);
    }

    @Test
    void scoreBetweenThresholdsReturnsAmbiguous() {
        var evaluator = evaluatorReturningScore(0.5f, 0.7, 0.3);
        assertThat(evaluator.evaluate("query", "chunk")).isEqualTo(RelevanceGrade.AMBIGUOUS);
    }

    @Test
    void evaluateBatchUsesRerankerBatchPath() {
        var model = InMemoryInferenceModel.withFunction(1, input -> {
            String candidate = input.texts().get(1);
            return switch (candidate) {
                case "good" -> new float[]{0.9f};
                case "meh"  -> new float[]{0.5f};
                case "bad"  -> new float[]{0.1f};
                default     -> new float[]{0.0f};
            };
        });
        var reranker = new CrossEncoderReranker(model);
        var evaluator = new CrossEncoderRelevanceEvaluator(reranker, 0.7, 0.3);

        List<RelevanceGrade> grades = evaluator.evaluateBatch("query",
            List.of("good", "meh", "bad"));

        assertThat(grades).containsExactly(
            RelevanceGrade.CORRECT, RelevanceGrade.AMBIGUOUS, RelevanceGrade.INCORRECT);
    }

    @Test
    void constructorRejectsNullReranker() {
        assertThatThrownBy(() -> new CrossEncoderRelevanceEvaluator(null, 0.7, 0.3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsInvertedThresholds() {
        var model = InMemoryInferenceModel.returning(0.5f);
        var reranker = new CrossEncoderReranker(model);
        assertThatThrownBy(() -> new CrossEncoderRelevanceEvaluator(reranker, 0.3, 0.7))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static CrossEncoderRelevanceEvaluator evaluatorReturningScore(
            float score, double correctThreshold, double incorrectThreshold) {
        var model = InMemoryInferenceModel.returning(score);
        var reranker = new CrossEncoderReranker(model);
        return new CrossEncoderRelevanceEvaluator(reranker, correctThreshold, incorrectThreshold);
    }
}
