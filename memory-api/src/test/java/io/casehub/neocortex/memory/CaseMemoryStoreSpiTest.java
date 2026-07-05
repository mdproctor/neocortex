package io.casehub.neocortex.memory;

import io.casehub.platform.api.identity.CurrentPrincipal;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CaseMemoryStoreSpiTest {

    // Anonymous impl omitting all default methods.
    // Compiler error on any omitted method = it is abstract (RED state).
    // Compiles without implementing defaults = they are default (GREEN proves contract).
    final CaseMemoryStore sut = new CaseMemoryStore() {
        @Override public String store(MemoryInput i) { return "mem-1"; }
        @Override public List<Memory> query(MemoryQuery q) { return List.of(); }
        @Override public int erase(EraseRequest r) { return 0; }
    };

    static final MemoryDomain DOMAIN = new MemoryDomain("d");

    @Test
    void storeAll_delegates_to_store() {
        var a = new MemoryInput("e1", DOMAIN, "t1", null, "a", Map.of());
        var b = new MemoryInput("e1", DOMAIN, "t1", null, "b", Map.of());
        var result = sut.storeAll(List.of(a, b));
        assertEquals(List.of("mem-1", "mem-1"), result.stored());
        assertTrue(result.allSucceeded());
    }

    @Test
    void storeAll_collects_backend_failure_instead_of_throwing() {
        CaseMemoryStore failingStore = new CaseMemoryStore() {
            int call = 0;
            @Override public String store(MemoryInput i) {
                if (call++ == 1) throw new IllegalStateException("backend error");
                return "mem-ok";
            }
            @Override public List<Memory> query(MemoryQuery q) { return List.of(); }
            @Override public int erase(EraseRequest r) { return 0; }
        };
        var inputs = List.of(
            new MemoryInput("e1", DOMAIN, "t1", null, "a", Map.of()),
            new MemoryInput("e1", DOMAIN, "t1", null, "b", Map.of()),
            new MemoryInput("e1", DOMAIN, "t1", null, "c", Map.of())
        );
        var result = failingStore.storeAll(inputs);
        assertEquals(List.of("mem-ok", "mem-ok"), result.stored());
        assertEquals(1, result.failures().size());
        assertEquals(1, result.failures().get(0).inputIndex());
    }

    @Test
    void storeAll_propagates_security_exception_immediately() {
        CaseMemoryStore tenantCheckStore = new CaseMemoryStore() {
            @Override public String store(MemoryInput i) {
                if ("bad".equals(i.tenantId())) throw new SecurityException("tenant mismatch");
                return "mem-ok";
            }
            @Override public List<Memory> query(MemoryQuery q) { return List.of(); }
            @Override public int erase(EraseRequest r) { return 0; }
        };
        var inputs = List.of(
            new MemoryInput("e1", DOMAIN, "t1", null, "a", Map.of()),
            new MemoryInput("e1", DOMAIN, "bad", null, "b", Map.of())
        );
        assertThrows(SecurityException.class, () -> tenantCheckStore.storeAll(inputs));
    }

    @Test
    void eraseById_default_throws_MemoryCapabilityException() {
        final var ex = assertThrows(MemoryCapabilityException.class,
            () -> sut.eraseById("mem-1", "entity-1", "tenant-1"));
        assertEquals(MemoryCapability.ERASE_BY_ID, ex.required());
    }

    @Test
    void eraseEntity_default_throws_MemoryCapabilityException() {
        final var ex = assertThrows(MemoryCapabilityException.class,
            () -> sut.eraseEntity("entity-1", "tenant-1"));
        assertEquals(MemoryCapability.ERASE_ENTITY, ex.required());
    }

    @Test
    void capabilities_default_returns_empty_set() {
        assertTrue(sut.capabilities().isEmpty());
    }

    @Test
    void requireCapability_throws_for_undeclared_capability() {
        final var ex = assertThrows(MemoryCapabilityException.class,
            () -> sut.requireCapability(MemoryCapability.TEMPORAL_GRAPH));
        assertEquals(MemoryCapability.TEMPORAL_GRAPH, ex.required());
    }

    // Via static utility directly (callable by all adapters)
    @Test
    void memoryPermissions_throws_on_mismatch() {
        assertThrows(SecurityException.class,
            () -> MemoryPermissions.assertTenant("wrong", principal("real")));
    }

    @Test
    void memoryPermissions_passes_on_match() {
        assertDoesNotThrow(() -> MemoryPermissions.assertTenant("real", principal("real")));
    }

    @Test
    void eraseEntityAcrossTenants_default_throws_MemoryCapabilityException() {
        final var ex = assertThrows(MemoryCapabilityException.class,
            () -> sut.eraseEntityAcrossTenants("entity-1", Set.of("tenant-1")));
        assertEquals(MemoryCapability.CROSS_TENANT_ERASE, ex.required());
    }

    @Test
    void scan_default_throws_MemoryCapabilityException() {
        var request = new MemoryScanRequest("tenant-1", null, null, null, 10, null);
        final var ex = assertThrows(MemoryCapabilityException.class,
            () -> sut.scan(request));
        assertEquals(MemoryCapability.SCAN, ex.required());
    }

    private static CurrentPrincipal principal(String tenancyId) {
        return new CurrentPrincipal() {
            @Override public String actorId() { return "actor"; }
            @Override public Set<String> groups() { return Set.of(); }
            @Override public String tenancyId() { return tenancyId; }
            @Override public boolean isCrossTenantAdmin() { return false; }
        };
    }
}
