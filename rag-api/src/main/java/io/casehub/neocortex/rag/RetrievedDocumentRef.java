package io.casehub.neocortex.rag;

public record RetrievedDocumentRef(String sourceDocumentId, double relevanceScore) {
    public RetrievedDocumentRef {
        if (sourceDocumentId == null || sourceDocumentId.isBlank())
            throw new IllegalArgumentException("sourceDocumentId must not be null or blank");
    }
}
