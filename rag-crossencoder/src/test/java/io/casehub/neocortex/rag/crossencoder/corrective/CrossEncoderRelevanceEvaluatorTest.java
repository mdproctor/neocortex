package io.casehub.neocortex.rag.crossencoder.corrective;

import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;
import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.rag.RelevanceGrade;
import io.casehub.neocortex.rag.crossencoder.ScoredGrade;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    void evaluateBatchWithScores_returnsGradesAndRawScores() {
        var model = contentScoringModel(Map.of(
            "correct", 0.9f, "incorrect", 0.1f, "ambiguous", 0.5f));
        var reranker = new CrossEncoderReranker(model);
        var evaluator = new CrossEncoderRelevanceEvaluator(reranker, 0.7, 0.3);

        List<ScoredGrade> results = evaluator.evaluateBatchWithScores(
            "query", List.of("correct", "incorrect", "ambiguous"));

        assertThat(results).hasSize(3);
        assertThat(results.get(0).grade()).isEqualTo(RelevanceGrade.CORRECT);
        assertThat(results.get(0).score()).isEqualTo(0.9f);
        assertThat(results.get(1).grade()).isEqualTo(RelevanceGrade.INCORRECT);
        assertThat(results.get(1).score()).isEqualTo(0.1f);
        assertThat(results.get(2).grade()).isEqualTo(RelevanceGrade.AMBIGUOUS);
        assertThat(results.get(2).score()).isEqualTo(0.5f);
    }

    @Test
    void evaluateBatchWithScores_emptyInputReturnsEmpty() {
        var model = InMemoryInferenceModel.returning(0.5f);
        var reranker = new CrossEncoderReranker(model);
        var evaluator = new CrossEncoderRelevanceEvaluator(reranker, 0.7, 0.3);

        List<ScoredGrade> results = evaluator.evaluateBatchWithScores("query", List.of());

        assertThat(results).isEmpty();
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

    private static InMemoryInferenceModel contentScoringModel(
            Map<String, Float> contentToScore) {
        return InMemoryInferenceModel.withFunction(1, input -> {
            String candidate = input.texts().get(1);
            Float score = contentToScore.get(candidate);
            return new float[]{score != null ? score : 0.0f};
        });
    }
}
