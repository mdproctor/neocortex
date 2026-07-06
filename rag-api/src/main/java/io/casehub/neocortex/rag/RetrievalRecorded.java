package io.casehub.neocortex.rag;

import java.util.List;

public record RetrievalRecorded(
    String retrievalId,
    RetrievalQuery query,
    CorpusRef corpus,
    List<RetrievedDocumentRef> documents
) {
    public RetrievalRecorded {
        if (retrievalId == null || retrievalId.isBlank())
            throw new IllegalArgumentException("retrievalId must not be null or blank");
        if (query == null) throw new IllegalArgumentException("query must not be null");
        if (corpus == null) throw new IllegalArgumentException("corpus must not be null");
        documents = documents == null ? List.of() : List.copyOf(documents);
    }
}
