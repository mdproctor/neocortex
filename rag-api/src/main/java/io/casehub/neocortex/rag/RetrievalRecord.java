package io.casehub.neocortex.rag;

import java.time.Instant;
import java.util.List;

public record RetrievalRecord(
    String retrievalId,
    RetrievalQuery query,
    CorpusRef corpus,
    List<RetrievedDocumentRef> documents,
    int maxResults,
    Instant timestamp
) {
    public RetrievalRecord {
        if (retrievalId == null || retrievalId.isBlank())
            throw new IllegalArgumentException("retrievalId must not be null or blank");
        if (query == null) throw new IllegalArgumentException("query must not be null");
        if (corpus == null) throw new IllegalArgumentException("corpus must not be null");
        documents = documents == null ? List.of() : List.copyOf(documents);
        if (maxResults < 1)
            throw new IllegalArgumentException("maxResults must be positive");
        if (timestamp == null)
            throw new IllegalArgumentException("timestamp must not be null");
    }
}
