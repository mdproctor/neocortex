package io.casehub.neocortex.memory;

import java.util.Map;
import java.util.Objects;

public record MemoryInput(
    String entityId,
    MemoryDomain domain,
    String tenantId,
    String caseId,
    String text,
    Map<String, String> attributes
) {
    public MemoryInput {
        Objects.requireNonNull(entityId,  "entityId required");
        Objects.requireNonNull(domain,    "domain required");
        Objects.requireNonNull(tenantId,  "tenantId required");
        Objects.requireNonNull(text,      "text required");
        if (text.isBlank()) throw new IllegalArgumentException("text must not be blank");
        Objects.requireNonNull(attributes, "attributes required");
        attributes = Map.copyOf(attributes);
    }
}
