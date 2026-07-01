package io.casehub.neocortex.memory.inmem;

import io.casehub.neocortex.memory.*;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.micrometer.core.annotation.Timed;
import io.quarkus.arc.Arc;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Alternative
@Priority(10)
@ApplicationScoped
public class InMemoryMemoryStore implements CaseMemoryStore {

    @Override
    public java.util.Set<MemoryCapability> capabilities() {
        return java.util.Set.of(
            MemoryCapability.CHRONOLOGICAL_ORDER,
            MemoryCapability.DOMAIN_SCOPED,
            MemoryCapability.CASE_SCOPED,
            MemoryCapability.SINCE_FILTER,
            MemoryCapability.BATCH_STORE,
            MemoryCapability.ERASE_BY_ID,
            MemoryCapability.ERASE_ENTITY,
            MemoryCapability.ERASE_DOMAIN_CASE,
            MemoryCapability.CROSS_TENANT_ERASE
        );
    }


    private final ConcurrentHashMap<BucketKey, CopyOnWriteArrayList<Memory>> store
        = new ConcurrentHashMap<>();
    private final CurrentPrincipal principal;

    @Inject
    public InMemoryMemoryStore(CurrentPrincipal principal) {
        this.principal = principal;
    }

    private boolean requestContextActive() {
        var c = Arc.container();
        return c == null || c.requestContext().isActive();
    }

    @Timed(value = "casehub.memory.inmem", histogram = true, extraTags = {"operation", "store"})
    @Override
    public String store(MemoryInput input) {
        MemoryPermissions.assertTenant(input.tenantId(), principal, requestContextActive());
        String memoryId = UUID.randomUUID().toString();
        Memory memory = new Memory(
            memoryId, input.entityId(), input.domain(), input.tenantId(),
            input.caseId(), input.text(), input.attributes(), Instant.now()
        );
        store.computeIfAbsent(
            new BucketKey(input.tenantId(), input.entityId(), input.domain()),
            k -> new CopyOnWriteArrayList<>()
        ).add(memory);
        return memoryId;
    }

    @Timed(value = "casehub.memory.inmem", histogram = true, extraTags = {"operation", "storeAll"})
    @Override
    public StoreAllResult storeAll(List<MemoryInput> inputs) {
        if (inputs.isEmpty()) return StoreAllResult.empty();
        inputs.forEach(i -> MemoryPermissions.assertTenant(i.tenantId(), principal, requestContextActive()));
        return new StoreAllResult(List.copyOf(inputs.stream().map(this::store).toList()), List.of());
    }

    @Timed(value = "casehub.memory.inmem", histogram = true, extraTags = {"operation", "query"})
    @Override
    public List<Memory> query(MemoryQuery query) {
        MemoryPermissions.assertTenant(query.tenantId(), principal, requestContextActive());
        // MemoryOrder is ignored — in-mem always sorts chronologically (createdAt DESC).
        return query.entityIds().stream()
            .flatMap(entityId -> store.getOrDefault(
                    new BucketKey(query.tenantId(), entityId, query.domain()),
                    new CopyOnWriteArrayList<>()
                ).stream()
            )
            .filter(m -> query.caseId() == null || query.caseId().equals(m.caseId()))
            .filter(m -> query.since() == null || !m.createdAt().isBefore(query.since()))
            .filter(m -> query.question() == null
                || m.text().toLowerCase().contains(query.question().toLowerCase()))
            .sorted(Comparator.comparing(Memory::createdAt).reversed())
            .limit(query.limit())
            .toList();
    }

    @Timed(value = "casehub.memory.inmem", histogram = true, extraTags = {"operation", "erase"})
    @Override
    public int erase(EraseRequest request) {
        MemoryPermissions.assertTenant(request.tenantId(), principal, requestContextActive());
        final var key = new BucketKey(request.tenantId(), request.entityId(), request.domain());
        final var removed = new AtomicInteger();
        store.computeIfPresent(key, (k, memories) -> {
            final var remaining = new CopyOnWriteArrayList<>(memories.stream()
                .filter(m -> request.caseId() != null && !request.caseId().equals(m.caseId()))
                .toList());
            removed.set(memories.size() - remaining.size());
            return remaining;
        });
        return removed.get();
    }

    @Timed(value = "casehub.memory.inmem", histogram = true, extraTags = {"operation", "eraseById"})
    @Override
    public void eraseById(String memoryId, String entityId, String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        // Scope to entity buckets — mismatch means no-op (no information leak).
        store.entrySet().stream()
            .filter(e -> e.getKey().tenantId().equals(tenantId)
                      && e.getKey().entityId().equals(entityId))
            .forEach(e -> e.getValue().removeIf(m -> m.memoryId().equals(memoryId)));
    }

    @Timed(value = "casehub.memory.inmem", histogram = true, extraTags = {"operation", "eraseEntity"})
    @Override
    public int eraseEntity(String entityId, String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        final var count = new AtomicInteger();
        store.entrySet().removeIf(e -> {
            if (e.getKey().tenantId().equals(tenantId) && e.getKey().entityId().equals(entityId)) {
                count.addAndGet(e.getValue().size());
                return true;
            }
            return false;
        });
        return count.get();
    }

    @Timed(value = "casehub.memory.inmem", histogram = true, extraTags = {"operation", "eraseEntityAcrossTenants"})
    @Override
    public int eraseEntityAcrossTenants(String entityId, Set<String> tenantIds) {
        MemoryPermissions.assertCrossTenantAdmin(principal);
        var count = new AtomicInteger();
        store.entrySet().removeIf(e -> {
            if (tenantIds.contains(e.getKey().tenantId()) && e.getKey().entityId().equals(entityId)) {
                count.addAndGet(e.getValue().size());
                return true;
            }
            return false;
        });
        return count.get();
    }
}
