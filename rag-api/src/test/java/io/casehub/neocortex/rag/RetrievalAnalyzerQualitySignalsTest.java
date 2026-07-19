package io.casehub.neocortex.rag;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalAnalyzerQualitySignalsTest {

    private static final CorpusRef CORPUS = new CorpusRef("t1", "corpus1");
    private static final Instant SINCE = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-07-01T00:00:00Z");
    private static final QualityThresholds DEFAULTS = QualityThresholds.defaults();

    private static RetrievedDocumentRef doc(String id, double score) {
        return new RetrievedDocumentRef(id, score);
    }

    private static RetrievalRecord record(String rid, Instant ts, RetrievedDocumentRef... docs) {
        return new RetrievalRecord(rid, RetrievalQuery.of("q"), CORPUS, List.of(docs), 10, ts);
    }

    private static RetrievalTracker combinedStub(List<RetrievalRecord> records,
                                                  List<RetrievalFeedback> feedback,
                                                  Set<String> retrievedIds) {
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
                return retrievedIds;
            }

            @Override
            public int purgeOlderThan(Instant cutoff) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static EmbeddingIngestor stubIngestor(List<String> documents) {
        return new EmbeddingIngestor() {
            @Override
            public void ingest(CorpusRef c, List<ChunkInput> chunks) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteDocument(CorpusRef c, String docId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteCorpus(CorpusRef c) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<String> listDocuments(CorpusRef c) {
                return documents;
            }
        };
    }

    @Test
    void neverRetrievedDocsFlagged() {
        var tracker = combinedStub(
                List.of(record("r1", UNTIL.minusSeconds(3600), doc("doc-A", 0.9))),
                List.of(),
                Set.of("doc-A"));
        var ingestor = stubIngestor(List.of("doc-A", "doc-B"));

        var result = RetrievalAnalyzer.qualitySignals(
                tracker, ingestor, CORPUS, SINCE, UNTIL, DEFAULTS);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).signal()).isEqualTo(QualitySignal.NEVER_RETRIEVED);
        assertThat(result.get(0).sourceDocumentId()).isEqualTo("doc-B");
        assertThat(result.get(0).stats()).isNull();
    }

    @Test
    void highRetrievalLowQuality_whenRatioExceedsThreshold() {
        Instant recent = UNTIL.minusSeconds(3600);
        var records = new ArrayList<RetrievalRecord>();
        var feedback = new ArrayList<RetrievalFeedback>();
        for (int i = 0; i < 5; i++) {
            String rid = "r" + i;
            records.add(record(rid, recent, doc("doc-A", 0.5)));
            feedback.add(new RetrievalFeedback(rid, "doc-A",
                    RetrievalOutcome.NOT_RELEVANT, recent));
        }
        var tracker = combinedStub(records, feedback, Set.of("doc-A"));
        var ingestor = stubIngestor(List.of("doc-A"));

        var result = RetrievalAnalyzer.qualitySignals(
                tracker, ingestor, CORPUS, SINCE, UNTIL, DEFAULTS);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).signal()).isEqualTo(QualitySignal.HIGH_RETRIEVAL_LOW_QUALITY);
        assertThat(result.get(0).stats()).isNotNull();
        assertThat(result.get(0).stats().retrievalCount()).isEqualTo(5);
    }

    @Test
    void highRetrievalLowQuality_notFlaggedWhenBelowThreshold() {
        Instant recent = UNTIL.minusSeconds(3600);
        var records = new ArrayList<RetrievalRecord>();
        var feedback = new ArrayList<RetrievalFeedback>();
        for (int i = 0; i < 5; i++) {
            String rid = "r" + i;
            records.add(record(rid, recent, doc("doc-A", 0.5)));
            feedback.add(new RetrievalFeedback(rid, "doc-A",
                    i < 2 ? RetrievalOutcome.NOT_RELEVANT : RetrievalOutcome.RELEVANT,
                    recent));
        }
        var tracker = combinedStub(records, feedback, Set.of("doc-A"));
        var ingestor = stubIngestor(List.of("doc-A"));

        var result = RetrievalAnalyzer.qualitySignals(
                tracker, ingestor, CORPUS, SINCE, UNTIL, DEFAULTS);

        assertThat(result).isEmpty();
    }

    @Test
    void belowMinRetrievals_notFlaggedEvenWithBadFeedback() {
        Instant recent = UNTIL.minusSeconds(3600);
        var records = List.of(
                record("r1", recent, doc("doc-A", 0.5)),
                record("r2", recent, doc("doc-A", 0.5)));
        var feedback = List.of(
                new RetrievalFeedback("r1", "doc-A", RetrievalOutcome.NOT_RELEVANT, recent),
                new RetrievalFeedback("r2", "doc-A", RetrievalOutcome.NOT_RELEVANT, recent));
        var tracker = combinedStub(records, feedback, Set.of("doc-A"));
        var ingestor = stubIngestor(List.of("doc-A"));

        var result = RetrievalAnalyzer.qualitySignals(
                tracker, ingestor, CORPUS, SINCE, UNTIL, DEFAULTS);

        assertThat(result).isEmpty();
    }

    @Test
    void aboveMinRetrievalsZeroFeedback_notFlagged() {
        Instant recent = UNTIL.minusSeconds(3600);
        var records = new ArrayList<RetrievalRecord>();
        for (int i = 0; i < 5; i++) {
            records.add(record("r" + i, recent, doc("doc-A", 0.5)));
        }
        var tracker = combinedStub(records, List.of(), Set.of("doc-A"));
        var ingestor = stubIngestor(List.of("doc-A"));

        var result = RetrievalAnalyzer.qualitySignals(
                tracker, ingestor, CORPUS, SINCE, UNTIL, DEFAULTS);

        assertThat(result).isEmpty();
    }

    @Test
    void aboveMinRetrievals_feedbackBelowMinFeedback_notFlagged() {
        Instant recent = UNTIL.minusSeconds(3600);
        var records = new ArrayList<RetrievalRecord>();
        for (int i = 0; i < 5; i++) {
            records.add(record("r" + i, recent, doc("doc-A", 0.5)));
        }
        var feedback = List.of(
                new RetrievalFeedback("r0", "doc-A", RetrievalOutcome.NOT_RELEVANT, recent),
                new RetrievalFeedback("r1", "doc-A", RetrievalOutcome.NOT_RELEVANT, recent));
        var tracker = combinedStub(records, feedback, Set.of("doc-A"));
        var ingestor = stubIngestor(List.of("doc-A"));

        var result = RetrievalAnalyzer.qualitySignals(
                tracker, ingestor, CORPUS, SINCE, UNTIL, DEFAULTS);

        assertThat(result).isEmpty();
    }

    @Test
    void stale_whenLastRetrievalOutsideStaleWindow() {
        Instant old = UNTIL.minus(Duration.ofDays(120));
        var records = List.of(
                record("r1", old, doc("doc-A", 0.9)),
                record("r2", old, doc("doc-A", 0.8)),
                record("r3", old, doc("doc-A", 0.7)));
        var tracker = combinedStub(records, List.of(), Set.of("doc-A"));
        var ingestor = stubIngestor(List.of("doc-A"));

        var result = RetrievalAnalyzer.qualitySignals(
                tracker, ingestor, CORPUS, SINCE, UNTIL, DEFAULTS);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).signal()).isEqualTo(QualitySignal.STALE);
    }

    @Test
    void multipleSignals_highestSeverityOnly() {
        Instant old = UNTIL.minus(Duration.ofDays(120));
        var records = new ArrayList<RetrievalRecord>();
        var feedback = new ArrayList<RetrievalFeedback>();
        for (int i = 0; i < 5; i++) {
            String rid = "r" + i;
            records.add(record(rid, old, doc("doc-A", 0.5)));
            feedback.add(new RetrievalFeedback(rid, "doc-A",
                    RetrievalOutcome.NOT_RELEVANT, old));
        }
        var tracker = combinedStub(records, feedback, Set.of("doc-A"));
        var ingestor = stubIngestor(List.of("doc-A"));

        var result = RetrievalAnalyzer.qualitySignals(
                tracker, ingestor, CORPUS, SINCE, UNTIL, DEFAULTS);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).signal()).isEqualTo(QualitySignal.HIGH_RETRIEVAL_LOW_QUALITY);
    }

    @Test
    void customThresholds_overrideDefaults() {
        Instant recent = UNTIL.minusSeconds(3600);
        var records = new ArrayList<RetrievalRecord>();
        var feedback = new ArrayList<RetrievalFeedback>();
        for (int i = 0; i < 3; i++) {
            String rid = "r" + i;
            records.add(record(rid, recent, doc("doc-A", 0.5)));
            feedback.add(new RetrievalFeedback(rid, "doc-A",
                    i < 2 ? RetrievalOutcome.NOT_RELEVANT : RetrievalOutcome.RELEVANT,
                    recent));
        }
        var tracker = combinedStub(records, feedback, Set.of("doc-A"));
        var ingestor = stubIngestor(List.of("doc-A"));
        var thresholds = new QualityThresholds(3, 3, 0.5, Duration.ofDays(90));

        var result = RetrievalAnalyzer.qualitySignals(
                tracker, ingestor, CORPUS, SINCE, UNTIL, thresholds);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).signal()).isEqualTo(QualitySignal.HIGH_RETRIEVAL_LOW_QUALITY);
    }

    @Test
    void resultOrdering_neverRetrievedFirst_thenLowQuality_thenStale() {
        Instant recent = UNTIL.minusSeconds(3600);
        Instant old = UNTIL.minus(Duration.ofDays(120));

        var records = new ArrayList<RetrievalRecord>();
        var feedback = new ArrayList<RetrievalFeedback>();
        for (int i = 0; i < 5; i++) {
            records.add(record("rb" + i, recent, doc("doc-B", 0.5)));
            feedback.add(new RetrievalFeedback("rb" + i, "doc-B",
                    RetrievalOutcome.NOT_RELEVANT, recent));
        }
        for (int i = 0; i < 3; i++) {
            records.add(record("rc" + i, old, doc("doc-C", 0.9)));
        }
        var tracker = combinedStub(records, feedback, Set.of("doc-B", "doc-C"));
        var ingestor = stubIngestor(List.of("doc-A", "doc-B", "doc-C"));

        var result = RetrievalAnalyzer.qualitySignals(
                tracker, ingestor, CORPUS, SINCE, UNTIL, DEFAULTS);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).signal()).isEqualTo(QualitySignal.NEVER_RETRIEVED);
        assertThat(result.get(0).sourceDocumentId()).isEqualTo("doc-A");
        assertThat(result.get(1).signal()).isEqualTo(QualitySignal.HIGH_RETRIEVAL_LOW_QUALITY);
        assertThat(result.get(1).sourceDocumentId()).isEqualTo("doc-B");
        assertThat(result.get(2).signal()).isEqualTo(QualitySignal.STALE);
        assertThat(result.get(2).sourceDocumentId()).isEqualTo("doc-C");
    }
}
