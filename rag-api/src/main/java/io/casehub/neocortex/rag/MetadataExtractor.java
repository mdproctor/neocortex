package io.casehub.neocortex.rag;

public interface MetadataExtractor {
    ExtractionResult extract(String path, byte[] content);
}
