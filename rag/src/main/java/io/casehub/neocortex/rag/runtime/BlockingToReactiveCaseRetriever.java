package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.ReactiveCaseRetriever;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
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
    public Uni<List<RetrievedChunk>> retrieve(RetrievalQuery query, CorpusRef corpus, int maxResults, PayloadFilter filter) {
        return Uni.createFrom().item(() -> delegate.retrieve(query, corpus, maxResults, filter))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
