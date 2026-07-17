package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.platform.api.path.Path;

import java.time.Instant;
import java.util.Objects;

public sealed interface CbrCasesErased {
    String tenantId();
    int erasedCount();
    Instant erasedAt();

    record ByRequest(String tenantId, int erasedCount,
                     String entityId, MemoryDomain domain, String caseId,
                     Instant erasedAt) implements CbrCasesErased {
        public ByRequest {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(erasedAt, "erasedAt");
        }
    }

    record ByEntity(String tenantId, int erasedCount,
                    String entityId,
                    Instant erasedAt) implements CbrCasesErased {
        public ByEntity {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(erasedAt, "erasedAt");
        }
    }

    record ByScope(String tenantId, int erasedCount,
                   Path scope,
                   Instant erasedAt) implements CbrCasesErased {
        public ByScope {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(erasedAt, "erasedAt");
        }
    }
}
