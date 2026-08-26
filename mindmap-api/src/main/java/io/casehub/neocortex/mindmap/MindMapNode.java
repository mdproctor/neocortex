package io.casehub.neocortex.mindmap;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface MindMapNode {

    String id();

    String name();

    String subgraphId();

    ConfidenceOrigin confidenceOrigin();

    double confidence();

    String provenance();

    Instant createdAt();

    Instant updatedAt();

    Instant confirmedAt();

    Instant validFrom();

    Instant validUntil();

    Set<String> traits();

    Set<NodeRef> refs();

    Double pleasure();

    Double arousal();

    Double dominance();

    Optional<String> property(String key);

    Map<String, String> properties();
}
