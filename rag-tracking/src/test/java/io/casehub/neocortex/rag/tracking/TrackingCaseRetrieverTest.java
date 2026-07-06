package io.casehub.neocortex.rag.tracking;

import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievalRecorded;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.testing.InMemoryRetrievalTracker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingCaseRetrieverTest {

    private static final CorpusRef CORPUS = new CorpusRef("tenant-1", "test-corpus");

    @Test
    void recordsRetrievalAndStampsChunks() {
        var delegateResult = List.of(
            chunk("content-a", "doc1", 0.9),
            chunk("content-b", "doc2", 0.8));
        CaseRetriever delegate = (q, c, m, f) -> delegateResult;
        var tracker = new InMemoryRetrievalTracker();
        var event = new AtomicReference<RetrievalRecorded>();

        var retriever = new TrackingCaseRetriever(delegate, tracker, event::set);
        var results = retriever.retrieve(RetrievalQuery.of("test query"), CORPUS, 10, null);

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(c ->
            assertThat(c.metadata()).containsKey(TrackingLogic.TRACKING_ID_KEY));

        var records = tracker.findRecords(CORPUS, Instant.EPOCH, Instant.MAX);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).query().text()).isEqualTo("test query");
    }

    @Test
    void firesRetrievalRecordedEvent() {
        var delegateResult = List.of(
            chunk("content-a", "doc1", 0.9),
            chunk("content-b", "doc1", 0.7),
            chunk("content-c", "doc2", 0.8));
        CaseRetriever delegate = (q, c, m, f) -> delegateResult;
        var tracker = new InMemoryRetrievalTracker();
        var event = new AtomicReference<RetrievalRecorded>();

        var retriever = new TrackingCaseRetriever(delegate, tracker, event::set);
        retriever.retrieve(RetrievalQuery.of("test query"), CORPUS, 10, null);

        assertThat(event.get()).isNotNull();
        assertThat(event.get().query().text()).isEqualTo("test query");
        assertThat(event.get().corpus()).isEqualTo(CORPUS);
        assertThat(event.get().documents()).hasSize(2); // deduplicated by doc id
    }

    @Test
    void skipsAlreadyTrackedChunks() {
        var delegateResult = List.of(
            new RetrievedChunk("content", "doc1", 0.9,
                Map.of(TrackingLogic.TRACKING_ID_KEY, "existing-id")));
        CaseRetriever delegate = (q, c, m, f) -> delegateResult;
        var tracker = new InMemoryRetrievalTracker();
        var event = new AtomicReference<RetrievalRecorded>();

        var retriever = new TrackingCaseRetriever(delegate, tracker, event::set);
        var results = retriever.retrieve(RetrievalQuery.of("test query"), CORPUS, 10, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).metadata().get(TrackingLogic.TRACKING_ID_KEY))
            .isEqualTo("existing-id");
        assertThat(event.get()).isNull();

        var records = tracker.findRecords(CORPUS, Instant.EPOCH, Instant.MAX);
        assertThat(records).isEmpty();
    }

    @Test
    void failureIsolation_returnsChunksWhenTrackerThrows() {
        var delegateResult = List.of(chunk("content", "doc1", 0.9));
        CaseRetriever delegate = (q, c, m, f) -> delegateResult;
        var event = new AtomicReference<RetrievalRecorded>();

        var throwingTracker = new InMemoryRetrievalTracker() {
            @Override
            public String record(RetrievalQuery query, CorpusRef corpus,
                                 List<RetrievedChunk> results, int maxResults) {
                throw new RuntimeException("storage unavailable");
            }
        };

        var retriever = new TrackingCaseRetriever(delegate, throwingTracker, event::set);
        var results = retriever.retrieve(RetrievalQuery.of("test query"), CORPUS, 10, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).metadata()).doesNotContainKey(TrackingLogic.TRACKING_ID_KEY);
        assertThat(event.get()).isNull();
    }

    // -- helpers --

    private static RetrievedChunk chunk(String content, String docId, double score) {
        return new RetrievedChunk(content, docId, score, Map.of());
    }
}
