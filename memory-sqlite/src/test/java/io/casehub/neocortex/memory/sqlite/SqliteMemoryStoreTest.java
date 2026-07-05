package io.casehub.neocortex.memory.sqlite;

import io.casehub.neocortex.memory.*;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.casehub.neocortex.memory.testing.CaseMemoryStoreContractTest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@ActivateRequestContext
class SqliteMemoryStoreTest extends CaseMemoryStoreContractTest {

    @Inject SqliteMemoryStore sqliteStore;
    @Inject FixedCurrentPrincipal principal;

    @BeforeEach
    void setup() {
        // Clean all data before each test to avoid interference from contract tests
        principal.setCrossTenantAdmin(true);
        principal.setTenancyId(TENANT);
        // Erase all possible test entities across both tenants
        for (String entityId : List.of("entity-1", "entity-2", "e1", "e2")) {
            try {
                sqliteStore.eraseEntityAcrossTenants(entityId, Set.of(TENANT, OTHER_TENANT));
            } catch (Exception e) {
                // Ignore - entity may not exist
            }
        }
        principal.setCrossTenantAdmin(false);
        principal.setTenancyId(TENANT);
    }

    @AfterEach
    void cleanUp() {
        // Cleanup done in @BeforeEach
        principal.setTenancyId(TENANT);
        principal.setCrossTenantAdmin(false);
    }

    @Override
    protected CaseMemoryStore store() {
        return sqliteStore;
    }

    @Test
    void eraseEntityAcrossTenants_deletes_across_tenants() {
        // Seed under TENANT
        store().store(new MemoryInput("entity-1", DOMAIN, TENANT, null, "data-a", Map.of()));
        // Seed under OTHER_TENANT
        principal.setTenancyId(OTHER_TENANT);
        store().store(new MemoryInput("entity-1", DOMAIN, OTHER_TENANT, null, "data-b", Map.of()));
        // Erase as cross-tenant admin
        principal.setTenancyId(TENANT);
        principal.setCrossTenantAdmin(true);
        int count = sqliteStore.eraseEntityAcrossTenants("entity-1", Set.of(TENANT, OTHER_TENANT));
        assertEquals(2, count);
    }

    @Test
    void eraseEntityAcrossTenants_requires_cross_tenant_admin() {
        principal.setCrossTenantAdmin(false);
        assertThrows(SecurityException.class,
            () -> sqliteStore.eraseEntityAcrossTenants("entity-1", Set.of(TENANT)));
    }

    // --- SQLite-specific tests ---

    @Test
    void queryWithRelevanceOrderUsesFts5() {
        store().store(new MemoryInput("entity-1", DOMAIN, TENANT, null,
            "the patient reported ibuprofen side effects including nausea", Map.of()));
        store().store(new MemoryInput("entity-1", DOMAIN, TENANT, null,
            "appointment scheduled for next tuesday", Map.of()));

        var results = store().query(
            MemoryQuery.forEntity("entity-1", DOMAIN, TENANT)
                .withOrder(MemoryOrder.RELEVANCE)
                .withQuestion("ibuprofen side effects"));

        assertFalse(results.isEmpty());
        assertTrue(results.get(0).text().contains("ibuprofen"),
            "Expected ibuprofen memory first; got: " + results.get(0).text());
    }

    @Test
    void queryWithRelevanceOrderNullQuestion() {
        store().store(new MemoryInput("entity-1", DOMAIN, TENANT, null, "alpha", Map.of()));
        store().store(new MemoryInput("entity-1", DOMAIN, TENANT, null, "beta", Map.of()));

        var results = store().query(
            MemoryQuery.forEntity("entity-1", DOMAIN, TENANT)
                .withOrder(MemoryOrder.RELEVANCE)
                .withQuestion(null));

        // null question → chronological fallback regardless of fts.enabled
        assertEquals(2, results.size());
        assertEquals("beta", results.get(0).text());
    }

    @Test
    void storeAllWrapsInSingleTransaction() {
        var inputs = List.of(
            new MemoryInput("entity-1", DOMAIN, TENANT, null, "batch-a", Map.of()),
            new MemoryInput("entity-1", DOMAIN, TENANT, null, "batch-b", Map.of()),
            new MemoryInput("entity-1", DOMAIN, TENANT, null, "batch-c", Map.of())
        );
        var result = store().storeAll(inputs);

        assertTrue(result.allSucceeded());
        assertEquals(3, result.stored().size());
        var stored = store().query(MemoryQuery.forEntity("entity-1", DOMAIN, TENANT));
        assertEquals(3, stored.size());
        assertTrue(stored.stream().anyMatch(m -> "batch-a".equals(m.text())));
        assertTrue(stored.stream().anyMatch(m -> "batch-b".equals(m.text())));
        assertTrue(stored.stream().anyMatch(m -> "batch-c".equals(m.text())));
    }

    @Test
    void ftsOperatorCharactersInQuestionAreStripped() {
        store().store(new MemoryInput("entity-1", DOMAIN, TENANT, null,
            "pre-trial hearing was held yesterday", Map.of()));

        // "pre-trial" with '-' stripped becomes "pre trial" — both words ANDed, matches
        var results = store().query(
            MemoryQuery.forEntity("entity-1", DOMAIN, TENANT)
                .withOrder(MemoryOrder.RELEVANCE)
                .withQuestion("pre-trial"));

        assertEquals(1, results.size());
    }

    public static class FtsDisabledProfile implements io.quarkus.test.junit.QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of("casehub.memory.sqlite.fts.enabled", "false");
        }
    }

    // --- scan() tests ---

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
        var results = sqliteStore.scan(request);

        assertEquals(2, results.size());
        results.forEach(m ->
            assertTrue(m.attributes().containsKey("cbr.caseType") && m.attributes().get("cbr.caseType").equals("aml")));
    }

    @Test
    void scan_respectsLimit() {
        for (int i = 0; i < 5; i++) {
            store().store(new MemoryInput("e1", DOMAIN, TENANT, "case-" + i, "p" + i,
                Map.of("cbr.caseType", "aml")));
        }
        var request = new MemoryScanRequest(TENANT, null, "cbr.caseType", "aml", 2, null);
        var results = sqliteStore.scan(request);
        assertEquals(2, results.size());
    }

    @Test
    void scan_paginatesWithCursor() {
        for (int i = 0; i < 5; i++) {
            store().store(new MemoryInput("e1", DOMAIN, TENANT, "case-" + i, "p" + i,
                Map.of("cbr.caseType", "aml")));
        }
        // First page
        var page1 = sqliteStore.scan(new MemoryScanRequest(TENANT, null, "cbr.caseType", "aml", 3, null));
        assertEquals(3, page1.size());

        // Second page using cursor from last element
        String cursor = page1.getLast().memoryId();
        var page2 = sqliteStore.scan(new MemoryScanRequest(TENANT, null, "cbr.caseType", "aml", 3, cursor));
        assertEquals(2, page2.size());

        // No overlap
        var page1Ids = page1.stream().map(Memory::memoryId).toList();
        var page2Ids = page2.stream().map(Memory::memoryId).toList();
        assertTrue(page1Ids.stream().noneMatch(page2Ids::contains));
    }

    @Test
    void scan_filtersByTenant() {
        store().store(new MemoryInput("e1", DOMAIN, TENANT, "case-1", "p1",
            Map.of("cbr.caseType", "aml")));
        principal.setTenancyId(OTHER_TENANT);
        store().store(new MemoryInput("e1", DOMAIN, OTHER_TENANT, "case-2", "p2",
            Map.of("cbr.caseType", "aml")));

        principal.setTenancyId(TENANT);
        var results = sqliteStore.scan(new MemoryScanRequest(TENANT, null, "cbr.caseType", "aml", 100, null));
        assertEquals(1, results.size());
    }

    @Test
    void scan_filtersByDomain() {
        store().store(new MemoryInput("e1", DOMAIN, TENANT, "case-1", "p1",
            Map.of("cbr.caseType", "aml")));
        store().store(new MemoryInput("e1", new MemoryDomain("other"), TENANT, "case-2", "p2",
            Map.of("cbr.caseType", "aml")));

        var results = sqliteStore.scan(new MemoryScanRequest(TENANT, DOMAIN.name(), "cbr.caseType", "aml", 100, null));
        assertEquals(1, results.size());
    }

    @Test
    void scan_withoutAttributeFilter_returnsAllForTenant() {
        store().store(new MemoryInput("e1", DOMAIN, TENANT, "case-1", "p1", Map.of("a", "1")));
        store().store(new MemoryInput("e2", DOMAIN, TENANT, "case-2", "p2", Map.of("b", "2")));
        principal.setTenancyId(OTHER_TENANT);
        store().store(new MemoryInput("e1", DOMAIN, OTHER_TENANT, "case-3", "p3", Map.of("c", "3")));

        principal.setTenancyId(TENANT);
        var results = sqliteStore.scan(new MemoryScanRequest(TENANT, null, null, null, 100, null));
        assertEquals(2, results.size());
    }

    @Test
    void scan_declaresScanCapability() {
        assertTrue(sqliteStore.capabilities().contains(MemoryCapability.SCAN));
    }
}
