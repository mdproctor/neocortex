package io.casehub.rag.testing;

import io.casehub.rag.CaseRetriever;
import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
import io.casehub.rag.RetrievedChunk;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryCaseRetriever implements CaseRetriever {

    private final InMemoryEmbeddingIngestor store;
    private final List<RetrievedChunk>      fixedResponse;

    public InMemoryCaseRetriever(InMemoryEmbeddingIngestor store) {
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
    public List<RetrievedChunk> retrieve(String query, CorpusRef corpus, int maxResults, PayloadFilter filter) {
        if (fixedResponse != null) {
            return fixedResponse;
        }
        List<ChunkInput> chunks = store.getChunks(corpus);
        List<RetrievedChunk> results = new ArrayList<>();
        for (ChunkInput c : chunks) {
            if (filter != null && !matches(c.metadata(), filter)) {
                continue;
            }
            results.add(new RetrievedChunk(c.content(), c.sourceDocumentId(), 1.0, c.metadata()));
            if (results.size() >= maxResults) {
                break;
            }
        }
        return Collections.unmodifiableList(results);
    }

    private static boolean matches(Map<String, String> metadata, PayloadFilter filter) {
        return switch (filter) {
            case PayloadFilter.Eq eq -> eq.value().equals(metadata.get(eq.field()));
            case PayloadFilter.In in -> metadata.containsKey(in.field()) && in.values().contains(metadata.get(in.field()));
            case PayloadFilter.Not not -> !matches(metadata, not.inner());
            case PayloadFilter.And and -> and.filters().stream().allMatch(f -> matches(metadata, f));
            case PayloadFilter.Or or -> or.filters().stream().anyMatch(f -> matches(metadata, f));
        };
    }
}
