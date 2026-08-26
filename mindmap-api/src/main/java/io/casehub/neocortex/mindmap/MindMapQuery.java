package io.casehub.neocortex.mindmap;

import java.util.Objects;
import java.util.Set;

public record MindMapQuery(
    String tenantId,
    String subgraphId,
    String text,
    String edgeType,
    Set<String> traits,
    Double minConfidence,
    ConfidenceOrigin confidenceOrigin,
    boolean includeSuperseded,
    int limit
) {

    public MindMapQuery {
        Objects.requireNonNull(tenantId, "tenantId");
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        traits = traits == null ? null : Set.copyOf(traits);
    }
}
