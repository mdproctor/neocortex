package io.casehub.neocortex.rag.testing;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievalFeedback;
import io.casehub.neocortex.rag.RetrievalOutcome;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.neocortex.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class RetrievalTrackerContractTest {

    protected static final CorpusRef CORPUS = new CorpusRef("tenant-a", "corpus-1");
    protected static final CorpusRef OTHER_CORPUS = new CorpusRef("tenant-b", "corpus-2");

    protected abstract RetrievalTracker tracker();

    protected List<RetrievedChunk> chunks(String... docIds) {
        return Arrays.stream(docIds)
            .map(id -> new RetrievedChunk("content-" + id, id, 0.9, Map.of()))
            .toList();
    }

    // --- record ---

    @Test
    void record_returnsNonBlankId() {
        String id = tracker().record(RetrievalQuery.of("q"), CORPUS, chunks("d1"), 10);
        assertThat(id).isNotBlank();
    }

    @Test
    void record_uniqueIdsPerCall() {
        String id1 = tracker().record(RetrievalQuery.of("q"), CORPUS, chunks("d1"), 10);
        String id2 = tracker().record(RetrievalQuery.of("q"), CORPUS, chunks("d1"), 10);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void record_emptyResults() {
        tracker().record(RetrievalQuery.of("q"), CORPUS, List.of(), 10);
        var records = tracker().findRecords(CORPUS, Instant.EPOCH, Instant.MAX);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().documents()).isEmpty();
    }

    @Test
    void record_deduplicatesChunksByDocId_maxScore() {
        var dups = List.of(
            new RetrievedChunk("c1", "doc-1", 0.7, Map.of()),
            new RetrievedChunk("c2", "doc-1", 0.9, Map.of()),
            new RetrievedChunk("c3", "doc-2", 0.5, Map.of()));
        tracker().record(RetrievalQuery.of("q"), CORPUS, dups, 10);
        var records = tracker().findRecords(CORPUS, Instant.EPOCH, Instant.MAX);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().documents()).hasSize(2);
        var doc1 = records.getFirst().documents().stream()
            .filter(d -> d.sourceDocumentId().equals("doc-1")).findFirst().orElseThrow();
        assertThat(doc1.relevanceScore()).isEqualTo(0.9);
    }

    @Test
    void record_capturesMaxResults() {
        tracker().record(RetrievalQuery.of("q"), CORPUS, chunks("d1"), 42);
        var records = tracker().findRecords(CORPUS, Instant.EPOCH, Instant.MAX);
        assertThat(records.getFirst().maxResults()).isEqualTo(42);
    }

    @Test
    void record_capturesQueryExpansion() {
        var query = RetrievalQuery.of("original").withExpansion("expanded");
        tracker().record(query, CORPUS, chunks("d1"), 10);
        var records = tracker().findRecords(CORPUS, Instant.EPOCH, Instant.MAX);
        assertThat(records.getFirst().query().text()).isEqualTo("original");
        assertThat(records.getFirst().query().expandedText()).isEqualTo("expanded");
    }

    // --- feedback ---

    @Test
    void feedback_storesOutcome() {
        String id = tracker().record(RetrievalQuery.of("q"), CORPUS, chunks("d1"), 10);
        tracker().feedback(id, "d1", RetrievalOutcome.HIGHLY_RELEVANT);
        var fb = tracker().findFeedback(CORPUS, Instant.EPOCH, Instant.MAX);
        assertThat(fb).hasSize(1);
        assertThat(fb.getFirst().outcome()).isEqualTo(RetrievalOutcome.HIGHLY_RELEVANT);
    }

    @Test
    void feedback_upsertLatestWins() {
        String id = tracker().record(RetrievalQuery.of("q"), CORPUS, chunks("d1"), 10);
        tracker().feedback(id, "d1", RetrievalOutcome.NOT_RELEVANT);
        tracker().feedback(id, "d1", RetrievalOutcome.RELEVANT);
        var fb = tracker().findFeedback(CORPUS, Instant.EPOCH, Instant.MAX);
        assertThat(fb).hasSize(1);
        assertThat(fb.getFirst().outcome()).isEqualTo(RetrievalOutcome.RELEVANT);
    }

    @Test
    void feedback_multipleDocs() {
        String id = tracker().record(RetrievalQuery.of("q"), CORPUS,
            chunks("d1", "d2"), 10);
        tracker().feedback(id, "d1", RetrievalOutcome.RELEVANT);
        tracker().feedback(id, "d2", RetrievalOutcome.NOT_RELEVANT);
        var fb = tracker().findFeedback(CORPUS, Instant.EPOCH, Instant.MAX);
        assertThat(fb).hasSize(2);
    }

    // --- findRecords ---

    @Test
    void findRecords_filtersByCorpus() {
        tracker().record(RetrievalQuery.of("q"), CORPUS, chunks("d1"), 10);
        tracker().record(RetrievalQuery.of("q"), OTHER_CORPUS, chunks("d2"), 10);
        var records = tracker().findRecords(CORPUS, Instant.EPOCH, Instant.MAX);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().documents().getFirst().sourceDocumentId()).isEqualTo("d1");
    }

    @Test
    void findRecords_filtersByTimeWindow() {
        tracker().record(RetrievalQuery.of("q"), CORPUS, chunks("d1"), 10);
        var since = Instant.now().plusSeconds(60);
        var records = tracker().findRecords(CORPUS, since, Instant.MAX);
        assertThat(records).isEmpty();
    }

    @Test
    void findRecords_emptyWhenNone() {
        assertThat(tracker().findRecords(CORPUS, Instant.EPOCH, Instant.MAX)).isEmpty();
    }

    // --- findFeedback ---

    @Test
    void findFeedback_filtersByCorpusViaRetrieval() {
        String id1 = tracker().record(RetrievalQuery.of("q"), CORPUS, chunks("d1"), 10);
        String id2 = tracker().record(RetrievalQuery.of("q"), OTHER_CORPUS, chunks("d2"), 10);
        tracker().feedback(id1, "d1", RetrievalOutcome.RELEVANT);
        tracker().feedback(id2, "d2", RetrievalOutcome.NOT_RELEVANT);
        var fb = tracker().findFeedback(CORPUS, Instant.EPOCH, Instant.MAX);
        assertThat(fb).hasSize(1);
        assertThat(fb.getFirst().sourceDocumentId()).isEqualTo("d1");
    }

    @Test
    void findFeedback_filtersByFeedbackTimestamp() {
        String id = tracker().record(RetrievalQuery.of("q"), CORPUS, chunks("d1"), 10);
        tracker().feedback(id, "d1", RetrievalOutcome.RELEVANT);
        var since = Instant.now().plusSeconds(60);
        assertThat(tracker().findFeedback(CORPUS, since, Instant.MAX)).isEmpty();
    }

    // --- findRetrievedDocumentIds ---

    @Test
    void findRetrievedDocumentIds_returnsDistinctIds() {
        tracker().record(RetrievalQuery.of("q1"), CORPUS, chunks("d1", "d2"), 10);
        tracker().record(RetrievalQuery.of("q2"), CORPUS, chunks("d2", "d3"), 10);
        assertThat(List.copyOf(tracker().findRetrievedDocumentIds(CORPUS, Instant.EPOCH, Instant.MAX)))
            .containsExactlyInAnyOrder("d1", "d2", "d3");
    }

    @Test
    void findRetrievedDocumentIds_filtersByCorpusAndTime() {
        tracker().record(RetrievalQuery.of("q"), OTHER_CORPUS, chunks("d1"), 10);
        assertThat(List.copyOf(tracker().findRetrievedDocumentIds(CORPUS, Instant.EPOCH, Instant.MAX)))
            .isEmpty();
    }

    @Test
    void findRetrievedDocumentIds_emptyWhenNone() {
        assertThat(List.copyOf(tracker().findRetrievedDocumentIds(CORPUS,
            Instant.EPOCH, Instant.MAX))).isEmpty();
    }
}
