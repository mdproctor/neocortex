package io.casehub.neocortex.memory.runtime;

import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.ReactiveCaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.neocortex.memory.StoreAllResult;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;

@DefaultBean
@ApplicationScoped
public class BlockingToReactiveBridge implements ReactiveCaseMemoryStore {

    @Inject CaseMemoryStore delegate;

    @Override
    public Uni<String> store(MemoryInput input) {
        return Uni.createFrom().item(() -> delegate.store(input))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<List<Memory>> query(MemoryQuery query) {
        return Uni.createFrom().item(() -> delegate.query(query))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<StoreAllResult> storeAll(List<MemoryInput> inputs) {
        return Uni.createFrom().item(() -> delegate.storeAll(inputs))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Integer> erase(EraseRequest request) {
        return Uni.createFrom().item(() -> delegate.erase(request))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> eraseById(String memoryId, String entityId, String tenantId) {
        return Uni.createFrom().<Void>item(() -> { delegate.eraseById(memoryId, entityId, tenantId); return null; })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Integer> eraseEntity(String entityId, String tenantId) {
        return Uni.createFrom().item(() -> delegate.eraseEntity(entityId, tenantId))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Integer> eraseEntityAcrossTenants(String entityId, Set<String> tenantIds) {
        return Uni.createFrom().item(() -> delegate.eraseEntityAcrossTenants(entityId, tenantIds))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
