package io.casehub.neocortex.rag.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodeDomainTokenizerTest {

    @Test
    void camelCaseSplit() {
        assertTokens("ChatModel", "chatmodel", "chat", "model");
    }

    @Test
    void allUppersThenCamel() {
        assertTokens("HTTPClient", "httpclient", "http", "client");
    }

    @Test
    void mixedAlphaNumeric() {
        assertTokens("BM25", "bm25", "bm", "25");
    }

    @Test
    void annotationStripped() {
        assertTokens("@DefaultBean", "defaultbean", "default", "bean");
    }

    @Test
    void dotSeparatedPackage() {
        var tokens = CodeDomainTokenizer.tokenize("io.casehub.platform");
        assertTrue(tokens.containsAll(List.of("io", "casehub", "platform")));
    }

    @Test
    void singleLowercaseWord() {
        assertTokens("xml", "xml");
    }

    @Test
    void emptyAndNull() {
        assertEquals(List.of(), CodeDomainTokenizer.tokenize(""));
        assertEquals(List.of(), CodeDomainTokenizer.tokenize(null));
    }

    @Test
    void preservesCompoundAlongsideComponents() {
        var tokens = CodeDomainTokenizer.tokenize("DefaultBean");
        assertTrue(tokens.contains("defaultbean"));
        assertTrue(tokens.contains("default"));
        assertTrue(tokens.contains("bean"));
    }

    @Test
    void geIdTokenization() {
        var tokens = CodeDomainTokenizer.tokenize("GE-20260629-63d619");
        assertTrue(tokens.containsAll(List.of("ge", "20260629", "63d619")));
    }

    @Test
    void configProperty() {
        var tokens = CodeDomainTokenizer.tokenize("quarkus.langchain4j.ollama.devservices.enabled");
        assertTrue(tokens.containsAll(List.of("quarkus", "langchain4j", "ollama", "devservices", "enabled")));
    }

    private void assertTokens(String input, String... expected) {
        var tokens = CodeDomainTokenizer.tokenize(input);
        for (String exp : expected) {
            assertTrue(tokens.contains(exp),
                "Expected token '" + exp + "' in " + tokens + " for input '" + input + "'");
        }
    }
}
