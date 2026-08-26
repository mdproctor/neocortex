package io.casehub.neocortex.mindmap;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public interface MindMapEdge {

    String id();

    String sourceNodeId();

    String targetNodeId();

    String edgeType();

    ValidationTier tier();

    ConfidenceOrigin confidenceOrigin();

    double confidence();

    String provenance();

    Instant createdAt();

    Instant updatedAt();

    Instant validFrom();

    Instant validUntil();

    Double pleasure();

    Double arousal();

    Double dominance();

    Optional<String> property(String key);

    Map<String, String> properties();
}
