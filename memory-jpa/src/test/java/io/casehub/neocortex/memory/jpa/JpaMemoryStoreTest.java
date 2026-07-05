package io.casehub.neocortex.memory.jpa;

import io.casehub.neocortex.memory.*;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.casehub.neocortex.memory.testing.CaseMemoryStoreContractTest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the full CaseMemoryStoreContractTest suite against JpaMemoryStore on H2.
 *
 * <p><strong>Test isolation:</strong> @TestTransaction at class level does not roll back inherited
 * test methods (CDI interception applies only to methods declared in this class, not inherited ones).
 * Instead we use explicit @BeforeEach + @AfterEach in REQUIRES_NEW transactions so each test sees
 * a clean slate regardless of the previous test's outcome.
 *
 * <p><strong>Principal:</strong> FixedCurrentPrincipal (@Alternative @Priority(1)) is active on the
 * test classpath and displaces MockCurrentPrincipal. Its tenancyId defaults to DEFAULT_TENANT_ID;
 * setup() sets it to TENANT ("tenant-1") before each test.
 */
@QuarkusTest
@ActivateRequestContext
class JpaMemoryStoreTest extends CaseMemoryStoreContractTest {

    @Inject JpaMemoryStore jpaStore;
    @Inject FixedCurrentPrincipal principal;
    @Inject EntityManager em;

    @BeforeEach
    @Transactional(TxType.REQUIRES_NEW)
    void setup() {
        principal.setTenancyId(TENANT);
        principal.setCrossTenantAdmin(false);
        em.createQuery("DELETE FROM MemoryEntry").executeUpdate();
    }

    @AfterEach
    @Transactional(TxType.REQUIRES_NEW)
    void cleanup() {
        em.createQuery("DELETE FROM MemoryEntry").executeUpdate();
    }

    @Override
    protected CaseMemoryStore store() {
        return jpaStore;
    }

    // JPA-specific: assertTenant guard fires before any backend call
    @Test
    void assertTenant_mismatch_throws_before_backend_call() {
        var bad = new MemoryInput("entity-1", DOMAIN, OTHER_TENANT, null, "x", Map.of());
        assertThrows(SecurityException.class, () -> store().store(bad));
    }

    @Test
    void eraseEntityAcrossTenants_deletes_across_tenants() {
        // Seed under TENANT (principal already set to TENANT in @BeforeEach)
        store().store(new MemoryInput("entity-1", DOMAIN, TENANT, null, "data-a", Map.of()));
        // Seed under OTHER_TENANT
        principal.setTenancyId(OTHER_TENANT);
        store().store(new MemoryInput("entity-1", DOMAIN, OTHER_TENANT, null, "data-b", Map.of()));
        // Erase as cross-tenant admin
        principal.setTenancyId(TENANT);
        principal.setCrossTenantAdmin(true);
        int count = jpaStore.eraseEntityAcrossTenants("entity-1", Set.of(TENANT, OTHER_TENANT));
        assertEquals(2, count);
    }

    @Test
    void eraseEntityAcrossTenants_requires_cross_tenant_admin() {
        principal.setCrossTenantAdmin(false);
        assertThrows(SecurityException.class,
            () -> jpaStore.eraseEntityAcrossTenants("entity-1", Set.of(TENANT)));
    }

    @Test
    void storeAll_mixed_tenant_does_not_persist_any_entry() {
        var good = new MemoryInput("entity-1", DOMAIN, TENANT,       null, "good", Map.of());
        var bad  = new MemoryInput("entity-1", DOMAIN, OTHER_TENANT, null, "bad",  Map.of());

        assertThrows(SecurityException.class,
            () -> store().storeAll(List.of(good, bad)));

        // With the single-transaction override: SecurityException fires on the first mismatched
        // item during stream.toList() materialisation, before MemoryEntry.persist() is called → 0 rows.
        // Without the override (SPI default): item 0 committed in its own transaction → 1 row.
        assertEquals(0, store().query(query()).size(),
            "Mixed-tenant storeAll must not persist any entries");
    }

    @Test
    void scan_returnsEntriesMatchingAttribute() {
        // Store two CBR entries and one non-CBR entry
        store().store(new MemoryInput("e1", DOMAIN, TENANT, "case-1", "problem 1",
            Map.of("cbr.caseType", "aml", "solution", "sol1")));
        store().store(new MemoryInput("e1", DOMAIN, TENANT, "case-2", "problem 2",
            Map.of("cbr.caseType", "aml", "solution", "sol2")));
        store().store(new MemoryInput("e1", DOMAIN, TENANT, "case-3", "problem 3",
            Map.of("other", "value")));

        var request = new MemoryScanRequest(TENANT, null, "cbr.caseType", "aml", 100, null);
        var results = jpaStore.scan(request);

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(m ->
            assertThat(m.attributes())
                .containsEntry("cbr.caseType", "aml"));
    }

    @Test
    void scan_respectsLimit() {
        for (int i = 0; i < 5; i++) {
            store().store(new MemoryInput("e1", DOMAIN, TENANT, "case-" + i, "p" + i,
                Map.of("cbr.caseType", "aml")));
        }
        var request = new MemoryScanRequest(TENANT, null, "cbr.caseType", "aml", 2, null);
        var results = jpaStore.scan(request);
        assertThat(results).hasSize(2);
    }

    @Test
    void scan_paginatesWithCursor() {
        for (int i = 0; i < 5; i++) {
            store().store(new MemoryInput("e1", DOMAIN, TENANT, "case-" + i, "p" + i,
                Map.of("cbr.caseType", "aml")));
        }
        // First page
        var page1 = jpaStore.scan(new MemoryScanRequest(TENANT, null, "cbr.caseType", "aml", 3, null));
        assertThat(page1).hasSize(3);

        // Second page using cursor from last element
        String cursor = page1.getLast().memoryId();
        var page2 = jpaStore.scan(new MemoryScanRequest(TENANT, null, "cbr.caseType", "aml", 3, cursor));
        assertThat(page2).hasSize(2);

        // No overlap
        var page1Ids = page1.stream().map(Memory::memoryId).toList();
        var page2Ids = page2.stream().map(Memory::memoryId).toList();
        assertThat(page1Ids).doesNotContainAnyElementsOf(page2Ids);
    }

    @Test
    void scan_filtersByTenant() {
        store().store(new MemoryInput("e1", DOMAIN, TENANT, "case-1", "p1",
            Map.of("cbr.caseType", "aml")));
        principal.setTenancyId(OTHER_TENANT);
        store().store(new MemoryInput("e1", DOMAIN, OTHER_TENANT, "case-2", "p2",
            Map.of("cbr.caseType", "aml")));

        principal.setTenancyId(TENANT);
        var results = jpaStore.scan(new MemoryScanRequest(TENANT, null, "cbr.caseType", "aml", 100, null));
        assertThat(results).hasSize(1);
    }

    @Test
    void scan_filtersByDomain() {
        store().store(new MemoryInput("e1", DOMAIN, TENANT, "case-1", "p1",
            Map.of("cbr.caseType", "aml")));
        store().store(new MemoryInput("e1", new MemoryDomain("other"), TENANT, "case-2", "p2",
            Map.of("cbr.caseType", "aml")));

        var results = jpaStore.scan(new MemoryScanRequest(TENANT, DOMAIN.name(), "cbr.caseType", "aml", 100, null));
        assertThat(results).hasSize(1);
    }

    @Test
    void scan_withoutAttributeFilter_returnsAllForTenant() {
        store().store(new MemoryInput("e1", DOMAIN, TENANT, "case-1", "p1", Map.of("a", "1")));
        store().store(new MemoryInput("e2", DOMAIN, TENANT, "case-2", "p2", Map.of("b", "2")));
        principal.setTenancyId(OTHER_TENANT);
        store().store(new MemoryInput("e1", DOMAIN, OTHER_TENANT, "case-3", "p3", Map.of("c", "3")));

        principal.setTenancyId(TENANT);
        var results = jpaStore.scan(new MemoryScanRequest(TENANT, null, null, null, 100, null));
        assertThat(results).hasSize(2);
    }

    @Test
    void scan_declaresScanCapability() {
        assertTrue(jpaStore.capabilities().contains(MemoryCapability.SCAN));
    }

    @Test
    void discoverTenants_returnsDistinctTenantIds() {
        principal.setTenancyId("tenant-a");
        store().store(new MemoryInput("e1", DOMAIN, "tenant-a", null, "text1", Map.of("cbr.caseType", "game")));
        principal.setTenancyId("tenant-b");
        store().store(new MemoryInput("e2", DOMAIN, "tenant-b", null, "text2", Map.of("cbr.caseType", "game")));
        principal.setTenancyId("tenant-a");
        store().store(new MemoryInput("e3", DOMAIN, "tenant-a", null, "text3", Map.of("cbr.caseType", "game")));
        principal.setTenancyId("tenant-c");
        store().store(new MemoryInput("e4", DOMAIN, "tenant-c", null, "text4", Map.of("cbr.caseType", "other")));

        principal.setCrossTenantAdmin(true);
        Set<String> tenants = jpaStore.discoverTenants("cbr.caseType", "game");
        assertThat(tenants).containsExactlyInAnyOrder("tenant-a", "tenant-b");
    }

    @Test
    void discoverTenants_allTenantsWhenNoFilter() {
        principal.setTenancyId("tenant-a");
        store().store(new MemoryInput("e1", DOMAIN, "tenant-a", null, "text1", Map.of()));
        principal.setTenancyId("tenant-b");
        store().store(new MemoryInput("e2", DOMAIN, "tenant-b", null, "text2", Map.of()));

        principal.setCrossTenantAdmin(true);
        Set<String> tenants = jpaStore.discoverTenants(null, null);
        assertThat(tenants).containsExactlyInAnyOrder("tenant-a", "tenant-b");
    }

    @Test
    void discoverTenants_emptyWhenNoMatch() {
        principal.setTenancyId("tenant-a");
        store().store(new MemoryInput("e1", DOMAIN, "tenant-a", null, "text1", Map.of("k", "v")));

        principal.setCrossTenantAdmin(true);
        Set<String> tenants = jpaStore.discoverTenants("k", "nonexistent");
        assertThat(tenants).isEmpty();
    }
}
