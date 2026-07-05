package io.casehub.neocortex.memory;

import java.util.Objects;

public record MemoryScanRequest(
    String tenantId,
    String domain,
    String attributeKey,
    String attributeValue,
    int limit,
    String afterMemoryId
) {
    public MemoryScanRequest {
        Objects.requireNonNull(tenantId, "tenantId required");
        if (limit < 1)
            throw new IllegalArgumentException("limit must be >= 1, got: " + limit);
        if (attributeValue != null && attributeKey == null)
            throw new IllegalArgumentException("attributeValue requires attributeKey");
        if (attributeKey != null && attributeValue == null)
            throw new IllegalArgumentException("attributeKey requires attributeValue for filtered scan");
    }
}
