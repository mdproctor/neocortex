package io.casehub.neocortex.rag.testing;

import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryEmbeddingIngestor implements EmbeddingIngestor {

    private final Map<CorpusRef, Map<String, List<ChunkInput>>> data = new ConcurrentHashMap<>();

    @Override
    public void ingest(CorpusRef corpus, List<ChunkInput> chunks) {
        data.compute(corpus, (key, docs) -> {
            if (docs == null) docs = new LinkedHashMap<>();
            for (ChunkInput chunk : chunks) {
                docs.computeIfAbsent(chunk.sourceDocumentId(), k -> new ArrayList<>()).add(chunk);
            }
            return docs;
        });
    }

    @Override
    public void deleteDocument(CorpusRef corpus, String sourceDocumentId) {
        data.computeIfPresent(corpus, (key, docs) -> {
            docs.remove(sourceDocumentId);
            return docs.isEmpty() ? null : docs;
        });
    }

    @Override
    public void deleteCorpus(CorpusRef corpus) {
        data.remove(corpus);
    }

    @Override
    public List<String> listDocuments(CorpusRef corpus) {
        var docs = data.get(corpus);
        if (docs == null) return List.of();
        synchronized (docs) {
            return List.copyOf(docs.keySet());
        }
    }

    public List<ChunkInput> getChunks(CorpusRef corpus) {
        var docs = data.get(corpus);
        if (docs == null) return List.of();
        synchronized (docs) {
            List<ChunkInput> all = new ArrayList<>();
            docs.values().forEach(all::addAll);
            return Collections.unmodifiableList(all);
        }
    }
}
