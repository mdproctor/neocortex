package io.casehub.neocortex.rag.testing;

import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEmbeddingIngestorTest {

    private InMemoryEmbeddingIngestor store;

    @BeforeEach
    void setUp() {
        store = new InMemoryEmbeddingIngestor();
    }

    @Test
    void ingest_and_list_documents() {
        CorpusRef corpus = new CorpusRef("tenant1", "corpus1");
        ChunkInput chunk1 = new ChunkInput("content1", "doc1", Map.of("key", "value"));
        ChunkInput chunk2 = new ChunkInput("content2", "doc2", null);

        store.ingest(corpus, List.of(chunk1, chunk2));

        assertThat(store.listDocuments(corpus)).containsExactly("doc1", "doc2");
    }

    @Test
    void empty_corpus_returns_empty_list() {
        CorpusRef corpus = new CorpusRef("tenant1", "corpus1");
        assertThat(store.listDocuments(corpus)).isEmpty();
    }

    @Test
    void deleteDocument_removes_document() {
        CorpusRef corpus = new CorpusRef("tenant1", "corpus1");
        ChunkInput chunk1 = new ChunkInput("content1", "doc1", null);
        ChunkInput chunk2 = new ChunkInput("content2", "doc2", null);

        store.ingest(corpus, List.of(chunk1, chunk2));
        store.deleteDocument(corpus, "doc1");

        assertThat(store.listDocuments(corpus)).containsExactly("doc2");
    }

    @Test
    void deleteDocument_unknown_document_does_nothing() {
        CorpusRef corpus = new CorpusRef("tenant1", "corpus1");
        ChunkInput chunk1 = new ChunkInput("content1", "doc1", null);

        store.ingest(corpus, List.of(chunk1));
        store.deleteDocument(corpus, "unknown");

        assertThat(store.listDocuments(corpus)).containsExactly("doc1");
    }

    @Test
    void deleteCorpus_removes_all_data() {
        CorpusRef corpus = new CorpusRef("tenant1", "corpus1");
        ChunkInput chunk1 = new ChunkInput("content1", "doc1", null);

        store.ingest(corpus, List.of(chunk1));
        store.deleteCorpus(corpus);

        assertThat(store.listDocuments(corpus)).isEmpty();
    }

    @Test
    void deleteCorpus_unknown_corpus_does_nothing() {
        CorpusRef corpus = new CorpusRef("tenant1", "corpus1");
        store.deleteCorpus(corpus);
        assertThat(store.listDocuments(corpus)).isEmpty();
    }

    @Test
    void getChunks_returns_in_insertion_order() {
        CorpusRef corpus = new CorpusRef("tenant1", "corpus1");
        ChunkInput chunk1 = new ChunkInput("content1", "doc1", null);
        ChunkInput chunk2 = new ChunkInput("content2", "doc1", Map.of("k", "v"));
        ChunkInput chunk3 = new ChunkInput("content3", "doc2", null);

        store.ingest(corpus, List.of(chunk1, chunk2, chunk3));

        List<ChunkInput> chunks = store.getChunks(corpus);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).content()).isEqualTo("content1");
        assertThat(chunks.get(1).content()).isEqualTo("content2");
        assertThat(chunks.get(2).content()).isEqualTo("content3");
    }

    @Test
    void getChunks_empty_corpus_returns_empty_list() {
        CorpusRef corpus = new CorpusRef("tenant1", "corpus1");
        assertThat(store.getChunks(corpus)).isEmpty();
    }

    @Test
    void tenant_isolation() {
        CorpusRef corpus1 = new CorpusRef("tenant1", "corpus1");
        CorpusRef corpus2 = new CorpusRef("tenant2", "corpus1");
        ChunkInput chunk1 = new ChunkInput("content1", "doc1", null);
        ChunkInput chunk2 = new ChunkInput("content2", "doc2", null);

        store.ingest(corpus1, List.of(chunk1));
        store.ingest(corpus2, List.of(chunk2));

        assertThat(store.listDocuments(corpus1)).containsExactly("doc1");
        assertThat(store.listDocuments(corpus2)).containsExactly("doc2");
        assertThat(store.getChunks(corpus1)).hasSize(1);
        assertThat(store.getChunks(corpus1).get(0).content()).isEqualTo("content1");
        assertThat(store.getChunks(corpus2)).hasSize(1);
        assertThat(store.getChunks(corpus2).get(0).content()).isEqualTo("content2");
    }

    @Test
    void multiple_chunks_same_document() {
        CorpusRef corpus = new CorpusRef("tenant1", "corpus1");
        ChunkInput chunk1 = new ChunkInput("content1", "doc1", null);
        ChunkInput chunk2 = new ChunkInput("content2", "doc1", null);

        store.ingest(corpus, List.of(chunk1, chunk2));

        assertThat(store.listDocuments(corpus)).containsExactly("doc1");
        assertThat(store.getChunks(corpus)).hasSize(2);
    }
}
