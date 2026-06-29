package io.casehub.rag.runtime;

import io.casehub.rag.PayloadFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BM25IndexTest {

    private BM25Index index;

    @BeforeEach
    void setUp() {
        index = new BM25Index();
    }

    @Test
    void emptyIndexReturnsNoResults() {
        var results = index.search("ChatModel", 10, null);
        assertTrue(results.isEmpty());
    }

    @Test
    void singleDocumentMatch() {
        index.addChunk("doc1", "ChatModel is a LangChain4j interface",
            Map.of("domain", "jvm"), Map.of());
        var results = index.search("ChatModel", 10, null);
        assertEquals(1, results.size());
        assertEquals("doc1", results.getFirst().id());
    }

    @Test
    void idfWeightsRareTermsHigher() {
        index.addChunk("doc1", "ChatModel adapter for streaming responses",
            Map.of(), Map.of());
        index.addChunk("doc2", "adapter pattern for legacy systems",
            Map.of(), Map.of());
        // "ChatModel" appears in 1 doc (high IDF), "adapter" in 2 docs (low IDF)
        var results = index.search("ChatModel adapter", 10, null);
        assertEquals("doc1", results.getFirst().id());
    }

    @Test
    void exactCompoundMatchRanksHigher() {
        index.addChunk("doc1", "The ChatModel interface provides chat capabilities",
            Map.of(), Map.of());
        index.addChunk("doc2", "A generic model for chat applications",
            Map.of(), Map.of());
        // doc1 has "chatmodel" compound token (rare, high IDF) + "chat" + "model"
        // doc2 has only "chat" and "model" (no compound)
        var results = index.search("ChatModel", 10, null);
        assertEquals("doc1", results.getFirst().id());
    }

    @Test
    void removeDocument() {
        index.addChunk("doc1", "ChatModel is an interface", Map.of(), Map.of());
        assertEquals(1, index.size());
        index.removeDocument("doc1");
        assertEquals(0, index.size());
        assertTrue(index.search("ChatModel", 10, null).isEmpty());
    }

    @Test
    void payloadFilterEq() {
        index.addChunk("doc1", "CDI beans in Quarkus", Map.of("domain", "jvm"), Map.of());
        index.addChunk("doc2", "CDI beans in Python", Map.of("domain", "python"), Map.of());
        var results = index.search("CDI beans", 10, PayloadFilter.eq("domain", "jvm"));
        assertEquals(1, results.size());
        assertEquals("doc1", results.getFirst().id());
    }

    @Test
    void payloadFilterInWithListMetadata() {
        index.addChunk("doc1", "Qdrant vector search",
            Map.of(), Map.of("tags", List.of("qdrant", "search")));
        index.addChunk("doc2", "PostgreSQL full text search",
            Map.of(), Map.of("tags", List.of("postgresql", "fts")));
        var results = index.search("search", 10, PayloadFilter.in("tags", List.of("qdrant")));
        assertEquals(1, results.size());
        assertEquals("doc1", results.getFirst().id());
    }

    @Test
    void clearRemovesEverything() {
        index.addChunk("doc1", "something", Map.of(), Map.of());
        index.addChunk("doc2", "else", Map.of(), Map.of());
        index.clear();
        assertEquals(0, index.size());
    }

    @Test
    void topKLimitsResults() {
        for (int i = 0; i < 20; i++) {
            index.addChunk("doc" + i, "Quarkus CDI bean " + i, Map.of(), Map.of());
        }
        var results = index.search("Quarkus CDI", 5, null);
        assertEquals(5, results.size());
    }

    @Test
    void resultsContainContentAndMetadata() {
        index.addChunk("doc1", "ChatModel content here",
            Map.of("domain", "jvm", "type", "gotcha"),
            Map.of("tags", List.of("langchain4j")));
        var results = index.search("ChatModel", 10, null);
        var entry = results.getFirst();
        assertEquals("ChatModel content here", entry.content());
        assertEquals("jvm", entry.metadata().get("domain"));
        assertEquals(List.of("langchain4j"), entry.listMetadata().get("tags"));
    }
}
