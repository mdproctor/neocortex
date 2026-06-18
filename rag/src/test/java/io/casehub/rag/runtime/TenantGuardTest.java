package io.casehub.rag.runtime;

import io.casehub.platform.api.identity.CurrentPrincipal;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class TenantGuardTest {

    @Test
    void nullPrincipalProducesNoOpGuard() {
        TenantGuard guard = TenantGuard.of(null);
        assertThatCode(() -> guard.assertTenant("any-tenant"))
            .doesNotThrowAnyException();
    }

    @Test
    void matchingTenantPasses() {
        CurrentPrincipal principal = stubPrincipal("tenant-1");
        TenantGuard guard = TenantGuard.of(principal);
        assertThatCode(() -> guard.assertTenant("tenant-1"))
            .doesNotThrowAnyException();
    }

    @Test
    void mismatchedTenantThrows() {
        CurrentPrincipal principal = stubPrincipal("tenant-1");
        TenantGuard guard = TenantGuard.of(principal);
        assertThatThrownBy(() -> guard.assertTenant("tenant-2"))
            .isInstanceOf(SecurityException.class);
    }

    private static CurrentPrincipal stubPrincipal(String tenantId) {
        return new CurrentPrincipal() {
            @Override public String actorId() { return "test"; }
            @Override public Set<String> groups() { return Set.of(); }
            @Override public String tenancyId() { return tenantId; }
            @Override public boolean isCrossTenantAdmin() { return false; }
        };
    }
}
