package io.casehub.neocortex.rag;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RetrievalAnalyzerDocumentStatsTest {

    private static final CorpusRef CORPUS = new CorpusRef("t1", "corpus1");
    private static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-03T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-01-04T00:00:00Z");
    private static final Instant SINCE = Instant.parse("2025-12-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-02-01T00:00:00Z");

    private static RetrievalRecord record(String retrievalId, Instant timestamp,
                                           RetrievedDocumentRef... docs) {
        return new RetrievalRecord(retrievalId, RetrievalQuery.of("query"),
                CORPUS, List.of(docs), 10, timestamp);
    }

    private static RetrievedDocumentRef doc(String id, double score) {
        return new RetrievedDocumentRef(id, score);
    }

    private static RetrievalTracker stubTracker(List<RetrievalRecord> records,
                                                 List<RetrievalFeedback> feedback) {
        return new RetrievalTracker() {
            @Override
            public String record(RetrievalQuery q, CorpusRef c,
                                 List<RetrievedChunk> r, int m) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void feedback(String rid, String did, RetrievalOutcome o) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<RetrievalRecord> findRecords(CorpusRef c, Instant s, Instant u) {
                return records;
            }

            @Override
            public List<RetrievalFeedback> findFeedback(CorpusRef c, Instant s, Instant u) {
                return feedback;
            }

            @Override
            public Set<String> findRetrievedDocumentIds(CorpusRef c, Instant s, Instant u) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int purgeOlderThan(Instant cutoff) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void singleDocSingleRetrieval() {
        var records = List.of(record("r1", T1, doc("doc-A", 0.9)));
        var tracker = stubTracker(records, List.of());

        var result = RetrievalAnalyzer.documentStats(tracker, CORPUS, SINCE, UNTIL);

        assertThat(result).hasSize(1);
        var stats = result.get("doc-A");
        assertThat(stats.retrievalCount()).isEqualTo(1);
        assertThat(stats.firstRetrieved()).isEqualTo(T1);
        assertThat(stats.lastRetrieved()).isEqualTo(T1);
        assertThat(stats.averageRetrievalScore()).isEqualTo(0.9);
        assertThat(stats.feedbackDistribution()).isEmpty();
    }

    @Test
    void multipleRetrievalsSameDoc() {
        var records = List.of(
                record("r1", T1, doc("doc-A", 0.8)),
                record("r2", T2, doc("doc-A", 0.6)),
                record("r3", T3, doc("doc-A", 1.0)));
        var tracker = stubTracker(records, List.of());

        var result = RetrievalAnalyzer.documentStats(tracker, CORPUS, SINCE, UNTIL);

        var stats = result.get("doc-A");
        assertThat(stats.retrievalCount()).isEqualTo(3);
        assertThat(stats.firstRetrieved()).isEqualTo(T1);
        assertThat(stats.lastRetrieved()).isEqualTo(T3);
        assertThat(stats.averageRetrievalScore()).isCloseTo(0.8, within(0.001));
    }

    @Test
    void multipleDocumentsIndependentStats() {
        var records = List.of(
                record("r1", T1, doc("doc-A", 0.9), doc("doc-B", 0.5)),
                record("r2", T2, doc("doc-A", 0.7)));
        var tracker = stubTracker(records, List.of());

        var result = RetrievalAnalyzer.documentStats(tracker, CORPUS, SINCE, UNTIL);

        assertThat(result).hasSize(2);
        assertThat(result.get("doc-A").retrievalCount()).isEqualTo(2);
        assertThat(result.get("doc-B").retrievalCount()).isEqualTo(1);
    }

    @Test
    void withFeedback_distributionPopulated() {
        var records = List.of(record("r1", T1, doc("doc-A", 0.9)));
        var feedback = List.of(
                new RetrievalFeedback("r1", "doc-A", RetrievalOutcome.RELEVANT, T2));
        var tracker = stubTracker(records, feedback);

        var result = RetrievalAnalyzer.documentStats(tracker, CORPUS, SINCE, UNTIL);

        assertThat(result.get("doc-A").feedbackDistribution())
                .containsEntry(RetrievalOutcome.RELEVANT, 1);
    }

    @Test
    void mixedFeedbackOutcomes_eachCounted() {
        var records = List.of(
                record("r1", T1, doc("doc-A", 0.9)),
                record("r2", T2, doc("doc-A", 0.8)));
        var feedback = List.of(
                new RetrievalFeedback("r1", "doc-A", RetrievalOutcome.NOT_RELEVANT, T1),
                new RetrievalFeedback("r2", "doc-A", RetrievalOutcome.HIGHLY_RELEVANT, T2));
        var tracker = stubTracker(records, feedback);

        var result = RetrievalAnalyzer.documentStats(tracker, CORPUS, SINCE, UNTIL);

        var dist = result.get("doc-A").feedbackDistribution();
        assertThat(dist).containsEntry(RetrievalOutcome.NOT_RELEVANT, 1)
                .containsEntry(RetrievalOutcome.HIGHLY_RELEVANT, 1);
    }

    @Test
    void noRetrievalsInWindow_emptyMap() {
        var tracker = stubTracker(List.of(), List.of());

        var result = RetrievalAnalyzer.documentStats(tracker, CORPUS, SINCE, UNTIL);

        assertThat(result).isEmpty();
    }

    @Test
    void multipleChunksCollapsedToSameDoc_countedAsOnePerRetrieval() {
        var records = List.of(record("r1", T1, doc("doc-A", 0.9)));
        var tracker = stubTracker(records, List.of());

        var result = RetrievalAnalyzer.documentStats(tracker, CORPUS, SINCE, UNTIL);

        assertThat(result.get("doc-A").retrievalCount()).isEqualTo(1);
    }

    @Test
    void lateFeedbackIncluded_retrievalInWindowFeedbackAfterUntil() {
        var records = List.of(record("r1", T1, doc("doc-A", 0.9)));
        var feedback = List.of(
                new RetrievalFeedback("r1", "doc-A", RetrievalOutcome.RELEVANT,
                        Instant.parse("2026-03-01T00:00:00Z")));
        var tracker = stubTracker(records, feedback);

        var result = RetrievalAnalyzer.documentStats(tracker, CORPUS, SINCE, UNTIL);

        assertThat(result.get("doc-A").feedbackDistribution())
                .containsEntry(RetrievalOutcome.RELEVANT, 1);
    }

    @Test
    void outOfWindowFeedbackExcluded_retrievalOutsideWindow() {
        var records = List.of(record("r1", T1, doc("doc-A", 0.9)));
        var feedback = List.of(
                new RetrievalFeedback("r-outside", "doc-A",
                        RetrievalOutcome.NOT_RELEVANT, T2));
        var tracker = stubTracker(records, feedback);

        var result = RetrievalAnalyzer.documentStats(tracker, CORPUS, SINCE, UNTIL);

        assertThat(result.get("doc-A").feedbackDistribution()).isEmpty();
    }
}
