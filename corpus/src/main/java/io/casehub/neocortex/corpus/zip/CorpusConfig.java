package io.casehub.neocortex.corpus.zip;

import java.nio.file.Path;

public record CorpusConfig(String corpusName, Path source, long maxZipSize) {

    public static final long DEFAULT_MAX_ZIP_SIZE = 100 * 1024 * 1024; // 100 MB

    public CorpusConfig {
        if (corpusName == null || corpusName.isBlank())
            throw new IllegalArgumentException("corpusName must not be null or blank");
        if (source == null)
            throw new IllegalArgumentException("source must not be null");
        if (maxZipSize <= 0)
            throw new IllegalArgumentException("maxZipSize must be > 0");
    }

    public CorpusConfig(String corpusName, Path source) {
        this(corpusName, source, DEFAULT_MAX_ZIP_SIZE);
    }
}
