package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BlockingToReactiveEmbeddingIngestorTest {

    private RecordingEmbeddingIngestor          blocking;
    private BlockingToReactiveEmbeddingIngestor bridge;

    @BeforeEach
    void setUp() {
        blocking = new RecordingEmbeddingIngestor();
        bridge = new BlockingToReactiveEmbeddingIngestor(blocking);
    }

    @Test
    void ingestDelegatesToBlocking() {
        var corpus = new CorpusRef("t1", "docs");
        var chunks = List.of(new ChunkInput("hello", "d1", Map.of()));
        bridge.ingest(corpus, chunks).await().indefinitely();
        assertThat(blocking.calls).containsExactly("ingest:t1:docs");
    }

    @Test
    void deleteDocumentDelegatesToBlocking() {
        var corpus = new CorpusRef("t1", "docs");
        bridge.deleteDocument(corpus, "d1").await().indefinitely();
        assertThat(blocking.calls).containsExactly("deleteDocument:t1:docs:d1");
    }

    @Test
    void deleteCorpusDelegatesToBlocking() {
        var corpus = new CorpusRef("t1", "docs");
        bridge.deleteCorpus(corpus).await().indefinitely();
        assertThat(blocking.calls).containsExactly("deleteCorpus:t1:docs");
    }

    @Test
    void listDocumentsDelegatesToBlocking() {
        var corpus = new CorpusRef("t1", "docs");
        blocking.documentsToReturn = List.of("d1", "d2");
        List<String> result = bridge.listDocuments(corpus).await().indefinitely();
        assertThat(result).containsExactly("d1", "d2");
        assertThat(blocking.calls).containsExactly("listDocuments:t1:docs");
    }

    @Test
    void ingest_executesOnWorkerThread() {
        var capturedId = new AtomicLong(Thread.currentThread().getId());
        EmbeddingIngestor spy = new EmbeddingIngestor() {
            @Override public void ingest(CorpusRef c, List<ChunkInput> ch) {
                capturedId.set(Thread.currentThread().getId());
            }
            @Override public void deleteDocument(CorpusRef c, String id) {}
            @Override public void deleteCorpus(CorpusRef c) {}
            @Override public List<String> listDocuments(CorpusRef c) { return List.of(); }
        };
        var b = new BlockingToReactiveEmbeddingIngestor(spy);
        b.ingest(new CorpusRef("t", "c"), List.of(new ChunkInput("x", "d", Map.of())))
            .await().indefinitely();
        assertNotEquals(Thread.currentThread().getId(), capturedId.get(),
            "ingest() must offload to a worker thread");
    }

    @Test
    void deleteDocument_executesOnWorkerThread() {
        var capturedId = new AtomicLong(Thread.currentThread().getId());
        EmbeddingIngestor spy = new EmbeddingIngestor() {
            @Override public void ingest(CorpusRef c, List<ChunkInput> ch) {}
            @Override public void deleteDocument(CorpusRef c, String id) {
                capturedId.set(Thread.currentThread().getId());
            }
            @Override public void deleteCorpus(CorpusRef c) {}
            @Override public List<String> listDocuments(CorpusRef c) { return List.of(); }
        };
        var b = new BlockingToReactiveEmbeddingIngestor(spy);
        b.deleteDocument(new CorpusRef("t", "c"), "d1").await().indefinitely();
        assertNotEquals(Thread.currentThread().getId(), capturedId.get(),
            "deleteDocument() must offload to a worker thread");
    }

    @Test
    void deleteCorpus_executesOnWorkerThread() {
        var capturedId = new AtomicLong(Thread.currentThread().getId());
        EmbeddingIngestor spy = new EmbeddingIngestor() {
            @Override public void ingest(CorpusRef c, List<ChunkInput> ch) {}
            @Override public void deleteDocument(CorpusRef c, String id) {}
            @Override public void deleteCorpus(CorpusRef c) {
                capturedId.set(Thread.currentThread().getId());
            }
            @Override public List<String> listDocuments(CorpusRef c) { return List.of(); }
        };
        var b = new BlockingToReactiveEmbeddingIngestor(spy);
        b.deleteCorpus(new CorpusRef("t", "c")).await().indefinitely();
        assertNotEquals(Thread.currentThread().getId(), capturedId.get(),
            "deleteCorpus() must offload to a worker thread");
    }

    @Test
    void listDocuments_executesOnWorkerThread() {
        var capturedId = new AtomicLong(Thread.currentThread().getId());
        EmbeddingIngestor spy = new EmbeddingIngestor() {
            @Override public void ingest(CorpusRef c, List<ChunkInput> ch) {}
            @Override public void deleteDocument(CorpusRef c, String id) {}
            @Override public void deleteCorpus(CorpusRef c) {}
            @Override public List<String> listDocuments(CorpusRef c) {
                capturedId.set(Thread.currentThread().getId());
                return List.of();
            }
        };
        var b = new BlockingToReactiveEmbeddingIngestor(spy);
        b.listDocuments(new CorpusRef("t", "c")).await().indefinitely();
        assertNotEquals(Thread.currentThread().getId(), capturedId.get(),
            "listDocuments() must offload to a worker thread");
    }

    static class RecordingEmbeddingIngestor implements EmbeddingIngestor {
        final List<String> calls = new ArrayList<>();
        List<String> documentsToReturn = List.of();

        @Override
        public void ingest(CorpusRef corpus, List<ChunkInput> chunks) {
            calls.add("ingest:" + corpus.tenantId() + ":" + corpus.corpusName());
        }

        @Override
        public void deleteDocument(CorpusRef corpus, String sourceDocumentId) {
            calls.add("deleteDocument:" + corpus.tenantId() + ":" + corpus.corpusName()
                + ":" + sourceDocumentId);
        }

        @Override
        public void deleteCorpus(CorpusRef corpus) {
            calls.add("deleteCorpus:" + corpus.tenantId() + ":" + corpus.corpusName());
        }

        @Override
        public List<String> listDocuments(CorpusRef corpus) {
            calls.add("listDocuments:" + corpus.tenantId() + ":" + corpus.corpusName());
            return documentsToReturn;
        }
    }
}
