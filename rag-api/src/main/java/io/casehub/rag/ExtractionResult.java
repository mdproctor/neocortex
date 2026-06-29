package io.casehub.rag;

import java.util.List;
import java.util.Map;

public record ExtractionResult(String body, Map<String, String> metadata,
                                Map<String, List<String>> listMetadata) {
    public ExtractionResult {
        if (body == null)
            throw new IllegalArgumentException("body must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        listMetadata = listMetadata == null ? Map.of() : deepCopyListMetadata(listMetadata);
    }

    public ExtractionResult(String body, Map<String, String> metadata) {
        this(body, metadata, Map.of());
    }

    private static Map<String, List<String>> deepCopyListMetadata(Map<String, List<String>> m) {
        var copy = new java.util.LinkedHashMap<String, List<String>>();
        m.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Map.copyOf(copy);
    }
}
