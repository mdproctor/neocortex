package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.RetrievedChunk;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class BM25IndexRegistry {

    private final TenancyStrategy tenancyStrategy;
    private final Map<String, BM25Index> indexes = new ConcurrentHashMap<>();

    @Inject
    BM25IndexRegistry(RagConfig ragConfig) {
        this.tenancyStrategy = ragConfig.tenancyStrategy();
    }

    // Test-visible constructor
    BM25IndexRegistry(TenancyStrategy strategy) {
        this.tenancyStrategy = strategy;
    }

    public void addChunk(CorpusRef corpus, String sourceDocumentId, String content,
                         Map<String, String> metadata, Map<String, List<String>> listMetadata) {
        String key = tenancyStrategy.collectionName(corpus);
        indexes.computeIfAbsent(key, k -> new BM25Index())
            .addChunk(sourceDocumentId, content, metadata, listMetadata);
    }

    public void removeDocument(CorpusRef corpus, String sourceDocumentId) {
        String key = tenancyStrategy.collectionName(corpus);
        BM25Index index = indexes.get(key);
        if (index != null) index.removeDocument(sourceDocumentId);
    }

    public List<RetrievedChunk> search(CorpusRef corpus, String query, int topK, PayloadFilter filter) {
        String key = tenancyStrategy.collectionName(corpus);
        BM25Index index = indexes.get(key);
        if (index == null) return List.of();

        List<BM25Index.ScoredEntry> entries = index.search(query, topK, filter);
        List<RetrievedChunk> results = new ArrayList<>(entries.size());
        for (var entry : entries) {
            results.add(new RetrievedChunk(entry.content(), entry.id(),
                entry.score(), entry.metadata()));
        }
        return results;
    }

    public void clear(CorpusRef corpus) {
        String key = tenancyStrategy.collectionName(corpus);
        BM25Index index = indexes.remove(key);
        if (index != null) index.clear();
    }
}
