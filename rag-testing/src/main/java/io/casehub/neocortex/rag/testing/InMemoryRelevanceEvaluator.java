package io.casehub.neocortex.rag.testing;

import io.casehub.neocortex.rag.RelevanceEvaluator;
import io.casehub.neocortex.rag.RelevanceGrade;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/**
 * In-memory stub for {@link RelevanceEvaluator} that returns a fixed {@link RelevanceGrade}
 * for all queries and chunk content.
 * <p>
 * Default constructor returns {@link RelevanceGrade#CORRECT} for all evaluations.
 * Use {@link #returning(RelevanceGrade)} factory method to configure a different grade.
 * <p>
 * This stub is automatically registered as a CDI {@code @Alternative} with {@code @Priority(1)},
 * so it will be used in tests when {@code casehub-rag-testing} is on the classpath
 * and no production {@link RelevanceEvaluator} bean exists.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryRelevanceEvaluator implements RelevanceEvaluator {

    private final RelevanceGrade fixedGrade;

    /**
     * Creates an evaluator that always returns {@link RelevanceGrade#CORRECT}.
     */
    public InMemoryRelevanceEvaluator() {
        this.fixedGrade = RelevanceGrade.CORRECT;
    }

    private InMemoryRelevanceEvaluator(RelevanceGrade grade) {
        this.fixedGrade = grade;
    }

    /**
     * Creates an evaluator that always returns the specified grade.
     *
     * @param grade the fixed grade to return for all evaluations
     * @return a new evaluator instance
     */
    public static InMemoryRelevanceEvaluator returning(RelevanceGrade grade) {
        return new InMemoryRelevanceEvaluator(grade);
    }

    @Override
    public RelevanceGrade evaluate(String query, String chunkContent) {
        return fixedGrade;
    }
}
