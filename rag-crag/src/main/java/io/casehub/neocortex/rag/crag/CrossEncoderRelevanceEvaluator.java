package io.casehub.neocortex.rag.crag;

import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.inference.tasks.RankedResult;
import io.casehub.neocortex.rag.RelevanceEvaluator;
import io.casehub.neocortex.rag.RelevanceGrade;

import java.util.ArrayList;
import java.util.List;

public final class CrossEncoderRelevanceEvaluator implements RelevanceEvaluator {

    private final CrossEncoderReranker reranker;
    private final double correctThreshold;
    private final double incorrectThreshold;

    public CrossEncoderRelevanceEvaluator(CrossEncoderReranker reranker,
                                          double correctThreshold,
                                          double incorrectThreshold) {
        if (reranker == null) throw new IllegalArgumentException("reranker must not be null");
        if (incorrectThreshold > correctThreshold)
            throw new IllegalArgumentException(
                "incorrectThreshold (" + incorrectThreshold
                    + ") must not exceed correctThreshold (" + correctThreshold + ")");
        this.reranker = reranker;
        this.correctThreshold = correctThreshold;
        this.incorrectThreshold = incorrectThreshold;
    }

    @Override
    public RelevanceGrade evaluate(String query, String chunkContent) {
        float score = reranker.score(query, chunkContent);
        return gradeFromScore(score);
    }

    @Override
    public List<RelevanceGrade> evaluateBatch(String query, List<String> chunkContents) {
        if (chunkContents.isEmpty()) return List.of();
        List<RankedResult> ranked = reranker.rerank(query, chunkContents);
        List<RelevanceGrade> grades = new ArrayList<>(chunkContents.size());
        for (int i = 0; i < chunkContents.size(); i++) grades.add(null);
        for (RankedResult r : ranked) {
            grades.set(r.originalIndex(), gradeFromScore(r.score()));
        }
        return List.copyOf(grades);
    }

    private RelevanceGrade gradeFromScore(float score) {
        if (score >= (float) correctThreshold) return RelevanceGrade.CORRECT;
        if (score <= (float) incorrectThreshold) return RelevanceGrade.INCORRECT;
        return RelevanceGrade.AMBIGUOUS;
    }
}
