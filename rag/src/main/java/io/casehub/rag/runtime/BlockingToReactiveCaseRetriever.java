package io.casehub.rag.runtime;

import io.casehub.rag.CaseRetriever;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
import io.casehub.rag.ReactiveCaseRetriever;
import io.casehub.rag.RetrievedChunk;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@DefaultBean
@ApplicationScoped
public class BlockingToReactiveCaseRetriever implements ReactiveCaseRetriever {

    @Inject CaseRetriever delegate;

    public BlockingToReactiveCaseRetriever() {}

    public BlockingToReactiveCaseRetriever(CaseRetriever delegate) {
        this.delegate = delegate;
    }

    @Override
    public Uni<List<RetrievedChunk>> retrieve(String query, CorpusRef corpus, int maxResults, PayloadFilter filter) {
        return Uni.createFrom().item(() -> delegate.retrieve(query, corpus, maxResults, filter))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
