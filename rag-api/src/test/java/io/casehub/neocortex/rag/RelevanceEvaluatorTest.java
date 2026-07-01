package io.casehub.neocortex.rag;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RelevanceEvaluatorTest {

    @Test
    void evaluateBatchDefaultDelegatesToEvaluate() {
        RelevanceEvaluator evaluator = (query, chunkContent) -> {
            if (chunkContent.contains("relevant")) return RelevanceGrade.CORRECT;
            if (chunkContent.contains("maybe")) return RelevanceGrade.AMBIGUOUS;
            return RelevanceGrade.INCORRECT;
        };

        List<RelevanceGrade> grades = evaluator.evaluateBatch("query",
            List.of("relevant text", "maybe useful", "garbage"));

        assertThat(grades).containsExactly(
            RelevanceGrade.CORRECT, RelevanceGrade.AMBIGUOUS, RelevanceGrade.INCORRECT);
    }

    @Test
    void evaluateBatchEmptyListReturnsEmpty() {
        RelevanceEvaluator evaluator = (query, chunkContent) -> RelevanceGrade.CORRECT;
        List<RelevanceGrade> grades = evaluator.evaluateBatch("query", List.of());
        assertThat(grades).isEmpty();
    }

    @Test
    void evaluateBatchReturnsImmutableList() {
        RelevanceEvaluator evaluator = (query, chunkContent) -> RelevanceGrade.CORRECT;
        List<RelevanceGrade> grades = evaluator.evaluateBatch("query", List.of("text"));
        assertThat(grades).isUnmodifiable();
    }
}
