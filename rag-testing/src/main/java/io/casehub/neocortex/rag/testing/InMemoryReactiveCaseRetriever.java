package io.casehub.neocortex.rag.testing;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.ReactiveCaseRetriever;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.List;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryReactiveCaseRetriever implements ReactiveCaseRetriever {

    @Inject
    InMemoryCaseRetriever delegate;

    public InMemoryReactiveCaseRetriever() {}

    public InMemoryReactiveCaseRetriever(InMemoryCaseRetriever delegate) {
        this.delegate = delegate;
    }

    @Override
    public Uni<List<RetrievedChunk>> retrieve(RetrievalQuery query, CorpusRef corpus, int maxResults, PayloadFilter filter) {
        return Uni.createFrom().item(() -> delegate.retrieve(query, corpus, maxResults, filter));
    }
}
