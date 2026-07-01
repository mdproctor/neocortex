package io.casehub.neocortex.rag.testing;

import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

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

    @Inject
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
    public List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus, int maxResults, PayloadFilter filter) {
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
            case PayloadFilter.Gte gte -> {
                String v = metadata.get(gte.field());
                yield v != null && Double.parseDouble(v) >= gte.value();
            }
            case PayloadFilter.Lte lte -> {
                String v = metadata.get(lte.field());
                yield v != null && Double.parseDouble(v) <= lte.value();
            }
            case PayloadFilter.Range range -> {
                String v = metadata.get(range.field());
                yield v != null && Double.parseDouble(v) >= range.min() && Double.parseDouble(v) <= range.max();
            }
        };
    }
}
