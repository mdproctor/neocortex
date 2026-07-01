package io.casehub.neocortex.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Graph-native query parameters for {@link GraphCaseMemoryStore#graphQuery(GraphMemoryQuery)}.
 *
 * <p>{@code question} is required — {@code graphQuery()} is purely semantic; the underlying
 * REST search endpoint requires a query string. For non-semantic chronological retrieval use
 * {@link CaseMemoryStore#query(MemoryQuery)} with {@link MemoryOrder#CHRONOLOGICAL}.
 *
 * <p>{@code domain} is required — mirrors {@link MemoryQuery} and ensures {@link Memory#domain()}
 * is always constructible (non-blank {@link MemoryDomain}).
 */
public record GraphMemoryQuery(
        String tenantId,
        List<String> entityIds,
        MemoryDomain domain,
        String question,
        int limit,
        Instant since,
        Instant validAt,
        Set<String> entityTypes,
        MemoryResultType resultType
) {
    public static final int MAX_ENTITY_IDS = 25;

    public GraphMemoryQuery {
        Objects.requireNonNull(tenantId,  "tenantId required");
        Objects.requireNonNull(entityIds, "entityIds required");
        Objects.requireNonNull(domain,    "domain required");
        Objects.requireNonNull(question,  "question required — for chronological retrieval use query(MemoryQuery)");
        if (question.isBlank()) throw new IllegalArgumentException("question must not be blank");
        if (entityIds.isEmpty()) throw new IllegalArgumentException("entityIds must not be empty");
        if (entityIds.size() > MAX_ENTITY_IDS)
            throw new IllegalArgumentException("entityIds must not exceed " + MAX_ENTITY_IDS);
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1, got: " + limit);
        entityIds   = List.copyOf(entityIds);
        entityTypes = entityTypes == null ? null : Set.copyOf(entityTypes);
        resultType  = resultType  == null ? MemoryResultType.DEFAULT : resultType;
    }

    public static GraphMemoryQuery forEntity(
            final String entityId,
            final MemoryDomain domain,
            final String tenantId,
            final String question) {
        return new GraphMemoryQuery(tenantId, List.of(entityId), domain, question,
                10, null, null, null, MemoryResultType.DEFAULT);
    }

    public GraphMemoryQuery withLimit(final int limit) {
        return new GraphMemoryQuery(tenantId, entityIds, domain, question,
                limit, since, validAt, entityTypes, resultType);
    }

    public GraphMemoryQuery withSince(final Instant since) {
        return new GraphMemoryQuery(tenantId, entityIds, domain, question,
                limit, since, validAt, entityTypes, resultType);
    }

    public GraphMemoryQuery withValidAt(final Instant validAt) {
        return new GraphMemoryQuery(tenantId, entityIds, domain, question,
                limit, since, validAt, entityTypes, resultType);
    }

    public GraphMemoryQuery withEntityTypes(final Set<String> entityTypes) {
        return new GraphMemoryQuery(tenantId, entityIds, domain, question,
                limit, since, validAt, entityTypes, resultType);
    }

    public GraphMemoryQuery withResultType(final MemoryResultType resultType) {
        return new GraphMemoryQuery(tenantId, entityIds, domain, question,
                limit, since, validAt, entityTypes, resultType);
    }
}
