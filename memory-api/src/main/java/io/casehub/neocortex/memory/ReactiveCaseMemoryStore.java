package io.casehub.neocortex.memory;

import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public interface ReactiveCaseMemoryStore {

    Uni<String> store(MemoryInput input);

    Uni<List<Memory>> query(MemoryQuery query);

    /**
     * Reactive mirror of {@link CaseMemoryStore#erase}.
     * Returns count of records erased (see blocking SPI for semantics).
     */
    Uni<Integer> erase(EraseRequest request);

    /**
     * Reactive mirror of {@link CaseMemoryStore#storeAll}.
     * Default delegates to store() in parallel via Uni.join, collecting results into a
     * {@link StoreAllResult}. Backend failures are collected; SecurityException propagates.
     * Adapters may override.
     */
    default Uni<StoreAllResult> storeAll(List<MemoryInput> inputs) {
        if (inputs.isEmpty()) return Uni.createFrom().item(StoreAllResult.empty());
        return Uni.join().all(inputs.stream().map(this::store).collect(Collectors.toList()))
            .andFailFast()
            .map(ids -> new StoreAllResult(ids, java.util.List.of()));
    }

    /**
     * Reactive mirror of {@link CaseMemoryStore#eraseEntity}.
     * Default fails with MemoryCapabilityException — consistent with blocking SPI contract.
     */
    default Uni<Integer> eraseEntity(String entityId, String tenantId) {
        return Uni.createFrom().failure(
            new MemoryCapabilityException(MemoryCapability.ERASE_ENTITY, getClass()));
    }

    /**
     * Reactive mirror of {@link CaseMemoryStore#eraseById}.
     * Default fails with MemoryCapabilityException — consistent with blocking SPI contract.
     */
    default Uni<Void> eraseById(String memoryId, String entityId, String tenantId) {
        return Uni.createFrom().failure(
            new MemoryCapabilityException(MemoryCapability.ERASE_BY_ID, getClass()));
    }

    /**
     * Reactive mirror of {@link CaseMemoryStore#eraseEntityAcrossTenants}.
     * Default fails with MemoryCapabilityException — consistent with blocking SPI contract.
     */
    default Uni<Integer> eraseEntityAcrossTenants(String entityId, Set<String> tenantIds) {
        return Uni.createFrom().failure(
            new MemoryCapabilityException(MemoryCapability.CROSS_TENANT_ERASE, getClass()));
    }
}
