package io.casehub.neocortex.examples.rag;

import dev.langchain4j.data.document.splitter.DocumentSplitters;
import io.casehub.neocortex.corpus.zip.FlatChangeSource;
import io.casehub.neocortex.corpus.zip.FlatCorpusStore;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.runtime.CorpusIngestionBinding;
import io.casehub.neocortex.rag.runtime.CorpusIngestionService;
import io.casehub.neocortex.rag.runtime.YamlFrontmatterExtractor;
import io.casehub.neocortex.rag.testing.InMemoryCaseRetriever;
import io.casehub.neocortex.rag.testing.InMemoryCursorStore;
import io.casehub.neocortex.rag.testing.InMemoryEmbeddingIngestor;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("smoke")
class RagEdgeCaseTest {

    private static final CorpusRef CORPUS = new CorpusRef("demo-tenant", "edge-case");

    @Test
    void emptyCorpusProducesNoChunks(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);
        var changeSource = new FlatChangeSource(store, tempDir);
        var ingestor = new InMemoryEmbeddingIngestor();
        var cursorStore = new InMemoryCursorStore();
        var binding = new CorpusIngestionBinding(
            "edge-case", CORPUS, changeSource, store, new YamlFrontmatterExtractor());

        var service = new CorpusIngestionService(ingestor, cursorStore);
        service.processBinding(binding, DocumentSplitters.recursive(500, 50));

        assertThat(ingestor.getChunks(CORPUS)).isEmpty();
    }

    @Test
    void corruptFrontmatterTreatedAsBody(@TempDir Path tempDir) throws IOException {
        var store = new FlatCorpusStore(tempDir);
        Files.writeString(tempDir.resolve("bad.md"), "---\nthis is not: valid: yaml: {{{\n---\nActual content here.");

        var changeSource = new FlatChangeSource(store, tempDir);
        var ingestor = new InMemoryEmbeddingIngestor();
        var cursorStore = new InMemoryCursorStore();
        var binding = new CorpusIngestionBinding(
            "edge-case", CORPUS, changeSource, store, new YamlFrontmatterExtractor());

        var service = new CorpusIngestionService(ingestor, cursorStore);
        service.processBinding(binding, DocumentSplitters.recursive(500, 50));

        assertThat(ingestor.getChunks(CORPUS)).isNotEmpty();
    }

    @Test
    void searchOnEmptyRetrieverReturnsEmptyList() {
        var retriever = InMemoryCaseRetriever.returning(List.of());
        var results = retriever.retrieve(RetrievalQuery.of("anything"), CORPUS, 5, null);
        assertThat(results).isEmpty();
    }
}
