package io.casehub.neocortex.memory.inmem;

import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryCapability;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.neocortex.memory.MemoryScanRequest;
import io.casehub.neocortex.memory.ReactiveCaseMemoryStore;
import io.casehub.neocortex.memory.StoreAllResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Alternative;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;

@Alternative
@Priority(10)
@ApplicationScoped
public class ReactiveInMemoryMemoryStore implements ReactiveCaseMemoryStore {

    private final CaseMemoryStore delegate;

    @Inject
    public ReactiveInMemoryMemoryStore(CaseMemoryStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public Uni<String> store(MemoryInput input) {
        return Uni.createFrom().item(() -> delegate.store(input));
    }

    @Override
    public Uni<List<Memory>> query(MemoryQuery query) {
        return Uni.createFrom().item(() -> delegate.query(query));
    }

    @Override
    public Uni<Integer> erase(EraseRequest request) {
        return Uni.createFrom().item(() -> delegate.erase(request));
    }

    @Override
    public Uni<StoreAllResult> storeAll(List<MemoryInput> inputs) {
        return Uni.createFrom().item(() -> delegate.storeAll(inputs));
    }

    @Override
    public Uni<Integer> eraseEntity(String entityId, String tenantId) {
        return Uni.createFrom().item(() -> delegate.eraseEntity(entityId, tenantId));
    }

    @Override
    public Uni<Void> eraseById(String memoryId, String entityId, String tenantId) {
        return Uni.createFrom().voidItem().invoke(() -> delegate.eraseById(memoryId, entityId, tenantId));
    }

    @Override
    public Uni<Integer> eraseEntityAcrossTenants(String entityId, Set<String> tenantIds) {
        return Uni.createFrom().item(() -> delegate.eraseEntityAcrossTenants(entityId, tenantIds));
    }

    @Override
    public Uni<List<Memory>> scan(MemoryScanRequest request) {
        return Uni.createFrom().item(() -> delegate.scan(request));
    }

    @Override
    public Uni<Set<String>> discoverTenants(String attributeKey, String attributeValue) {
        return Uni.createFrom().item(() -> delegate.discoverTenants(attributeKey, attributeValue));
    }

    @Override
    public Set<MemoryCapability> capabilities() {
        return delegate.capabilities();
    }

    @Override
    public void requireCapability(MemoryCapability capability) {
        delegate.requireCapability(capability);
    }
}
