package io.casehub.neocortex.corpus;

public record ChangedEntry(String path, ChangeType type) {
    public ChangedEntry {
        if (path == null || path.isBlank())
            throw new IllegalArgumentException("path must not be null or blank");
        if (type == null)
            throw new IllegalArgumentException("type must not be null");
    }
}
