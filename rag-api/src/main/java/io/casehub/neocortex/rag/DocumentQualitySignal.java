package io.casehub.neocortex.rag;

public record DocumentQualitySignal(
        String sourceDocumentId,
        DocumentStats stats,
        QualitySignal signal) {
}
