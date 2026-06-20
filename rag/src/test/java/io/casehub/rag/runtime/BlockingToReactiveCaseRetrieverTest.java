package io.casehub.rag.runtime;

import io.casehub.rag.CaseRetriever;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.RelevanceGrade;
import io.casehub.rag.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BlockingToReactiveCaseRetrieverTest {

    private BlockingToReactiveCaseRetriever bridge;

    @BeforeEach
    void setUp() {
        CaseRetriever blocking = (query, corpus, maxResults, filter) ->
            List.of(new RetrievedChunk("result for " + query, "d1", 0.95, Map.of()));
        bridge = new BlockingToReactiveCaseRetriever(blocking);
    }

    @Test
    void retrieveDelegatesToBlocking() {
        var corpus = new CorpusRef("t1", "docs");
        List<RetrievedChunk> result = bridge.retrieve("test query", corpus, 5, null)
            .await().indefinitely();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("result for test query");
        assertThat(result.get(0).relevanceScore()).isEqualTo(0.95);
        assertThat(result).allSatisfy(chunk ->
            assertThat(chunk.grade()).isEqualTo(RelevanceGrade.UNGRADED));
    }

    @Test
    void retrieve_executesOnWorkerThread() {
        var capturedId = new AtomicLong(Thread.currentThread().getId());
        CaseRetriever spy = (query, corpus, maxResults, filter) -> {
            capturedId.set(Thread.currentThread().getId());
            return List.of();
        };
        var b = new BlockingToReactiveCaseRetriever(spy);
        b.retrieve("q", new CorpusRef("t", "c"), 5, null).await().indefinitely();
        assertNotEquals(Thread.currentThread().getId(), capturedId.get(),
            "retrieve() must offload to a worker thread");
    }

    @Test
    void retrieveEmptyFromBlockingReturnsEmpty() {
        CaseRetriever empty = (query, corpus, maxResults, filter) -> List.of();
        bridge = new BlockingToReactiveCaseRetriever(empty);
        var corpus = new CorpusRef("t1", "docs");
        List<RetrievedChunk> result = bridge.retrieve("q", corpus, 10, null)
            .await().indefinitely();
        assertThat(result).isEmpty();
    }
}
