package io.casehub.neocortex.rag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RetrievalAnalyzer {

    private RetrievalAnalyzer() {}

    public static Map<String, DocumentStats> documentStats(
            RetrievalTracker tracker,
            CorpusRef corpus,
            Instant since, Instant until) {

        List<RetrievalRecord> records = tracker.findRecords(corpus, since, until);
        if (records.isEmpty()) {
            return Map.of();
        }

        List<RetrievalFeedback> allFeedback = tracker.findFeedback(corpus, since, Instant.MAX);

        Set<String> inWindowRetrievalIds = new HashSet<>();
        for (RetrievalRecord r : records) {
            inWindowRetrievalIds.add(r.retrievalId());
        }

        Map<String, Map<RetrievalOutcome, Integer>> feedbackByDoc = new HashMap<>();
        for (RetrievalFeedback fb : allFeedback) {
            if (inWindowRetrievalIds.contains(fb.retrievalId())) {
                feedbackByDoc
                        .computeIfAbsent(fb.sourceDocumentId(), k -> new EnumMap<>(RetrievalOutcome.class))
                        .merge(fb.outcome(), 1, Integer::sum);
            }
        }

        Map<String, List<DocAppearance>> appearances = new HashMap<>();
        for (RetrievalRecord r : records) {
            for (RetrievedDocumentRef doc : r.documents()) {
                appearances.computeIfAbsent(doc.sourceDocumentId(), k -> new ArrayList<>())
                        .add(new DocAppearance(r.timestamp(), doc.relevanceScore()));
            }
        }

        Map<String, DocumentStats> result = new LinkedHashMap<>();
        for (var entry : appearances.entrySet()) {
            String docId = entry.getKey();
            List<DocAppearance> apps = entry.getValue();

            int count = apps.size();
            Instant first = apps.stream().map(DocAppearance::timestamp).min(Comparator.naturalOrder()).orElseThrow();
            Instant last = apps.stream().map(DocAppearance::timestamp).max(Comparator.naturalOrder()).orElseThrow();
            double avgScore = apps.stream().mapToDouble(DocAppearance::score).average().orElse(0.0);
            Map<RetrievalOutcome, Integer> dist = feedbackByDoc.getOrDefault(docId, Map.of());

            result.put(docId, new DocumentStats(docId, count, first, last, avgScore, dist));
        }

        return result;
    }

    public static Set<String> unretrievedDocuments(
            RetrievalTracker tracker,
            EmbeddingIngestor ingestor,
            CorpusRef corpus,
            Instant since, Instant until) {

        Set<String>  retrieved    = tracker.findRetrievedDocumentIds(corpus, since, until);
        List<String> allDocuments = ingestor.listDocuments(corpus);

        Set<String> unretrieved = new LinkedHashSet<>();
        for (String docId : allDocuments) {
            if (!retrieved.contains(docId)) {
                unretrieved.add(docId);
            }
        }
        return unretrieved;
    }

    public static List<DocumentQualitySignal> qualitySignals(
            RetrievalTracker tracker,
            EmbeddingIngestor ingestor,
            CorpusRef corpus,
            Instant since, Instant until,
            QualityThresholds thresholds) {

        Map<String, DocumentStats> stats       = documentStats(tracker, corpus, since, until);
        Set<String>                unretrieved = unretrievedDocuments(tracker, ingestor, corpus, since, until);

        List<DocumentQualitySignal> neverRetrievedSignals = new ArrayList<>();
        List<DocumentQualitySignal> lowQualitySignals     = new ArrayList<>();
        List<DocumentQualitySignal> staleSignals          = new ArrayList<>();

        for (String docId : unretrieved) {
            neverRetrievedSignals.add(
                    new DocumentQualitySignal(docId, null, QualitySignal.NEVER_RETRIEVED));
        }

        Instant staleCutoff = until.minus(thresholds.staleWindow());

        for (var entry : stats.entrySet()) {
            String        docId = entry.getKey();
            DocumentStats ds    = entry.getValue();

            if (ds.retrievalCount() >= thresholds.minRetrievalsForQualityCheck()) {
                int totalFeedback = ds.feedbackDistribution().values().stream()
                                      .mapToInt(Integer::intValue).sum();
                if (totalFeedback >= thresholds.minFeedbackForQualityCheck()) {
                    int lowCount = ds.feedbackDistribution()
                                     .getOrDefault(RetrievalOutcome.NOT_RELEVANT, 0)
                                   + ds.feedbackDistribution()
                                       .getOrDefault(RetrievalOutcome.PARTIALLY_RELEVANT, 0);
                    double ratio = (double) lowCount / totalFeedback;
                    if (ratio >= thresholds.lowQualityRatio()) {
                        lowQualitySignals.add(
                                new DocumentQualitySignal(docId, ds,
                                                          QualitySignal.HIGH_RETRIEVAL_LOW_QUALITY));
                        continue;
                    }
                }
            }

            if (ds.lastRetrieved().isBefore(staleCutoff)) {
                staleSignals.add(
                        new DocumentQualitySignal(docId, ds, QualitySignal.STALE));
            }
        }

        List<DocumentQualitySignal> result = new ArrayList<>(
                neverRetrievedSignals.size() + lowQualitySignals.size() + staleSignals.size());
        result.addAll(neverRetrievedSignals);
        result.addAll(lowQualitySignals);
        result.addAll(staleSignals);
        return result;
    }


    private record DocAppearance(Instant timestamp, double score) {}
}
