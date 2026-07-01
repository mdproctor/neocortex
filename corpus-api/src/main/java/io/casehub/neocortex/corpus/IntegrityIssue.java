package io.casehub.neocortex.corpus;

public record IntegrityIssue(Severity severity, String zipFile, String message) {
    public IntegrityIssue {
        if (severity == null)
            throw new IllegalArgumentException("severity must not be null");
        if (message == null || message.isBlank())
            throw new IllegalArgumentException("message must not be null or blank");
    }
}
