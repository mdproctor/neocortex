package io.casehub.neocortex.memory.inmem;

import io.casehub.neocortex.memory.*;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ReactiveInMemoryMemoryStoreTest {

    private static final MemoryDomain DOMAIN = new MemoryDomain("d");
    private static final String TENANT = "t";
    private static final MemoryInput INPUT = new MemoryInput("e", DOMAIN, TENANT, null, "text", Map.of());
    private static final MemoryQuery QUERY = MemoryQuery.forEntity("e", DOMAIN, TENANT).withLimit(10);
    private static final EraseRequest ERASE = new EraseRequest("e", DOMAIN, TENANT, null);

    private ReactiveInMemoryMemoryStore storeWith(AtomicLong capturedThreadId) {
        CaseMemoryStore spy = new CaseMemoryStore() {
            @Override public String store(MemoryInput i) {
                capturedThreadId.set(Thread.currentThread().getId());
                return "mem-1";
            }
            @Override public List<Memory> query(MemoryQuery q) {
                capturedThreadId.set(Thread.currentThread().getId());
                return List.of();
            }
            @Override public int erase(EraseRequest r) {
                capturedThreadId.set(Thread.currentThread().getId());
                return 1;
            }
            @Override public StoreAllResult storeAll(List<MemoryInput> inputs) {
                capturedThreadId.set(Thread.currentThread().getId());
                return new StoreAllResult(inputs.stream().map(i -> "id").toList(), List.of());
            }
            @Override public Set<MemoryCapability> capabilities() {
                return Set.of(MemoryCapability.SCAN, MemoryCapability.DISCOVER_TENANTS);
            }
        };
        return new ReactiveInMemoryMemoryStore(spy);
    }

    @Test
    void store_executesOnCallerThread() {
        var capturedId = new AtomicLong(-1);
        String result = storeWith(capturedId).store(INPUT).await().indefinitely();
        assertEquals("mem-1", result);
        assertEquals(Thread.currentThread().getId(), capturedId.get(),
            "In-memory reactive wrapper must NOT dispatch to worker pool");
    }

    @Test
    void query_executesOnCallerThread() {
        var capturedId = new AtomicLong(-1);
        List<Memory> result = storeWith(capturedId).query(QUERY).await().indefinitely();
        assertNotNull(result);
        assertEquals(Thread.currentThread().getId(), capturedId.get());
    }

    @Test
    void erase_executesOnCallerThread() {
        var capturedId = new AtomicLong(-1);
        int count = storeWith(capturedId).erase(ERASE).await().indefinitely();
        assertEquals(1, count);
        assertEquals(Thread.currentThread().getId(), capturedId.get());
    }

    @Test
    void storeAll_executesOnCallerThread() {
        var capturedId = new AtomicLong(-1);
        var result = storeWith(capturedId).storeAll(List.of(INPUT)).await().indefinitely();
        assertEquals(1, result.stored().size());
        assertEquals(Thread.currentThread().getId(), capturedId.get());
    }

    @Test
    void capabilities_delegatesDirectly() {
        var capturedId = new AtomicLong(-1);
        var store = storeWith(capturedId);
        assertEquals(Set.of(MemoryCapability.SCAN, MemoryCapability.DISCOVER_TENANTS),
            store.capabilities());
    }
}
