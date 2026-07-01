package io.casehub.neocortex.memory.runtime;

import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.GraphCaseMemoryStore;
import io.casehub.neocortex.memory.GraphMemoryQuery;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryCapability;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.neocortex.memory.StoreAllResult;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * No-op {@link CaseMemoryStore} and {@link GraphCaseMemoryStore} — satisfies both injection
 * types when no real adapter is deployed. Erasure methods are true no-ops:
 * nothing stored → erasure is trivially satisfied. This adapter is the only one where
 * {@link #capabilities()} returns {@link Set#of()} yet erase methods do not throw.
 */
@DefaultBean
@ApplicationScoped
public class NoOpCaseMemoryStore implements GraphCaseMemoryStore {

    @Override public String store(final MemoryInput input) { return ""; }
    @Override public List<Memory> query(final MemoryQuery query) { return List.of(); }
    @Override public int erase(final EraseRequest request) { return 0; }
    @Override public void eraseById(final String memoryId, final String entityId, final String tenantId) {}
    @Override public int eraseEntity(final String entityId, final String tenantId) { return 0; }
    @Override public List<Memory> graphQuery(final GraphMemoryQuery query) { return List.of(); }

    @Override
    public Set<MemoryCapability> capabilities() {
        return Set.of();
    }

    @Override
    public StoreAllResult storeAll(final List<MemoryInput> inputs) {
        return new StoreAllResult(Collections.nCopies(inputs.size(), ""), List.of());
    }

    // No assertCrossTenantAdmin call — NoOpCaseMemoryStore has no CurrentPrincipal injection
    // and guards no real data, matching the same omission in eraseEntity().
    // capabilities() stays Set.of() — NoOp invariant is preserved.
    @Override
    public int eraseEntityAcrossTenants(final String entityId, final Set<String> tenantIds) { return 0; }
}
