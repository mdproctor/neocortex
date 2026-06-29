package io.casehub.rag.runtime;

import io.casehub.rag.CorpusRef;
import io.casehub.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BM25IndexRegistryTest {

    @Test
    void separateCorpusesAreIsolated() {
        var registry = new BM25IndexRegistry(TenancyStrategy.SEPARATE_COLLECTIONS);
        var corpus1 = new CorpusRef("tenant1", "corpus1");
        var corpus2 = new CorpusRef("tenant2", "corpus2");

        registry.addChunk(corpus1, "doc1", "ChatModel in Quarkus",
            Map.of("domain", "jvm"), Map.of());
        registry.addChunk(corpus2, "doc2", "Hibernate lazy loading fails outside transaction",
            Map.of("domain", "jvm"), Map.of());

        var results1 = registry.search(corpus1, "ChatModel", 10, null);
        assertEquals(1, results1.size());
        assertEquals("doc1", results1.getFirst().sourceDocumentId());

        var results2 = registry.search(corpus2, "ChatModel", 10, null);
        assertTrue(results2.isEmpty(), "corpus2 should not contain ChatModel tokens");
    }

    @Test
    void clearRemovesOnlyTargetCorpus() {
        var registry = new BM25IndexRegistry(TenancyStrategy.SEPARATE_COLLECTIONS);
        var corpus1 = new CorpusRef("t1", "c1");
        var corpus2 = new CorpusRef("t2", "c2");

        registry.addChunk(corpus1, "doc1", "content1", Map.of(), Map.of());
        registry.addChunk(corpus2, "doc2", "content2", Map.of(), Map.of());
        registry.clear(corpus1);

        assertTrue(registry.search(corpus1, "content1", 10, null).isEmpty());
        assertEquals(1, registry.search(corpus2, "content2", 10, null).size());
    }

    @Test
    void searchReturnsRetrievedChunks() {
        var registry = new BM25IndexRegistry(TenancyStrategy.SEPARATE_COLLECTIONS);
        var corpus = new CorpusRef("hortora", "garden");
        registry.addChunk(corpus, "doc1", "ChatModel is a LangChain4j interface",
            Map.of("domain", "jvm"), Map.of("tags", List.of("langchain4j")));

        List<RetrievedChunk> results = registry.search(corpus, "ChatModel", 10, null);
        assertEquals(1, results.size());
        var chunk = results.getFirst();
        assertEquals("doc1", chunk.sourceDocumentId());
        assertEquals("jvm", chunk.metadata().get("domain"));
        assertTrue(chunk.relevanceScore() > 0);
    }
}
