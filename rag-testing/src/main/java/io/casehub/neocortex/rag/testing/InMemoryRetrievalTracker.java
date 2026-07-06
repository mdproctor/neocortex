package io.casehub.neocortex.rag.testing;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievalFeedback;
import io.casehub.neocortex.rag.RetrievalOutcome;
import io.casehub.neocortex.rag.RetrievalRecord;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.RetrievedDocumentRef;
import io.casehub.neocortex.rag.RetrievalQuery;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryRetrievalTracker implements RetrievalTracker {

    private final List<RetrievalRecord> records = new CopyOnWriteArrayList<>();
    private final Map<String, RetrievalFeedback> feedbackIndex = new ConcurrentHashMap<>();

    @Override
    public String record(RetrievalQuery query, CorpusRef corpus,
                         List<RetrievedChunk> results, int maxResults) {
        final String retrievalId = UUID.randomUUID().toString();
        final var docRefs = results.stream()
            .collect(Collectors.toMap(
                RetrievedChunk::sourceDocumentId,
                RetrievedChunk::relevanceScore, Math::max))
            .entrySet().stream()
            .map(e -> new RetrievedDocumentRef(e.getKey(), e.getValue()))
            .toList();
        records.add(new RetrievalRecord(retrievalId, query, corpus,
            docRefs, maxResults, Instant.now()));
        return retrievalId;
    }

    @Override
    public void feedback(String retrievalId, String sourceDocumentId,
                         RetrievalOutcome outcome) {
        final String key = retrievalId + "\0" + sourceDocumentId;
        feedbackIndex.put(key, new RetrievalFeedback(
            retrievalId, sourceDocumentId, outcome, Instant.now()));
    }

    @Override
    public List<RetrievalRecord> findRecords(CorpusRef corpus,
                                              Instant since, Instant until) {
        return records.stream()
            .filter(r -> r.corpus().equals(corpus))
            .filter(r -> !r.timestamp().isBefore(since) && r.timestamp().isBefore(until))
            .toList();
    }

    @Override
    public List<RetrievalFeedback> findFeedback(CorpusRef corpus,
                                                 Instant since, Instant until) {
        final Set<String> corpusIds = records.stream()
            .filter(r -> r.corpus().equals(corpus))
            .map(RetrievalRecord::retrievalId)
            .collect(Collectors.toSet());
        return feedbackIndex.values().stream()
            .filter(f -> corpusIds.contains(f.retrievalId()))
            .filter(f -> !f.timestamp().isBefore(since) && f.timestamp().isBefore(until))
            .toList();
    }

    @Override
    public Set<String> findRetrievedDocumentIds(CorpusRef corpus,
                                                 Instant since, Instant until) {
        return records.stream()
            .filter(r -> r.corpus().equals(corpus))
            .filter(r -> !r.timestamp().isBefore(since) && r.timestamp().isBefore(until))
            .flatMap(r -> r.documents().stream())
            .map(RetrievedDocumentRef::sourceDocumentId)
            .collect(Collectors.toSet());
    }

    public void clear() {
        records.clear();
        feedbackIndex.clear();
    }
}
