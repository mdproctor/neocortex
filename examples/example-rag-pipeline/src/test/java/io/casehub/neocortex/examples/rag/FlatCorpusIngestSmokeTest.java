package io.casehub.neocortex.examples.rag;

import dev.langchain4j.data.document.splitter.DocumentSplitters;
import io.casehub.neocortex.corpus.zip.FlatChangeSource;
import io.casehub.neocortex.corpus.zip.FlatCorpusStore;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.runtime.CorpusIngestionBinding;
import io.casehub.neocortex.rag.runtime.CorpusIngestionService;
import io.casehub.neocortex.rag.runtime.YamlFrontmatterExtractor;
import io.casehub.neocortex.rag.testing.InMemoryCursorStore;
import io.casehub.neocortex.rag.testing.InMemoryEmbeddingIngestor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("smoke")
class FlatCorpusIngestSmokeTest {

    private static final CorpusRef CORPUS = new CorpusRef("demo-tenant", "examples");

    private InMemoryEmbeddingIngestor ingestor;
    private InMemoryCursorStore cursorStore;

    @BeforeEach
    void setUp() {
        ingestor = new InMemoryEmbeddingIngestor();
        cursorStore = new InMemoryCursorStore();
    }

    @Test
    void ingestsAllDocuments(@TempDir Path corpusDir) throws IOException {
        CorpusTestSupport.copyCorpus(corpusDir);
        var store = new FlatCorpusStore(corpusDir);
        var changeSource = new FlatChangeSource(store, corpusDir);
        var binding = new CorpusIngestionBinding(
            "examples", CORPUS, changeSource, store, new YamlFrontmatterExtractor());

        var service = new CorpusIngestionService(ingestor, cursorStore);
        service.processBinding(binding, DocumentSplitters.recursive(500, 50));

        assertThat(ingestor.getChunks(CORPUS)).isNotEmpty();
        assertThat(ingestor.listDocuments(CORPUS)).hasSize(CorpusTestSupport.documentCount());
    }

    @Test
    void incrementalIngestProducesNoNewChunks(@TempDir Path corpusDir) throws IOException {
        CorpusTestSupport.copyCorpus(corpusDir);
        var store = new FlatCorpusStore(corpusDir);
        var changeSource = new FlatChangeSource(store, corpusDir);
        var binding = new CorpusIngestionBinding(
            "examples", CORPUS, changeSource, store, new YamlFrontmatterExtractor());

        var service = new CorpusIngestionService(ingestor, cursorStore);
        var splitter = DocumentSplitters.recursive(500, 50);

        service.processBinding(binding, splitter);
        int firstRunChunks = ingestor.getChunks(CORPUS).size();

        service.processBinding(binding, splitter);
        int secondRunChunks = ingestor.getChunks(CORPUS).size();

        assertThat(secondRunChunks).isEqualTo(firstRunChunks);
    }

    @Test
    void metadataExtractedFromFrontmatter(@TempDir Path corpusDir) throws IOException {
        CorpusTestSupport.copyCorpus(corpusDir);
        var store = new FlatCorpusStore(corpusDir);
        var changeSource = new FlatChangeSource(store, corpusDir);
        var binding = new CorpusIngestionBinding(
            "examples", CORPUS, changeSource, store, new YamlFrontmatterExtractor());

        var service = new CorpusIngestionService(ingestor, cursorStore);
        service.processBinding(binding, DocumentSplitters.recursive(500, 50));

        var chunks = ingestor.getChunks(CORPUS);
        var chunkWithMetadata = chunks.stream()
            .filter(c -> c.metadata().containsKey("domain"))
            .findFirst();
        assertThat(chunkWithMetadata).isPresent();
        assertThat(chunkWithMetadata.get().metadata().get("domain"))
            .isIn("tech", "news", "legal");
    }
}
