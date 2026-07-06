package io.casehub.neocortex.rag;

import java.util.Map;

public record RetrievedChunk(String content, String sourceDocumentId,
                             double relevanceScore, Map<String, String> metadata,
                             RelevanceGrade grade) {
    public RetrievedChunk {
        if (content == null)
            throw new IllegalArgumentException("content must not be null");
        if (sourceDocumentId == null)
            throw new IllegalArgumentException("sourceDocumentId must not be null");
        if (grade == null)
            throw new IllegalArgumentException("grade must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public RetrievedChunk(String content, String sourceDocumentId,
                          double relevanceScore, Map<String, String> metadata) {
        this(content, sourceDocumentId, relevanceScore, metadata, RelevanceGrade.UNGRADED);
    }

    public RetrievedChunk withGrade(RelevanceGrade grade) {
        return new RetrievedChunk(content, sourceDocumentId, relevanceScore, metadata, grade);
    }

    public RetrievedChunk withMetadata(Map<String, String> metadata) {
        return new RetrievedChunk(content, sourceDocumentId, relevanceScore, metadata, grade);
    }
}
