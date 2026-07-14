package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import java.util.Objects;

public record CbrRetentionPolicy(
    String tenantId,
    MemoryDomain domain,
    String caseType,
    Integer maxAgeDays,
    Integer maxCasesPerType
) {
    public CbrRetentionPolicy {
        Objects.requireNonNull(tenantId, "tenantId required");
        Objects.requireNonNull(domain, "domain required");
        if (maxAgeDays == null && maxCasesPerType == null) {
            throw new IllegalArgumentException(
                "at least one of maxAgeDays or maxCasesPerType must be non-null");
        }
        if (maxAgeDays != null && maxAgeDays <= 0) {
            throw new IllegalArgumentException("maxAgeDays must be positive, got " + maxAgeDays);
        }
        if (maxCasesPerType != null && maxCasesPerType <= 0) {
            throw new IllegalArgumentException("maxCasesPerType must be positive, got " + maxCasesPerType);
        }
    }
}
