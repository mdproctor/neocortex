package io.casehub.neocortex.examples.rag;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import io.casehub.neocortex.corpus.zip.CorpusConfig;
import io.casehub.neocortex.corpus.zip.ZipChangeSource;
import io.casehub.neocortex.corpus.zip.ZipCorpusStore;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.CursorStore;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import io.casehub.neocortex.rag.runtime.CorpusIngestionBinding;
import io.casehub.neocortex.rag.runtime.CorpusIngestionService;
import io.casehub.neocortex.rag.runtime.YamlFrontmatterExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public final class ZipCorpusIngestDemo {

    public static void run(EmbeddingIngestor ingestor, CursorStore cursorStore, Path corpusDir) throws IOException {
        var config = new CorpusConfig("zip-examples", corpusDir, 4096);
        var store = new ZipCorpusStore(config);

        System.out.println("=== Writing documents to ZIP corpus ===");
        for (String file : ExampleCorpus.FILES) {
            try (InputStream is = ZipCorpusIngestDemo.class.getClassLoader().getResourceAsStream(file)) {
                if (is == null) throw new IllegalStateException("Missing classpath resource: " + file);
                String relativePath = file.substring("corpus/".length());
                store.append(relativePath, is.readAllBytes());
            }
        }

        var changeSource = new ZipChangeSource(store);
        var binding = new CorpusIngestionBinding(
            "zip-examples",
            new CorpusRef("demo-tenant", "zip-examples"),
            changeSource,
            store,
            new YamlFrontmatterExtractor()
        );

        var service = new CorpusIngestionService(ingestor, cursorStore);
        DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);

        System.out.println("=== Ingesting from ZIP corpus ===");
        service.processBinding(binding, splitter);
        System.out.println("Ingestion complete.");
    }
}
