package io.casehub.neocortex.memory.runtime;

import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.GraphCaseMemoryStore;
import io.casehub.neocortex.memory.GraphMemoryQuery;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.neocortex.memory.ReactiveCaseMemoryStore;
import io.casehub.neocortex.memory.StoreAllResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class NoOpCaseMemoryStoreTest {

    @Inject CaseMemoryStore store;
    @Inject GraphCaseMemoryStore graphStore;
    @Inject ReactiveCaseMemoryStore reactiveStore;

    static final MemoryDomain DOMAIN  = new MemoryDomain("test");
    static final MemoryInput  SAMPLE  = new MemoryInput(
        "entity-1", DOMAIN, "tenant-1", null, "sample", Map.of());
    static final MemoryInput  SAMPLE_WITH_CASE = new MemoryInput(
        "entity-1", DOMAIN, "tenant-1", "case-99", "sample", Map.of());
    static final MemoryQuery  QUERY   = MemoryQuery.forEntity("entity-1", DOMAIN, "tenant-1").withLimit(10);
    static final EraseRequest ERASE_SCOPED = new EraseRequest(
        "entity-1", DOMAIN, "tenant-1", null);

    // --- blocking no-op ---
    @Test void store_returns_empty_id()      { assertTrue(store.store(SAMPLE).isEmpty()); }
    @Test void query_returns_empty()         { assertTrue(store.query(QUERY).isEmpty()); }
    @Test void erase_scoped_does_not_throw() { assertDoesNotThrow(() -> store.erase(ERASE_SCOPED)); }
    @Test void eraseById_does_not_throw()    { assertDoesNotThrow(() -> store.eraseById("mem-1", "entity-1", "tenant-1")); }
    @Test void eraseEntity_returns_zero()    { assertEquals(0, store.eraseEntity("entity-1", "tenant-1")); }
    @Test void storeAll_returns_empty_ids() {
        var result = store.storeAll(List.of(SAMPLE, SAMPLE_WITH_CASE));
        assertEquals(List.of("", ""), result.stored());
        assertTrue(result.allSucceeded());
    }

    // --- GraphCaseMemoryStore injection ---
    @Test void graphStore_injection_resolves_to_noop() {
        assertNotNull(graphStore, "GraphCaseMemoryStore must resolve to NoOpCaseMemoryStore when no graph adapter is deployed");
    }
    @Test void graphQuery_returns_empty() {
        final var q = GraphMemoryQuery.forEntity("entity-1", DOMAIN, "tenant-1", "what happened?");
        assertTrue(graphStore.graphQuery(q).isEmpty());
    }
    @Test void capabilities_returns_empty_set() {
        assertTrue(store.capabilities().isEmpty());
        assertTrue(graphStore.capabilities().isEmpty());
    }

    // --- reactive bridge (delegates to blocking no-op) ---
    @Test void bridge_store_returns_empty_id() {
        assertTrue(reactiveStore.store(SAMPLE).await().indefinitely().isEmpty());
    }
    @Test void bridge_query_returns_empty() {
        assertTrue(reactiveStore.query(QUERY).await().indefinitely().isEmpty());
    }
    @Test void bridge_erase_does_not_throw() {
        assertDoesNotThrow(() -> reactiveStore.erase(ERASE_SCOPED).await().indefinitely());
    }
    @Test void bridge_eraseById_does_not_throw() {
        assertDoesNotThrow(() -> reactiveStore.eraseById("mem-1", "entity-1", "tenant-1").await().indefinitely());
    }
    @Test void bridge_eraseEntity_returns_zero() {
        assertEquals(0, reactiveStore.eraseEntity("entity-1", "tenant-1").await().indefinitely());
    }

    @Test void eraseEntityAcrossTenants_returns_zero() {
        assertEquals(0, store.eraseEntityAcrossTenants("entity-1", Set.of("tenant-1")));
    }
    @Test void bridge_eraseEntityAcrossTenants_returns_zero() {
        assertEquals(0, reactiveStore.eraseEntityAcrossTenants("entity-1", Set.of("tenant-1"))
            .await().indefinitely());
    }
}
