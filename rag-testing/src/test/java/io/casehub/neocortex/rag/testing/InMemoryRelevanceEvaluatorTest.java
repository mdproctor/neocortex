package io.casehub.neocortex.rag.testing;

import io.casehub.neocortex.rag.RelevanceGrade;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRelevanceEvaluatorTest {

    @Test
    void defaultConstructorReturnsCorrect() {
        var evaluator = new InMemoryRelevanceEvaluator();
        assertThat(evaluator.evaluate("query", "content")).isEqualTo(RelevanceGrade.CORRECT);
    }

    @Test
    void returningFactoryReturnsConfiguredGrade() {
        var evaluator = InMemoryRelevanceEvaluator.returning(RelevanceGrade.INCORRECT);
        assertThat(evaluator.evaluate("query", "content")).isEqualTo(RelevanceGrade.INCORRECT);
    }

    @Test
    void evaluateBatchReturnsConfiguredGradeForAll() {
        var evaluator = InMemoryRelevanceEvaluator.returning(RelevanceGrade.AMBIGUOUS);
        List<RelevanceGrade> grades = evaluator.evaluateBatch("query",
            List.of("chunk1", "chunk2", "chunk3"));
        assertThat(grades).containsExactly(
            RelevanceGrade.AMBIGUOUS, RelevanceGrade.AMBIGUOUS, RelevanceGrade.AMBIGUOUS);
    }

    @Test
    void evaluateIgnoresQueryAndContent() {
        var evaluator = InMemoryRelevanceEvaluator.returning(RelevanceGrade.CORRECT);
        assertThat(evaluator.evaluate("any query", "any content")).isEqualTo(RelevanceGrade.CORRECT);
        assertThat(evaluator.evaluate("different", "different")).isEqualTo(RelevanceGrade.CORRECT);
    }
}
