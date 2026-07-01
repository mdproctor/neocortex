package io.casehub.neocortex.rag;

import io.smallrye.mutiny.Uni;
import java.util.List;

/** Non-blocking counterpart of {@link EmbeddingIngestor}. Safe to subscribe to from the Vert.x event loop. */
public interface ReactiveEmbeddingIngestor {
    Uni<Void> ingest(CorpusRef corpus, List<ChunkInput> chunks);
    Uni<Void> deleteDocument(CorpusRef corpus, String sourceDocumentId);
    Uni<Void> deleteCorpus(CorpusRef corpus);
    Uni<List<String>> listDocuments(CorpusRef corpus);
}
