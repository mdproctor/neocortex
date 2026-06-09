package io.casehub.rag.testing;

import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCaseRetrieverTest {

    private InMemoryCorpusStore store;
    private CorpusRef corpus;

    @BeforeEach
    void setUp() {
        store = new InMemoryCorpusStore();
        corpus = new CorpusRef("tenant1", "corpus1");
    }

    @Test
    void storeBacked_returns_stored_chunks_in_insertion_order() {
        ChunkInput chunk1 = new ChunkInput("content1", "doc1", Map.of("key1", "value1"));
        ChunkInput chunk2 = new ChunkInput("content2", "doc1", null);
        ChunkInput chunk3 = new ChunkInput("content3", "doc2", Map.of("key2", "value2"));

        store.ingest(corpus, List.of(chunk1, chunk2, chunk3));

        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        List<RetrievedChunk> results = retriever.retrieve("any query", corpus, 10);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).content()).isEqualTo("content1");
        assertThat(results.get(0).sourceDocumentId()).isEqualTo("doc1");
        assertThat(results.get(0).relevanceScore()).isEqualTo(1.0);
        assertThat(results.get(0).metadata()).containsEntry("key1", "value1");

        assertThat(results.get(1).content()).isEqualTo("content2");
        assertThat(results.get(1).sourceDocumentId()).isEqualTo("doc1");
        assertThat(results.get(1).relevanceScore()).isEqualTo(1.0);
        assertThat(results.get(1).metadata()).isEmpty();

        assertThat(results.get(2).content()).isEqualTo("content3");
        assertThat(results.get(2).sourceDocumentId()).isEqualTo("doc2");
        assertThat(results.get(2).relevanceScore()).isEqualTo(1.0);
        assertThat(results.get(2).metadata()).containsEntry("key2", "value2");
    }

    @Test
    void storeBacked_respects_maxResults() {
        ChunkInput chunk1 = new ChunkInput("content1", "doc1", null);
        ChunkInput chunk2 = new ChunkInput("content2", "doc1", null);
        ChunkInput chunk3 = new ChunkInput("content3", "doc2", null);

        store.ingest(corpus, List.of(chunk1, chunk2, chunk3));

        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        List<RetrievedChunk> results = retriever.retrieve("any query", corpus, 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).content()).isEqualTo("content1");
        assertThat(results.get(1).content()).isEqualTo("content2");
    }

    @Test
    void storeBacked_unknown_corpus_returns_empty() {
        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        CorpusRef unknownCorpus = new CorpusRef("tenant2", "corpus2");
        List<RetrievedChunk> results = retriever.retrieve("any query", unknownCorpus, 10);

        assertThat(results).isEmpty();
    }

    @Test
    void storeBacked_empty_corpus_returns_empty() {
        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        List<RetrievedChunk> results = retriever.retrieve("any query", corpus, 10);

        assertThat(results).isEmpty();
    }

    @Test
    void programmatic_returns_fixed_response() {
        RetrievedChunk chunk1 = new RetrievedChunk("content1", "doc1", 0.9, Map.of("key", "value"));
        RetrievedChunk chunk2 = new RetrievedChunk("content2", "doc2", 0.8, null);

        InMemoryCaseRetriever retriever = InMemoryCaseRetriever.returning(List.of(chunk1, chunk2));
        List<RetrievedChunk> results = retriever.retrieve("any query", corpus, 10);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).content()).isEqualTo("content1");
        assertThat(results.get(0).relevanceScore()).isEqualTo(0.9);
        assertThat(results.get(0).metadata()).containsEntry("key", "value");

        assertThat(results.get(1).content()).isEqualTo("content2");
        assertThat(results.get(1).relevanceScore()).isEqualTo(0.8);
        assertThat(results.get(1).metadata()).isEmpty();
    }

    @Test
    void programmatic_ignores_maxResults() {
        RetrievedChunk chunk1 = new RetrievedChunk("content1", "doc1", 0.9, null);
        RetrievedChunk chunk2 = new RetrievedChunk("content2", "doc2", 0.8, null);
        RetrievedChunk chunk3 = new RetrievedChunk("content3", "doc3", 0.7, null);

        InMemoryCaseRetriever retriever = InMemoryCaseRetriever.returning(List.of(chunk1, chunk2, chunk3));
        List<RetrievedChunk> results = retriever.retrieve("any query", corpus, 1);

        // Should return all 3 chunks, ignoring maxResults=1
        assertThat(results).hasSize(3);
        assertThat(results.get(0).content()).isEqualTo("content1");
        assertThat(results.get(1).content()).isEqualTo("content2");
        assertThat(results.get(2).content()).isEqualTo("content3");
    }

    @Test
    void programmatic_ignores_corpus() {
        RetrievedChunk chunk1 = new RetrievedChunk("fixed", "doc1", 1.0, null);

        InMemoryCaseRetriever retriever = InMemoryCaseRetriever.returning(List.of(chunk1));
        CorpusRef otherCorpus = new CorpusRef("other-tenant", "other-corpus");
        List<RetrievedChunk> results = retriever.retrieve("any query", otherCorpus, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("fixed");
    }

    @Test
    void programmatic_returns_immutable_list() {
        RetrievedChunk chunk1 = new RetrievedChunk("content1", "doc1", 1.0, null);

        InMemoryCaseRetriever retriever = InMemoryCaseRetriever.returning(List.of(chunk1));
        List<RetrievedChunk> results = retriever.retrieve("any query", corpus, 10);

        assertThat(results).isUnmodifiable();
    }
}
