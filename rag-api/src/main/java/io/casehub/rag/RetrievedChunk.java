package io.casehub.rag;

import java.util.Map;

public record RetrievedChunk(String content, String sourceDocumentId,
                             double relevanceScore, Map<String, String> metadata) {
    public RetrievedChunk {
        if (content == null)
            throw new IllegalArgumentException("content must not be null");
        if (sourceDocumentId == null)
            throw new IllegalArgumentException("sourceDocumentId must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
