package io.casehub.neocortex.examples.rag;

import dev.langchain4j.data.document.splitter.DocumentSplitters;
import io.casehub.neocortex.corpus.zip.FlatChangeSource;
import io.casehub.neocortex.corpus.zip.FlatCorpusStore;
import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.CursorStore;
import io.casehub.neocortex.rag.runtime.CorpusIngestionBinding;
import io.casehub.neocortex.rag.runtime.CorpusIngestionService;
import io.casehub.neocortex.rag.runtime.YamlFrontmatterExtractor;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RagPipelineIT {

    private static final CorpusRef CORPUS = new CorpusRef("demo-tenant", "examples");

    @TempDir
    static Path corpusDir;

    @Inject EmbeddingIngestor ingestor;
    @Inject CursorStore cursorStore;
    @Inject CaseRetriever retriever;

    @BeforeAll
    static void copyCorpus() throws IOException {
        CorpusTestSupport.copyCorpus(corpusDir);
    }

    @Test
    @Order(1)
    void ingestAllDocuments() {
        var store = new FlatCorpusStore(corpusDir);
        var changeSource = new FlatChangeSource(store, corpusDir);
        var binding = new CorpusIngestionBinding(
            "examples", CORPUS, changeSource, store, new YamlFrontmatterExtractor());

        var service = new CorpusIngestionService(ingestor, cursorStore);
        service.processBinding(binding, DocumentSplitters.recursive(500, 50));

        assertThat(ingestor.listDocuments(CORPUS))
            .hasSize(CorpusTestSupport.documentCount());
    }

    @Test
    @Order(2)
    void techQueryReturnsTechDocs() {
        List<RetrievedChunk> results = retriever.retrieve(
            RetrievalQuery.of("How does dependency injection work?"), CORPUS, 5, null);
        assertThat(results).isNotEmpty();
        boolean hasTechDoc = results.stream()
            .anyMatch(c -> c.metadata().getOrDefault("domain", "").equals("tech"));
        assertThat(hasTechDoc).isTrue();
    }

    @Test
    @Order(2)
    void legalQueryReturnsLegalDocs() {
        List<RetrievedChunk> results = retriever.retrieve(
            RetrievalQuery.of("Can I end my lease early?"), CORPUS, 5, null);
        assertThat(results).isNotEmpty();
        var topDomain = results.get(0).metadata().getOrDefault("domain", "");
        assertThat(topDomain).isEqualTo("legal");
    }

    @Test
    @Order(2)
    void metadataRoundTrips() {
        List<RetrievedChunk> results = retriever.retrieve(
            RetrievalQuery.of("data protection GDPR"), CORPUS, 5, null);
        assertThat(results).isNotEmpty();
        var chunkWithMetadata = results.stream()
            .filter(c -> c.metadata().containsKey("domain"))
            .findFirst();
        assertThat(chunkWithMetadata).isPresent();
    }

    @Test
    @Order(2)
    void newsQueryReturnsNewsDocs() {
        List<RetrievedChunk> results = retriever.retrieve(
            RetrievalQuery.of("What happened with interest rates?"), CORPUS, 5, null);
        assertThat(results).isNotEmpty();
        boolean hasNewsDoc = results.stream()
            .anyMatch(c -> c.metadata().getOrDefault("domain", "").equals("news"));
        assertThat(hasNewsDoc).isTrue();
    }
}
