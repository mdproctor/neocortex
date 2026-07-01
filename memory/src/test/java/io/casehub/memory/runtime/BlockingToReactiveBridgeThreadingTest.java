package io.casehub.neocortex.memory.runtime;

import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.neocortex.memory.StoreAllResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that BlockingToReactiveBridge dispatches blocking delegate calls
 * to a worker thread, never running them on the subscribing thread.
 * This prevents event-loop blocking when the bridge is called from a reactive pipeline.
 */
class BlockingToReactiveBridgeThreadingTest {

    private static final MemoryDomain DOMAIN = new MemoryDomain("d");
    private static final String       TENANT = "t";
    private static final MemoryInput  INPUT  = new MemoryInput("e", DOMAIN, TENANT, null, "text", Map.of());
    private static final MemoryQuery  QUERY  = MemoryQuery.forEntity("e", DOMAIN, TENANT).withLimit(1);
    private static final EraseRequest ERASE  = new EraseRequest("e", DOMAIN, TENANT, null);

    private BlockingToReactiveBridge bridgeWith(AtomicLong capturedThreadId) {
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
                return 0;
            }
            @Override public void eraseById(String id, String eid, String tid) {
                capturedThreadId.set(Thread.currentThread().getId());
            }
            @Override public int eraseEntity(String eid, String tid) {
                capturedThreadId.set(Thread.currentThread().getId());
                return 0;
            }
            @Override public StoreAllResult storeAll(List<MemoryInput> inputs) {
                capturedThreadId.set(Thread.currentThread().getId());
                return new StoreAllResult(inputs.stream().map(i -> "mem-batch").toList(), List.of());
            }
            @Override public int eraseEntityAcrossTenants(String eid, Set<String> tids) {
                capturedThreadId.set(Thread.currentThread().getId());
                return 0;
            }
        };
        var bridge = new BlockingToReactiveBridge();
        bridge.delegate = spy;
        return bridge;
    }

    @Test
    void store_executes_delegate_on_worker_thread() {
        // Initialize to caller thread ID — if delegate is never called, assertion still fails correctly.
        var capturedId = new AtomicLong(Thread.currentThread().getId());
        bridgeWith(capturedId).store(INPUT).await().indefinitely();
        assertNotEquals(Thread.currentThread().getId(), capturedId.get(),
            "store() must offload delegate to a worker thread, not run on the subscribing thread");
    }

    @Test
    void query_executes_delegate_on_worker_thread() {
        var capturedId = new AtomicLong(Thread.currentThread().getId());
        bridgeWith(capturedId).query(QUERY).await().indefinitely();
        assertNotEquals(Thread.currentThread().getId(), capturedId.get(),
            "query() must offload delegate to a worker thread, not run on the subscribing thread");
    }

    @Test
    void erase_executes_delegate_on_worker_thread() {
        var capturedId = new AtomicLong(Thread.currentThread().getId());
        bridgeWith(capturedId).erase(ERASE).await().indefinitely();
        assertNotEquals(Thread.currentThread().getId(), capturedId.get(),
            "erase() must offload delegate to a worker thread, not run on the subscribing thread");
    }

    @Test
    void eraseById_executes_delegate_on_worker_thread() {
        var capturedId = new AtomicLong(Thread.currentThread().getId());
        bridgeWith(capturedId).eraseById("mem-1", "entity-1", TENANT).await().indefinitely();
        assertNotEquals(Thread.currentThread().getId(), capturedId.get(),
            "eraseById() must offload delegate to a worker thread, not run on the subscribing thread");
    }

    @Test
    void eraseEntity_executes_delegate_on_worker_thread() {
        var capturedId = new AtomicLong(Thread.currentThread().getId());
        bridgeWith(capturedId).eraseEntity("entity-1", TENANT).await().indefinitely();
        assertNotEquals(Thread.currentThread().getId(), capturedId.get(),
            "eraseEntity() must offload delegate to a worker thread, not run on the subscribing thread");
    }

    @Test
    void storeAll_executes_delegate_on_worker_thread() {
        var capturedId = new AtomicLong(Thread.currentThread().getId());
        var inputs = List.of(INPUT, new MemoryInput("e", DOMAIN, TENANT, null, "text2", Map.of()));
        var result = bridgeWith(capturedId).storeAll(inputs).await().indefinitely();
        assertNotEquals(Thread.currentThread().getId(), capturedId.get(),
            "storeAll() must offload delegate to a worker thread, not run on the subscribing thread");
        assertEquals(2, result.stored().size());
    }

    @Test
    void eraseEntityAcrossTenants_executes_delegate_on_worker_thread() {
        var capturedId = new AtomicLong(Thread.currentThread().getId());
        bridgeWith(capturedId).eraseEntityAcrossTenants("entity-1", Set.of(TENANT))
            .await().indefinitely();
        assertNotEquals(Thread.currentThread().getId(), capturedId.get(),
            "eraseEntityAcrossTenants() must offload delegate to a worker thread, not run on the subscribing thread");
    }
}
