package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.corpus.ChangeSource;
import io.casehub.neocortex.corpus.CorpusReader;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.MetadataExtractor;

import java.util.Objects;

public record CorpusIngestionBinding(
        String name,
        CorpusRef corpusRef,
        ChangeSource changeSource,
        CorpusReader corpusReader,
        MetadataExtractor metadataExtractor
) {
    public CorpusIngestionBinding {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("name must not be null or blank");
        Objects.requireNonNull(corpusRef, "corpusRef");
        Objects.requireNonNull(changeSource, "changeSource");
        Objects.requireNonNull(corpusReader, "corpusReader");
        Objects.requireNonNull(metadataExtractor, "metadataExtractor");
    }
}
