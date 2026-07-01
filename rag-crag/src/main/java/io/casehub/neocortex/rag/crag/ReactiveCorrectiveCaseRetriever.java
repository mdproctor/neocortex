package io.casehub.neocortex.rag.crag;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.ReactiveCaseRetriever;
import io.casehub.neocortex.rag.RelevanceEvaluator;
import io.casehub.neocortex.rag.RetrievalQuality;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
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
public class ReactiveCorrectiveCaseRetriever implements ReactiveCaseRetriever {

    private final ReactiveCaseRetriever delegate;
    private final RelevanceEvaluator evaluator;
    private final CragConfig config;
    private final Event<RetrievalQuality> qualityEvent;

    @Inject
    ReactiveCorrectiveCaseRetriever(@Delegate @Any ReactiveCaseRetriever delegate,
                                    RelevanceEvaluator evaluator,
                                    CragConfig config,
                                    Event<RetrievalQuality> qualityEvent) {
        this.delegate = delegate;
        this.evaluator = evaluator;
        this.config = config;
        this.qualityEvent = qualityEvent;
    }

    @Override
    public Uni<List<RetrievedChunk>> retrieve(RetrievalQuery query, CorpusRef corpus,
                                               int maxResults, PayloadFilter filter) {
        return delegate.retrieve(query, corpus, maxResults, filter)
            .onItem().transformToUni(chunks -> {
                if (CragEvaluationLogic.isAlreadyGraded(chunks)) {
                    return Uni.createFrom().item(chunks);
                }
                return evaluateAndCorrect(query, corpus, maxResults, filter, chunks);
            });
    }

    private Uni<List<RetrievedChunk>> evaluateAndCorrect(
            RetrievalQuery query, CorpusRef corpus, int maxResults,
            PayloadFilter filter, List<RetrievedChunk> chunks) {

        return Uni.createFrom().item(() -> {
                List<String> contents = chunks.stream()
                    .map(RetrievedChunk::content).toList();
                return CragEvaluationLogic.gradeChunks(chunks,
                    evaluator.evaluateBatch(query.text(), contents));
            })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .onItem().transformToUni(initial -> {
                List<RetrievedChunk> surviving = new ArrayList<>(
                    CragEvaluationLogic.filterIncorrect(initial.graded()));

                if (!CragEvaluationLogic.needsExpansion(
                        surviving.size(), maxResults, initial.incorrect())) {
                    List<RetrievedChunk> result =
                        CragEvaluationLogic.sortAndTruncate(surviving, maxResults);
                    qualityEvent.fireAsync(CragEvaluationLogic.buildQualityEvent(
                        chunks.size(), initial.correct(), initial.ambiguous(),
                        initial.incorrect(), false));
                    return Uni.createFrom().item(result);
                }

                int expandedLimit = maxResults * config.expansionMultiplier();
                return delegate.retrieve(query, corpus, expandedLimit, filter)
                    .onItem().transformToUni(expandedChunks -> {
                        List<RetrievedChunk> newChunks =
                            CragEvaluationLogic.deduplicateExpanded(
                                expandedChunks, initial.seen());

                        if (newChunks.isEmpty()) {
                            List<RetrievedChunk> result =
                                CragEvaluationLogic.sortAndTruncate(
                                    surviving, maxResults);
                            qualityEvent.fireAsync(
                                CragEvaluationLogic.buildQualityEvent(
                                    chunks.size(), initial.correct(),
                                    initial.ambiguous(), initial.incorrect(),
                                    true));
                            return Uni.createFrom().item(result);
                        }

                        return Uni.createFrom().item(() -> {
                                List<String> newContents = newChunks.stream()
                                    .map(RetrievedChunk::content).toList();
                                return CragEvaluationLogic.gradeChunks(newChunks,
                                    evaluator.evaluateBatch(query.text(), newContents));
                            })
                            .runSubscriptionOn(
                                Infrastructure.getDefaultWorkerPool())
                            .onItem().transform(expansionResult -> {
                                surviving.addAll(
                                    CragEvaluationLogic.filterIncorrect(
                                        expansionResult.graded()));

                                int totalRetrieved =
                                    chunks.size() + newChunks.size();
                                qualityEvent.fireAsync(
                                    CragEvaluationLogic.buildQualityEvent(
                                        totalRetrieved,
                                        initial.correct()
                                            + expansionResult.correct(),
                                        initial.ambiguous()
                                            + expansionResult.ambiguous(),
                                        initial.incorrect()
                                            + expansionResult.incorrect(),
                                        true));

                                return CragEvaluationLogic.sortAndTruncate(
                                    surviving, maxResults);
                            });
                    });
            });
    }
}
