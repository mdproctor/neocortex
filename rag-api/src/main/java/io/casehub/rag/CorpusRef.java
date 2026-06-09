package io.casehub.rag;

public record CorpusRef(String tenantId, String corpusName) {
    public CorpusRef {
        if (tenantId == null || tenantId.isBlank())
            throw new IllegalArgumentException("tenantId must not be null or blank");
        if (corpusName == null || corpusName.isBlank())
            throw new IllegalArgumentException("corpusName must not be null or blank");
    }
}
