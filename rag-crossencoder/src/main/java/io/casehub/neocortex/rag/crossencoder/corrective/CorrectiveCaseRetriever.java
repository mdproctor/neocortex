package io.casehub.neocortex.rag.crossencoder.corrective;

import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.RelevanceEvaluator;
import io.casehub.neocortex.rag.RelevanceGrade;
import io.casehub.neocortex.rag.RetrievalQuality;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.crossencoder.ScoredGrade;
import io.casehub.neocortex.rag.crossencoder.reranking.RerankingLogic;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

@Decorator
@Priority(100)
@IfBuildProperty(name = "casehub.rag.crag.enabled", stringValue = "true")
public class CorrectiveCaseRetriever implements CaseRetriever {

    private final CaseRetriever delegate;
    private final RelevanceEvaluator evaluator;
    private final CragConfig config;
    private final Event<RetrievalQuality> qualityEvent;

    @Inject
    CorrectiveCaseRetriever(@Delegate @Any CaseRetriever delegate,
                            RelevanceEvaluator evaluator,
                            CragConfig config,
                            Event<RetrievalQuality> qualityEvent) {
        this.delegate = delegate;
        this.evaluator = evaluator;
        this.config = config;
        this.qualityEvent = qualityEvent;
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus,
                                          int maxResults, PayloadFilter filter) {
        List<RetrievedChunk> chunks = delegate.retrieve(query, corpus, maxResults, filter);

        if (CragEvaluationLogic.isAlreadyGraded(chunks)) {
            return chunks;
        }

        List<String> contents = chunks.stream().map(RetrievedChunk::content).toList();
        List<RelevanceGrade> grades;
        List<RetrievedChunk> gradedInput = chunks;

        if (evaluator instanceof CrossEncoderRelevanceEvaluator ceEval) {
            var scored = ceEval.evaluateBatchWithScores(query.text(), contents);
            grades = scored.stream().map(ScoredGrade::grade).toList();
            float[] scores = extractScores(scored);
            gradedInput = RerankingLogic.attachScores(chunks, scores);
        } else {
            grades = evaluator.evaluateBatch(query.text(), contents);
        }

        var initial = CragEvaluationLogic.gradeChunks(gradedInput, grades);
        int totalRetrieved = chunks.size();

        List<RetrievedChunk> surviving = new ArrayList<>(
            CragEvaluationLogic.filterIncorrect(initial.graded()));

        boolean expanded = false;
        int correct = initial.correct(), ambiguous = initial.ambiguous(),
            incorrect = initial.incorrect();

        if (CragEvaluationLogic.needsExpansion(
                surviving.size(), maxResults, initial.incorrect())) {
            expanded = true;
            int expandedLimit = maxResults * config.expansionMultiplier();
            List<RetrievedChunk> expandedChunks = delegate.retrieve(
                query, corpus, expandedLimit, filter);

            List<RetrievedChunk> newChunks =
                CragEvaluationLogic.deduplicateExpanded(expandedChunks, initial.seen());

            if (!newChunks.isEmpty()) {
                List<String> newContents = newChunks.stream()
                    .map(RetrievedChunk::content).toList();
                List<RelevanceGrade> newGrades;
                List<RetrievedChunk> newGradedInput = newChunks;

                if (evaluator instanceof CrossEncoderRelevanceEvaluator ceEval) {
                    var scored = ceEval.evaluateBatchWithScores(query.text(), newContents);
                    newGrades = scored.stream().map(ScoredGrade::grade).toList();
                    float[] newScores = extractScores(scored);
                    newGradedInput = RerankingLogic.attachScores(newChunks, newScores);
                } else {
                    newGrades = evaluator.evaluateBatch(query.text(), newContents);
                }

                var expansionResult = CragEvaluationLogic.gradeChunks(
                    newGradedInput, newGrades);

                totalRetrieved += newChunks.size();
                correct += expansionResult.correct();
                ambiguous += expansionResult.ambiguous();
                incorrect += expansionResult.incorrect();

                surviving.addAll(
                    CragEvaluationLogic.filterIncorrect(expansionResult.graded()));
            }
        }

        List<RetrievedChunk> result =
            CragEvaluationLogic.sortAndTruncate(surviving, maxResults);

        qualityEvent.fire(CragEvaluationLogic.buildQualityEvent(
            totalRetrieved, correct, ambiguous, incorrect, expanded));

        return result;
    }

    private static float[] extractScores(List<ScoredGrade> scored) {
        float[] scores = new float[scored.size()];
        for (int i = 0; i < scored.size(); i++) {
            scores[i] = scored.get(i).score();
        }
        return scores;
    }
}
