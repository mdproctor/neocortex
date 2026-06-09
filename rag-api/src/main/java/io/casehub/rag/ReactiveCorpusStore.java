package io.casehub.rag;

import io.smallrye.mutiny.Uni;
import java.util.List;

public interface ReactiveCorpusStore {
    Uni<Void> ingest(CorpusRef corpus, List<ChunkInput> chunks);
    Uni<Void> deleteDocument(CorpusRef corpus, String sourceDocumentId);
    Uni<Void> deleteCorpus(CorpusRef corpus);
    Uni<List<String>> listDocuments(CorpusRef corpus);
}
