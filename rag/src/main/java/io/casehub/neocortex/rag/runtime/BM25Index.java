package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.rag.PayloadFilter;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class BM25Index {

    // BM25 parameters
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // Inverted index: term -> posting list
    private final Map<String, List<Posting>> invertedIndex = new HashMap<>();

    // Parallel arrays indexed by docIndex
    private final List<String> docIds = new ArrayList<>();
    private final List<Integer> docLengths = new ArrayList<>();
    private final List<String> docContents = new ArrayList<>();
    private final List<Map<String, String>> docMetadata = new ArrayList<>();
    private final List<Map<String, List<String>>> docListMetadata = new ArrayList<>();

    // Reverse lookup for remove
    private final Map<String, Integer> docIdToIndex = new HashMap<>();

    // Average document length
    private double avgDocLength = 0.0;

    record Posting(int docIndex, int tf) {}

    record ScoredEntry(
        String id,
        double score,
        String content,
        Map<String, String> metadata,
        Map<String, List<String>> listMetadata
    ) {}

    void addChunk(String id, String content, Map<String, String> metadata, Map<String, List<String>> listMetadata) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(listMetadata, "listMetadata");

        lock.writeLock().lock();
        try {
            // Remove existing document with same id if present
            if (docIdToIndex.containsKey(id)) {
                removeDocumentInternal(id);
            }

            int docIndex = docIds.size();
            List<String> tokens = CodeDomainTokenizer.tokenize(content);
            Map<String, Integer> termFreqs = computeTermFrequencies(tokens);

            docIds.add(id);
            docLengths.add(tokens.size());
            docContents.add(content);
            docMetadata.add(Map.copyOf(metadata));
            docListMetadata.add(listMetadata.isEmpty() ? Map.of() : Map.copyOf(listMetadata));
            docIdToIndex.put(id, docIndex);

            // Update inverted index
            for (Map.Entry<String, Integer> entry : termFreqs.entrySet()) {
                String term = entry.getKey();
                int tf = entry.getValue();
                invertedIndex.computeIfAbsent(term, k -> new ArrayList<>()).add(new Posting(docIndex, tf));
            }

            // Update average document length
            avgDocLength = docLengths.stream().mapToInt(Integer::intValue).average().orElse(0.0);

        } finally {
            lock.writeLock().unlock();
        }
    }

    void removeDocument(String sourceDocumentId) {
        lock.writeLock().lock();
        try {
            removeDocumentInternal(sourceDocumentId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void removeDocumentInternal(String sourceDocumentId) {
        Integer docIndex = docIdToIndex.remove(sourceDocumentId);
        if (docIndex == null) return;

        // Remove from parallel arrays by clearing (we don't shift arrays)
        // Mark as deleted by setting id to null
        docIds.set(docIndex, null);
        docLengths.set(docIndex, 0);
        docContents.set(docIndex, null);
        docMetadata.set(docIndex, Map.of());
        docListMetadata.set(docIndex, Map.of());

        // Remove postings from inverted index
        for (List<Posting> postings : invertedIndex.values()) {
            postings.removeIf(p -> p.docIndex == docIndex);
        }

        // Recalculate average document length
        long sum = 0;
        int count = 0;
        for (int i = 0; i < docLengths.size(); i++) {
            if (docIds.get(i) != null) {
                sum += docLengths.get(i);
                count++;
            }
        }
        avgDocLength = count > 0 ? (double) sum / count : 0.0;
    }

    List<ScoredEntry> search(String query, int topK, PayloadFilter filter) {
        lock.readLock().lock();
        try {
            if (docIds.isEmpty() || query == null || query.isEmpty()) {
                return List.of();
            }

            List<String> queryTokens = CodeDomainTokenizer.tokenize(query);
            if (queryTokens.isEmpty()) return List.of();

            // Compute BM25 scores for each document
            Map<Integer, Double> docScores = new HashMap<>();
            int totalDocs = (int) docIds.stream().filter(Objects::nonNull).count();

            for (String term : queryTokens) {
                List<Posting> postings = invertedIndex.get(term);
                if (postings == null) continue;

                int df = postings.size();
                double idf = Math.log((totalDocs - df + 0.5) / (df + 0.5) + 1.0);

                for (Posting posting : postings) {
                    if (docIds.get(posting.docIndex) == null) continue; // Skip deleted docs

                    int docLength = docLengths.get(posting.docIndex);
                    double normLength = docLength / avgDocLength;
                    double tfScore = (posting.tf * (K1 + 1.0)) / (posting.tf + K1 * (1.0 - B + B * normLength));
                    double score = idf * tfScore;

                    docScores.merge(posting.docIndex, score, Double::sum);
                }
            }

            // Filter by PayloadFilter if present
            List<Map.Entry<Integer, Double>> candidates = new ArrayList<>(docScores.entrySet());
            if (filter != null) {
                candidates.removeIf(e -> !matchesFilter(e.getKey(), filter));
            }

            // Sort by score descending
            candidates.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            // Take top K
            int limit = Math.min(topK, candidates.size());
            List<ScoredEntry> results = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                Map.Entry<Integer, Double> entry = candidates.get(i);
                int docIndex = entry.getKey();
                results.add(new ScoredEntry(
                    docIds.get(docIndex),
                    entry.getValue(),
                    docContents.get(docIndex),
                    docMetadata.get(docIndex),
                    docListMetadata.get(docIndex)
                ));
            }

            return List.copyOf(results);

        } finally {
            lock.readLock().unlock();
        }
    }

    void clear() {
        lock.writeLock().lock();
        try {
            invertedIndex.clear();
            docIds.clear();
            docLengths.clear();
            docContents.clear();
            docMetadata.clear();
            docListMetadata.clear();
            docIdToIndex.clear();
            avgDocLength = 0.0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    int size() {
        lock.readLock().lock();
        try {
            return (int) docIds.stream().filter(Objects::nonNull).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean matchesFilter(int docIndex, PayloadFilter filter) {
        return switch (filter) {
            case PayloadFilter.Eq eq -> {
                String value = docMetadata.get(docIndex).get(eq.field());
                yield eq.value().equals(value);
            }
            case PayloadFilter.In in -> {
                // Check both metadata and listMetadata
                String singleValue = docMetadata.get(docIndex).get(in.field());
                if (singleValue != null && in.values().contains(singleValue)) {
                    yield true;
                }
                List<String> listValue = docListMetadata.get(docIndex).get(in.field());
                if (listValue != null) {
                    for (String val : listValue) {
                        if (in.values().contains(val)) {
                            yield true;
                        }
                    }
                }
                yield false;
            }
            case PayloadFilter.Not not -> !matchesFilter(docIndex, not.inner());
            case PayloadFilter.And and -> {
                for (PayloadFilter f : and.filters()) {
                    if (!matchesFilter(docIndex, f)) {
                        yield false;
                    }
                }
                yield true;
            }
            case PayloadFilter.Or or -> {
                for (PayloadFilter f : or.filters()) {
                    if (matchesFilter(docIndex, f)) {
                        yield true;
                    }
                }
                yield false;
            }
            case PayloadFilter.Gte gte -> false; // BM25 index does not support numeric filtering
            case PayloadFilter.Lte lte -> false; // BM25 index does not support numeric filtering
            case PayloadFilter.Range range -> false; // BM25 index does not support numeric filtering
        };
    }

    private Map<String, Integer> computeTermFrequencies(List<String> tokens) {
        Map<String, Integer> freqs = new HashMap<>();
        for (String token : tokens) {
            freqs.merge(token, 1, Integer::sum);
        }
        return freqs;
    }
}
