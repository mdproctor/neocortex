package io.casehub.neocortex.corpus.management.cdi;

import io.casehub.neocortex.corpus.management.CorpusEntry;
import io.casehub.neocortex.corpus.management.CorpusEntryStore;
import io.casehub.neocortex.rag.CorpusRef;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@DefaultBean
@ApplicationScoped
public class NoOpCorpusEntryStore implements CorpusEntryStore {

    @Override
    public String add(CorpusRef corpus, CorpusEntry entry) {
        return entry.id();
    }

    @Override
    public void update(CorpusRef corpus, String entryId, CorpusEntry entry) {}

    @Override
    public void remove(CorpusRef corpus, String entryId) {}

    @Override
    public Optional<CorpusEntry> get(CorpusRef corpus, String entryId) {
        return Optional.empty();
    }
}
