package io.casehub.neocortex.knowledge.testing;

import io.casehub.neocortex.knowledge.CacheStore;
import io.casehub.neocortex.knowledge.CachedEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public abstract class CacheStoreContractTest {

    protected CacheStore store;

    protected abstract CacheStore createStore();

    @BeforeEach
    void setUp() {
        store = createStore();
    }

    protected CachedEntity testEntity(String id, String name) {
        return new CachedEntity(id, name, null, null,
            "src", id, Map.of(), Instant.now(), null,
            Instant.now().plusSeconds(3600), Set.of(), false, "test");
    }

    @Test
    void setAndGet() {
        var entity = testEntity("e1", "Test Entity");
        store.set(entity, "t1");
        var retrieved = store.get("e1", "t1");
        assertNotNull(retrieved);
        assertEquals("Test Entity", retrieved.name());
    }

    @Test
    void getReturnsNullForMissing() {
        assertNull(store.get("nonexistent", "t1"));
    }

    @Test
    void remove() {
        store.set(testEntity("e1", "Test"), "t1");
        store.remove("e1", "t1");
        assertNull(store.get("e1", "t1"));
    }

    @Test
    void listAll() {
        store.set(testEntity("e1", "A"), "t1");
        store.set(testEntity("e2", "B"), "t1");
        store.set(testEntity("e3", "C"), "t2");
        assertEquals(2, store.listAll("t1").size());
    }

    @Test
    void findExpired() {
        var entity = testEntity("e1", "Test");
        store.set(entity, "t1");
        Instant past = Instant.now().minusSeconds(10);
        store.expire("e1", past, "t1");
        var expired = store.findExpired("t1", Instant.now());
        assertTrue(expired.contains("e1"));
    }

    @Test
    void discoverTenants() {
        store.set(testEntity("e1", "A"), "t1");
        store.set(testEntity("e2", "B"), "t2");
        var tenants = store.discoverTenants();
        assertTrue(tenants.contains("t1"));
        assertTrue(tenants.contains("t2"));
    }

    @Test
    void tenantIsolation() {
        store.set(testEntity("e1", "A"), "t1");
        assertNull(store.get("e1", "t2"));
    }
}
