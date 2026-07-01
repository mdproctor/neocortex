package io.casehub.neocortex.rag;

import java.util.List;

public interface EmbeddingIngestor {
    void ingest(CorpusRef corpus, List<ChunkInput> chunks);
    void deleteDocument(CorpusRef corpus, String sourceDocumentId);
    void deleteCorpus(CorpusRef corpus);
    List<String> listDocuments(CorpusRef corpus);
}
