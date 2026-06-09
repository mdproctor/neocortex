package io.casehub.rag.testing;

import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryReactiveCaseRetrieverTest {

    private InMemoryCorpusStore store;
    private InMemoryReactiveCaseRetriever reactive;

    @BeforeEach
    void setUp() {
        store = new InMemoryCorpusStore();
        var blocking = new InMemoryCaseRetriever(store);
        reactive = new InMemoryReactiveCaseRetriever(blocking);
    }

    @Test
    void retrieveDelegatesToBlocking() {
        var corpus = new CorpusRef("t1", "docs");
        store.ingest(corpus, List.of(
            new ChunkInput("first", "d1", Map.of()),
            new ChunkInput("second", "d2", Map.of())));
        List<RetrievedChunk> chunks = reactive.retrieve("query", corpus, 10)
            .await().indefinitely();
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).isEqualTo("first");
        assertThat(chunks.get(1).content()).isEqualTo("second");
    }

    @Test
    void retrieveRespectsMaxResults() {
        var corpus = new CorpusRef("t1", "docs");
        store.ingest(corpus, List.of(
            new ChunkInput("a", "d1", Map.of()),
            new ChunkInput("b", "d2", Map.of()),
            new ChunkInput("c", "d3", Map.of())));
        List<RetrievedChunk> chunks = reactive.retrieve("q", corpus, 2)
            .await().indefinitely();
        assertThat(chunks).hasSize(2);
    }

    @Test
    void retrieveFromEmptyCorpusReturnsEmpty() {
        var corpus = new CorpusRef("t1", "docs");
        List<RetrievedChunk> chunks = reactive.retrieve("q", corpus, 10)
            .await().indefinitely();
        assertThat(chunks).isEmpty();
    }
}
