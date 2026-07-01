package io.casehub.neocortex.examples.rag;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import io.casehub.neocortex.corpus.zip.FlatChangeSource;
import io.casehub.neocortex.corpus.zip.FlatCorpusStore;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.CursorStore;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import io.casehub.neocortex.rag.runtime.CorpusIngestionBinding;
import io.casehub.neocortex.rag.runtime.CorpusIngestionService;
import io.casehub.neocortex.rag.runtime.YamlFrontmatterExtractor;

import java.nio.file.Path;

public final class FlatCorpusIngestDemo {

    public static void run(EmbeddingIngestor ingestor, CursorStore cursorStore, Path corpusDir) {
        var store = new FlatCorpusStore(corpusDir);
        var changeSource = new FlatChangeSource(store, corpusDir);
        var binding = new CorpusIngestionBinding(
            "examples",
            new CorpusRef("demo-tenant", "examples"),
            changeSource,
            store,
            new YamlFrontmatterExtractor()
        );

        var service = new CorpusIngestionService(ingestor, cursorStore);
        DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);

        System.out.println("=== Initial Ingestion ===");
        service.processBinding(binding, splitter);
        System.out.println("Ingestion complete.");

        System.out.println("\n=== Incremental Ingestion (no changes) ===");
        service.processBinding(binding, splitter);
        System.out.println("No new documents processed — cursor is current.");

        System.out.println("\n=== Reconciliation ===");
        service.reconcile("examples", binding, splitter);
        System.out.println("Reconciliation complete — Qdrant state matches corpus.");
    }
}
