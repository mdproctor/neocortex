package io.casehub.neocortex.rag;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalAnalyzerUnretrievedTest {

    private static final CorpusRef CORPUS = new CorpusRef("t1", "corpus1");
    private static final Instant SINCE = Instant.parse("2025-12-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-02-01T00:00:00Z");

    private static RetrievalTracker stubTrackerWithRetrievedIds(Set<String> ids) {
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
                throw new UnsupportedOperationException();
            }

            @Override
            public List<RetrievalFeedback> findFeedback(CorpusRef c, Instant s, Instant u) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Set<String> findRetrievedDocumentIds(CorpusRef c, Instant s, Instant u) {
                return ids;
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
    void allDocumentsRetrieved_emptySet() {
        var tracker = stubTrackerWithRetrievedIds(Set.of("doc-A", "doc-B"));
        var ingestor = stubIngestor(List.of("doc-A", "doc-B"));

        var result = RetrievalAnalyzer.unretrievedDocuments(
                tracker, ingestor, CORPUS, SINCE, UNTIL);

        assertThat(result).isEmpty();
    }

    @Test
    void someNeverRetrieved_correctSetDifference() {
        var tracker = stubTrackerWithRetrievedIds(Set.of("doc-A"));
        var ingestor = stubIngestor(List.of("doc-A", "doc-B", "doc-C"));

        var result = RetrievalAnalyzer.unretrievedDocuments(
                tracker, ingestor, CORPUS, SINCE, UNTIL);

        assertThat(result).containsExactlyInAnyOrder("doc-B", "doc-C");
    }

    @Test
    void emptyCorpus_emptySet() {
        var tracker = stubTrackerWithRetrievedIds(Set.of());
        var ingestor = stubIngestor(List.of());

        var result = RetrievalAnalyzer.unretrievedDocuments(
                tracker, ingestor, CORPUS, SINCE, UNTIL);

        assertThat(result).isEmpty();
    }

    @Test
    void emptyRetrievalHistory_allDocumentsReturned() {
        var tracker = stubTrackerWithRetrievedIds(Set.of());
        var ingestor = stubIngestor(List.of("doc-A", "doc-B"));

        var result = RetrievalAnalyzer.unretrievedDocuments(
                tracker, ingestor, CORPUS, SINCE, UNTIL);

        assertThat(result).containsExactlyInAnyOrder("doc-A", "doc-B");
    }
}
