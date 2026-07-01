package io.casehub.neocortex.rag.testing;

import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCaseRetrieverTest {

    private InMemoryEmbeddingIngestor store;
    private CorpusRef                 corpus;

    @BeforeEach
    void setUp() {
        store = new InMemoryEmbeddingIngestor();
        corpus = new CorpusRef("tenant1", "corpus1");
    }

    @Test
    void storeBacked_returns_stored_chunks_in_insertion_order() {
        ChunkInput chunk1 = new ChunkInput("content1", "doc1", Map.of("key1", "value1"));
        ChunkInput chunk2 = new ChunkInput("content2", "doc1", null);
        ChunkInput chunk3 = new ChunkInput("content3", "doc2", Map.of("key2", "value2"));

        store.ingest(corpus, List.of(chunk1, chunk2, chunk3));

        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("any query"), corpus, 10, null);

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
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("any query"), corpus, 2, null);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).content()).isEqualTo("content1");
        assertThat(results.get(1).content()).isEqualTo("content2");
    }

    @Test
    void storeBacked_unknown_corpus_returns_empty() {
        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        CorpusRef unknownCorpus = new CorpusRef("tenant2", "corpus2");
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("any query"), unknownCorpus, 10, null);

        assertThat(results).isEmpty();
    }

    @Test
    void storeBacked_empty_corpus_returns_empty() {
        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("any query"), corpus, 10, null);

        assertThat(results).isEmpty();
    }

    @Test
    void programmatic_returns_fixed_response() {
        RetrievedChunk chunk1 = new RetrievedChunk("content1", "doc1", 0.9, Map.of("key", "value"));
        RetrievedChunk chunk2 = new RetrievedChunk("content2", "doc2", 0.8, null);

        InMemoryCaseRetriever retriever = InMemoryCaseRetriever.returning(List.of(chunk1, chunk2));
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("any query"), corpus, 10, null);

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
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("any query"), corpus, 1, null);

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
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("any query"), otherCorpus, 10, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("fixed");
    }

    @Test
    void programmatic_returns_immutable_list() {
        RetrievedChunk chunk1 = new RetrievedChunk("content1", "doc1", 1.0, null);

        InMemoryCaseRetriever retriever = InMemoryCaseRetriever.returning(List.of(chunk1));
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("any query"), corpus, 10, null);

        assertThat(results).isUnmodifiable();
    }

    // ── PayloadFilter matching tests ───────────────────────────────────

    @Test
    void filterEqMatchesMetadata() {
        store.ingest(corpus, List.of(
                new ChunkInput("jvm content", "doc1", Map.of("domain", "jvm")),
                new ChunkInput("python content", "doc2", Map.of("domain", "python"))));

        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("query"), corpus, 10,
                PayloadFilter.eq("domain", "jvm"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("jvm content");
    }

    @Test
    void filterInMatchesMultipleValues() {
        store.ingest(corpus, List.of(
                new ChunkInput("jvm content", "doc1", Map.of("domain", "jvm")),
                new ChunkInput("python content", "doc2", Map.of("domain", "python")),
                new ChunkInput("rust content", "doc3", Map.of("domain", "rust"))));

        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("query"), corpus, 10,
                PayloadFilter.in("domain", List.of("jvm", "rust")));

        assertThat(results).hasSize(2);
        assertThat(results).extracting(RetrievedChunk::content)
                .containsExactlyInAnyOrder("jvm content", "rust content");
    }

    @Test
    void filterNotInvertsMatch() {
        store.ingest(corpus, List.of(
                new ChunkInput("jvm content", "doc1", Map.of("domain", "jvm")),
                new ChunkInput("python content", "doc2", Map.of("domain", "python"))));

        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("query"), corpus, 10,
                PayloadFilter.not(PayloadFilter.eq("domain", "jvm")));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("python content");
    }

    @Test
    void filterAndRequiresAllConditions() {
        store.ingest(corpus, List.of(
                new ChunkInput("jvm gotcha", "doc1", Map.of("domain", "jvm", "type", "gotcha")),
                new ChunkInput("jvm technique", "doc2", Map.of("domain", "jvm", "type", "technique")),
                new ChunkInput("python gotcha", "doc3", Map.of("domain", "python", "type", "gotcha"))));

        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("query"), corpus, 10,
                PayloadFilter.and(PayloadFilter.eq("domain", "jvm"), PayloadFilter.eq("type", "gotcha")));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("jvm gotcha");
    }

    @Test
    void filterOrMatchesAnyCondition() {
        store.ingest(corpus, List.of(
                new ChunkInput("jvm content", "doc1", Map.of("domain", "jvm")),
                new ChunkInput("python content", "doc2", Map.of("domain", "python")),
                new ChunkInput("rust content", "doc3", Map.of("domain", "rust"))));

        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("query"), corpus, 10,
                PayloadFilter.or(PayloadFilter.eq("domain", "jvm"), PayloadFilter.eq("domain", "python")));

        assertThat(results).hasSize(2);
        assertThat(results).extracting(RetrievedChunk::content)
                .containsExactlyInAnyOrder("jvm content", "python content");
    }

    @Test
    void filterNullReturnsUnfiltered() {
        store.ingest(corpus, List.of(
                new ChunkInput("content1", "doc1", Map.of("domain", "jvm")),
                new ChunkInput("content2", "doc2", Map.of("domain", "python"))));

        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("query"), corpus, 10, null);

        assertThat(results).hasSize(2);
    }

    @Test
    void filterOnMissingFieldReturnsNoMatch() {
        store.ingest(corpus, List.of(
                new ChunkInput("content1", "doc1", Map.of("domain", "jvm")),
                new ChunkInput("content2", "doc2", Map.of("domain", "python"))));

        InMemoryCaseRetriever retriever = new InMemoryCaseRetriever(store);
        List<RetrievedChunk> results = retriever.retrieve(RetrievalQuery.of("query"), corpus, 10,
                PayloadFilter.eq("nonexistent", "value"));

        assertThat(results).isEmpty();
    }
}
