package io.casehub.neocortex.rag.crag;

import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.RelevanceEvaluator;
import io.casehub.neocortex.rag.RetrievalQuality;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
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
        var initial = CragEvaluationLogic.gradeChunks(chunks,
            evaluator.evaluateBatch(query.text(), contents));
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
                var expansionResult = CragEvaluationLogic.gradeChunks(newChunks,
                    evaluator.evaluateBatch(query.text(), newContents));

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
}
