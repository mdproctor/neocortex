package io.casehub.rag;

import java.util.Map;

public record ChunkInput(String content, String sourceDocumentId, Map<String, String> metadata) {
    public ChunkInput {
        if (content == null || content.isBlank())
            throw new IllegalArgumentException("content must not be null or blank");
        if (sourceDocumentId == null || sourceDocumentId.isBlank())
            throw new IllegalArgumentException("sourceDocumentId must not be null or blank");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
