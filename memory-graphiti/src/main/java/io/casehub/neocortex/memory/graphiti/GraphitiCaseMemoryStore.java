package io.casehub.neocortex.memory.graphiti;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.GraphCaseMemoryStore;
import io.casehub.neocortex.memory.GraphMemoryQuery;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryCapability;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.neocortex.memory.StoreAllResult;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;

@Alternative
@Priority(2)
@ApplicationScoped
public class GraphitiCaseMemoryStore implements GraphCaseMemoryStore {

    @Inject
    ReactiveGraphitiCaseMemoryStore delegate;

    @Override
    public Set<MemoryCapability> capabilities() {
        return delegate.capabilities();
    }

    @Override
    public void requireCapability(MemoryCapability capability) {
        delegate.requireCapability(capability);
    }

    @Override
    public String store(MemoryInput input) {
        return delegate.store(input).await().indefinitely();
    }

    @Override
    public StoreAllResult storeAll(List<MemoryInput> inputs) {
        return delegate.storeAll(inputs).await().indefinitely();
    }

    @Override
    public List<Memory> query(MemoryQuery query) {
        return delegate.query(query).await().indefinitely();
    }

    @Override
    public List<Memory> graphQuery(GraphMemoryQuery query) {
        return delegate.graphQuery(query).await().indefinitely();
    }

    @Override
    public int erase(EraseRequest request) {
        return delegate.erase(request).await().indefinitely();
    }

    @Override
    public void eraseById(String memoryId, String entityId, String tenantId) {
        delegate.eraseById(memoryId, entityId, tenantId).await().indefinitely();
    }

    @Override
    public int eraseEntity(String entityId, String tenantId) {
        return delegate.eraseEntity(entityId, tenantId).await().indefinitely();
    }

    @Override
    public int eraseEntityAcrossTenants(String entityId, Set<String> tenantIds) {
        return delegate.eraseEntityAcrossTenants(entityId, tenantIds).await().indefinitely();
    }
}
