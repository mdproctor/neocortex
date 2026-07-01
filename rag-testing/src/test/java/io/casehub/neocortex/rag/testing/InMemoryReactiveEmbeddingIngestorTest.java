package io.casehub.neocortex.rag.testing;

import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryReactiveEmbeddingIngestorTest {

    private InMemoryEmbeddingIngestor         blocking;
    private InMemoryReactiveEmbeddingIngestor reactive;

    @BeforeEach
    void setUp() {
        blocking = new InMemoryEmbeddingIngestor();
        reactive = new InMemoryReactiveEmbeddingIngestor(blocking);
    }

    @Test
    void ingestDelegatesToBlocking() {
        var corpus = new CorpusRef("t1", "docs");
        var chunks = List.of(new ChunkInput("hello", "d1", Map.of()));
        reactive.ingest(corpus, chunks).await().indefinitely();
        assertThat(blocking.listDocuments(corpus)).containsExactly("d1");
    }

    @Test
    void deleteDocumentDelegatesToBlocking() {
        var corpus = new CorpusRef("t1", "docs");
        blocking.ingest(corpus, List.of(new ChunkInput("hello", "d1", Map.of())));
        reactive.deleteDocument(corpus, "d1").await().indefinitely();
        assertThat(blocking.listDocuments(corpus)).isEmpty();
    }

    @Test
    void deleteCorpusDelegatesToBlocking() {
        var corpus = new CorpusRef("t1", "docs");
        blocking.ingest(corpus, List.of(new ChunkInput("hello", "d1", Map.of())));
        reactive.deleteCorpus(corpus).await().indefinitely();
        assertThat(blocking.listDocuments(corpus)).isEmpty();
    }

    @Test
    void listDocumentsDelegatesToBlocking() {
        var corpus = new CorpusRef("t1", "docs");
        blocking.ingest(corpus, List.of(
            new ChunkInput("a", "d1", Map.of()),
            new ChunkInput("b", "d2", Map.of())));
        List<String> docs = reactive.listDocuments(corpus).await().indefinitely();
        assertThat(docs).containsExactly("d1", "d2");
    }
}
