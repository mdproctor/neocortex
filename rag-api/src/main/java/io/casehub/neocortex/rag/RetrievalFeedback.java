package io.casehub.neocortex.rag;

import java.time.Instant;

public record RetrievalFeedback(
    String retrievalId,
    String sourceDocumentId,
    RetrievalOutcome outcome,
    Instant timestamp
) {
    public RetrievalFeedback {
        if (retrievalId == null || retrievalId.isBlank())
            throw new IllegalArgumentException("retrievalId must not be null or blank");
        if (sourceDocumentId == null || sourceDocumentId.isBlank())
            throw new IllegalArgumentException("sourceDocumentId must not be null or blank");
        if (outcome == null)
            throw new IllegalArgumentException("outcome must not be null");
        if (timestamp == null)
            throw new IllegalArgumentException("timestamp must not be null");
    }
}
