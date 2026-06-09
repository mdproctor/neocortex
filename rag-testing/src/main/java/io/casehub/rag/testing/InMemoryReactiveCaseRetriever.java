package io.casehub.rag.testing;

import io.casehub.rag.CorpusRef;
import io.casehub.rag.ReactiveCaseRetriever;
import io.casehub.rag.RetrievedChunk;
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
    public Uni<List<RetrievedChunk>> retrieve(String query, CorpusRef corpus, int maxResults) {
        return Uni.createFrom().item(() -> delegate.retrieve(query, corpus, maxResults));
    }
}
