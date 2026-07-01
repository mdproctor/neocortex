package io.casehub.neocortex.memory;

import java.util.Objects;

public record EraseRequest(
    String entityId,
    MemoryDomain domain,
    String tenantId,
    String caseId
) {
    public EraseRequest {
        Objects.requireNonNull(entityId, "entityId required");
        Objects.requireNonNull(domain,   "domain required");
        Objects.requireNonNull(tenantId, "tenantId required");
    }
}
