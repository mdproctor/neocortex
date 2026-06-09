package io.casehub.rag.testing;

import io.casehub.rag.CaseRetriever;
import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.RetrievedChunk;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Alternative
@Priority(1)
public class InMemoryCaseRetriever implements CaseRetriever {

    private final InMemoryCorpusStore store;
    private final List<RetrievedChunk> fixedResponse;

    public InMemoryCaseRetriever(InMemoryCorpusStore store) {
        this.store = store;
        this.fixedResponse = null;
    }

    private InMemoryCaseRetriever(List<RetrievedChunk> fixedResponse) {
        this.store = null;
        this.fixedResponse = List.copyOf(fixedResponse);
    }

    public static InMemoryCaseRetriever returning(List<RetrievedChunk> fixedResponse) {
        return new InMemoryCaseRetriever(fixedResponse);
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, CorpusRef corpus, int maxResults) {
        if (fixedResponse != null) {
            return fixedResponse;
        }
        List<ChunkInput> chunks = store.getChunks(corpus);
        int limit = Math.min(maxResults, chunks.size());
        List<RetrievedChunk> results = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            ChunkInput c = chunks.get(i);
            results.add(new RetrievedChunk(c.content(), c.sourceDocumentId(), 1.0, c.metadata()));
        }
        return Collections.unmodifiableList(results);
    }
}
