package io.casehub.neocortex.corpus.zip;

public record EntryLocation(String zipFile, int version, long timestamp) {
    public EntryLocation {
        if (zipFile == null || zipFile.isBlank())
            throw new IllegalArgumentException("zipFile must not be null or blank");
        if (version < 1)
            throw new IllegalArgumentException("version must be >= 1");
    }
}
