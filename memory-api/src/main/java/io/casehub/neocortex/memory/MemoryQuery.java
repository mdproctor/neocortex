package io.casehub.neocortex.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record MemoryQuery(
    List<String> entityIds,
    MemoryDomain domain,
    String tenantId,
    String caseId,
    String question,
    int limit,
    Instant since,
    MemoryOrder order
) {
    /** Maximum entities per query. Covers realistic case party counts (2–15) with headroom. */
    public static final int MAX_ENTITY_IDS = 25;

    public MemoryQuery {
        Objects.requireNonNull(entityIds, "entityIds required");
        Objects.requireNonNull(domain,    "domain required");
        Objects.requireNonNull(tenantId,  "tenantId required");
        Objects.requireNonNull(order,     "order required");
        if (entityIds.isEmpty())
            throw new IllegalArgumentException("entityIds must not be empty");
        if (entityIds.size() > MAX_ENTITY_IDS)
            throw new IllegalArgumentException("entityIds must not exceed " + MAX_ENTITY_IDS + ", got: " + entityIds.size());
        if (limit < 1)
            throw new IllegalArgumentException("limit must be >= 1, got: " + limit);
        entityIds = List.copyOf(entityIds);
    }

    /**
     * Construct a query for a single entity.
     *
     * <p>Defaults: {@code limit=20}, {@code order=}{@link MemoryOrder#CHRONOLOGICAL}.
     * Use {@code with*} methods to override optional fields.
     */
    public static MemoryQuery forEntity(String entityId, MemoryDomain domain, String tenantId) {
        return new MemoryQuery(List.of(entityId), domain, tenantId, null, null, 20, null, MemoryOrder.CHRONOLOGICAL);
    }

    /**
     * Construct a query for multiple entities (max {@value #MAX_ENTITY_IDS}).
     *
     * <p>Defaults: {@code limit=20}, {@code order=}{@link MemoryOrder#CHRONOLOGICAL}.
     * {@code limit} applies to the combined result set, not per-entity.
     * Use {@code with*} methods to override optional fields.
     */
    public static MemoryQuery forEntities(List<String> entityIds, MemoryDomain domain, String tenantId) {
        return new MemoryQuery(entityIds, domain, tenantId, null, null, 20, null, MemoryOrder.CHRONOLOGICAL);
    }

    public MemoryQuery withCaseId(String caseId) {
        return new MemoryQuery(entityIds, domain, tenantId, caseId, question, limit, since, order);
    }

    public MemoryQuery withQuestion(String question) {
        return new MemoryQuery(entityIds, domain, tenantId, caseId, question, limit, since, order);
    }

    public MemoryQuery withLimit(int limit) {
        return new MemoryQuery(entityIds, domain, tenantId, caseId, question, limit, since, order);
    }

    public MemoryQuery withSince(Instant since) {
        return new MemoryQuery(entityIds, domain, tenantId, caseId, question, limit, since, order);
    }

    public MemoryQuery withOrder(MemoryOrder order) {
        return new MemoryQuery(entityIds, domain, tenantId, caseId, question, limit, since, order);
    }
}
