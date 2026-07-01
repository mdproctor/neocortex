package io.casehub.neocortex.memory;

import io.casehub.platform.api.identity.CurrentPrincipal;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class MemoryPermissionsTest {

    private static CurrentPrincipal principal(String tenancyId) {
        return new CurrentPrincipal() {
            @Override public String actorId()  { return "actor"; }
            @Override public Set<String> groups() { return Set.of(); }
            @Override public String tenancyId()   { return tenancyId; }
            @Override public boolean isCrossTenantAdmin() { return false; }
        };
    }

    @Test
    void matching_tenant_does_not_throw() {
        assertDoesNotThrow(() ->
            MemoryPermissions.assertTenant("tenant-a", principal("tenant-a")));
    }

    @Test
    void mismatched_tenant_throws_security_exception() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
            MemoryPermissions.assertTenant("tenant-b", principal("tenant-a")));
        assertTrue(ex.getMessage().contains("tenant-b"));
        assertTrue(ex.getMessage().contains("tenant-a"));
    }

    // ── 3-arg async-aware overload ─────────────────────────────────────────────

    @Test
    void three_arg_skips_check_when_not_in_request_context() {
        // requestContextActive=false simulates @ObservesAsync thread — trust tenantId
        assertDoesNotThrow(() ->
            MemoryPermissions.assertTenant("mine", principal("other-tenant"), false));
    }

    @Test
    void three_arg_enforces_when_in_request_context() {
        // requestContextActive=true simulates normal HTTP request — enforce
        assertThrows(SecurityException.class, () ->
            MemoryPermissions.assertTenant("mine", principal("other-tenant"), true));
    }

    @Test
    void three_arg_passes_matching_tenant_when_in_request_context() {
        assertDoesNotThrow(() ->
            MemoryPermissions.assertTenant("mine", principal("mine"), true));
    }

    // ── Cross-tenant admin checks ──────────────────────────────────────────────

    private static CurrentPrincipal crossTenantAdminPrincipal() {
        return new CurrentPrincipal() {
            @Override public String actorId()             { return "admin"; }
            @Override public Set<String> groups()         { return Set.of(); }
            @Override public String tenancyId()           { return "platform"; }
            @Override public boolean isCrossTenantAdmin() { return true; }
        };
    }

    @Test
    void assertCrossTenantAdmin_throws_SecurityException_when_not_admin() {
        SecurityException ex = assertThrows(SecurityException.class,
            () -> MemoryPermissions.assertCrossTenantAdmin(principal("t")));
        assertTrue(ex.getMessage().contains("actor"));
    }

    @Test
    void assertCrossTenantAdmin_passes_when_is_cross_tenant_admin() {
        assertDoesNotThrow(() ->
            MemoryPermissions.assertCrossTenantAdmin(crossTenantAdminPrincipal()));
    }
}
