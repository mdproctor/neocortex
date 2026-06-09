package io.casehub.rag.testing;

import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.ReactiveCorpusStore;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.List;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryReactiveCorpusStore implements ReactiveCorpusStore {

    @Inject
    InMemoryCorpusStore delegate;

    public InMemoryReactiveCorpusStore() {}

    public InMemoryReactiveCorpusStore(InMemoryCorpusStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public Uni<Void> ingest(CorpusRef corpus, List<ChunkInput> chunks) {
        return Uni.createFrom().item(corpus)
            .invoke(c -> delegate.ingest(c, chunks))
            .replaceWithVoid();
    }

    @Override
    public Uni<Void> deleteDocument(CorpusRef corpus, String sourceDocumentId) {
        return Uni.createFrom().item(corpus)
            .invoke(c -> delegate.deleteDocument(c, sourceDocumentId))
            .replaceWithVoid();
    }

    @Override
    public Uni<Void> deleteCorpus(CorpusRef corpus) {
        return Uni.createFrom().item(corpus)
            .invoke(c -> delegate.deleteCorpus(c))
            .replaceWithVoid();
    }

    @Override
    public Uni<List<String>> listDocuments(CorpusRef corpus) {
        return Uni.createFrom().item(() -> delegate.listDocuments(corpus));
    }
}
