package io.casehub.neocortex.mindmap;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record EdgeInput(
    String sourceNodeId,
    String targetNodeId,
    String edgeType,
    ConfidenceOrigin confidenceOrigin,
    Double confidence,
    String provenance,
    Instant validFrom,
    Instant validUntil,
    Double pleasure,
    Double arousal,
    Double dominance,
    Map<String, String> properties
) {

    public EdgeInput {
        Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(edgeType, "edgeType");
        if (confidenceOrigin == null) confidenceOrigin = ConfidenceOrigin.STATED;
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
