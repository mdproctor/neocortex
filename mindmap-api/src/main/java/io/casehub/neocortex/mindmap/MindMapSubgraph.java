package io.casehub.neocortex.mindmap;

import java.time.Instant;

public record MindMapSubgraph(
    String id,
    String name,
    SubgraphType type,
    String rootNodeId,
    String tenantId,
    Instant createdAt
) {}
