package io.casehub.neocortex.rag.tracking;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.ReactiveRetrievalTracker;
import io.casehub.neocortex.rag.RetrievalFeedback;
import io.casehub.neocortex.rag.RetrievalOutcome;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievalRecord;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@DefaultBean
@ApplicationScoped
public class BlockingToReactiveRetrievalTracker implements ReactiveRetrievalTracker {

    @Inject RetrievalTracker delegate;

    public BlockingToReactiveRetrievalTracker() {}

    public BlockingToReactiveRetrievalTracker(RetrievalTracker delegate) {
        this.delegate = delegate;
    }

    @Override
    public Uni<String> record(RetrievalQuery query, CorpusRef corpus,
                              List<RetrievedChunk> results, int maxResults) {
        return Uni.createFrom().item(() -> delegate.record(query, corpus, results, maxResults))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> feedback(String retrievalId, String sourceDocumentId,
                              RetrievalOutcome outcome) {
        return Uni.createFrom().voidItem()
            .invoke(() -> delegate.feedback(retrievalId, sourceDocumentId, outcome))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<List<RetrievalRecord>> findRecords(CorpusRef corpus,
                                                   Instant since, Instant until) {
        return Uni.createFrom().item(() -> delegate.findRecords(corpus, since, until))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<List<RetrievalFeedback>> findFeedback(CorpusRef corpus,
                                                      Instant since, Instant until) {
        return Uni.createFrom().item(() -> delegate.findFeedback(corpus, since, until))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Set<String>> findRetrievedDocumentIds(CorpusRef corpus,
                                                      Instant since, Instant until) {
        return Uni.createFrom().item(() -> delegate.findRetrievedDocumentIds(corpus, since, until))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Integer> purgeOlderThan(Instant cutoff) {
        return Uni.createFrom().item(() -> delegate.purgeOlderThan(cutoff))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
