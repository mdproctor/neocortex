package io.casehub.neocortex.memory;

public enum MemoryCapability {
    // Universal
    CHRONOLOGICAL_ORDER,
    DOMAIN_SCOPED,
    CASE_SCOPED,
    SINCE_FILTER,
    BATCH_STORE,

    // Semantic search tier
    SEMANTIC_SEARCH,     // vector similarity — mem0, graphiti
    FULL_TEXT_SEARCH,    // BM25/FTS keyword — jpa, sqlite

    // Graph tier
    TEMPORAL_GRAPH,      // valid_at/invalid_at on results; client-side temporal filtering
    ENTITY_TYPE_FILTER,  // filter by graph entity type (future — no REST support yet)
    ENTITY_TRAVERSAL,    // graph traversal with configurable depth (future)
    FACT_SEARCH,         // edge / relationship search via POST /search
    NODE_SEARCH,         // entity node summary search (future — no REST endpoint yet)

    // Erasure granularity
    ERASE_BY_ID,         // eraseById() — per-episode deletion
    ERASE_ENTITY,        // eraseEntity() — GDPR full-entity wipe
    ERASE_DOMAIN_CASE,   // erase(EraseRequest) — domain+caseId scoped
    CROSS_TENANT_ERASE,  // eraseEntityAcrossTenants() — GDPR Art.17 across all supplied tenantIds

    // Admin / scan tier
    SCAN,                // paginated attribute-filtered enumeration
}
