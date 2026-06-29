package io.casehub.rag;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record ChunkInput(String content, String sourceDocumentId,
                          Map<String, String> metadata,
                          Map<String, List<String>> listMetadata) {

    private static final Set<String> RESERVED_KEYS = Set.of("content", "sourceDocumentId");

    public ChunkInput {
        if (content == null || content.isBlank())
            throw new IllegalArgumentException("content must not be null or blank");
        if (sourceDocumentId == null || sourceDocumentId.isBlank())
            throw new IllegalArgumentException("sourceDocumentId must not be null or blank");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        listMetadata = listMetadata == null ? Map.of() : deepCopyListMetadata(listMetadata);
        for (String key : metadata.keySet()) {
            if (RESERVED_KEYS.contains(key))
                throw new IllegalArgumentException(
                    "metadata key '" + key + "' conflicts with ChunkInput field name");
        }
        for (String key : listMetadata.keySet()) {
            if (RESERVED_KEYS.contains(key))
                throw new IllegalArgumentException(
                    "metadata key '" + key + "' conflicts with ChunkInput field name");
        }
    }

    public ChunkInput(String content, String sourceDocumentId, Map<String, String> metadata) {
        this(content, sourceDocumentId, metadata, Map.of());
    }

    private static Map<String, List<String>> deepCopyListMetadata(Map<String, List<String>> m) {
        var copy = new java.util.LinkedHashMap<String, List<String>>();
        m.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Map.copyOf(copy);
    }
}
