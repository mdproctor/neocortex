package io.casehub.neocortex.rag.runtime;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.neocortex.memory.MemoryPermissions;

@FunctionalInterface
interface TenantGuard {

    void assertTenant(String tenantId);

    static TenantGuard of(CurrentPrincipal principal) {
        return principal == null
            ? tenantId -> {}
            : tenantId -> MemoryPermissions.assertTenant(
                tenantId, principal, RequestContextCheck.isActive());
    }
}
