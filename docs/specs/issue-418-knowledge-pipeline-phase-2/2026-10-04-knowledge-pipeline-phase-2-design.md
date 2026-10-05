# Knowledge Pipeline — Phase 2 Follow-up Design

**Epic:** casehubio/neocortex#418
**Scope:** #419, #420, #421, #422, #423, #426, #427
**Deferred:** #414 (LLM extraction), #424 (LLM query normalization), #425 (Tile38 geofencing)
**Date:** 2026-10-04

## 1. Overview

Phase 1 (#413) delivered `knowledge-pipeline-api` and `knowledge-pipeline` with a working orchestrator, spatial cache (SQLite + in-memory), entity resolution, promotion to MindMap, and research sessions. All classes use plain constructor injection — no CDI.

This spec covers seven follow-up issues: completing implementation stubs, adding CDI wiring, and improving code quality. The three deferred capabilities (#414, #424, #425) each require their own design phase due to external dependencies (AgentProvider, geocoding, Tile38).

## 2. Implementation Order

| # | Issue | Scale | What |
|---|-------|-------|------|
| 1 | #426 | S | Extract `SubgraphUtils.ensureSubgraph()` to mindmap-intelligence |
| 2 | #427 | S | Contract test abstract bases for SPIs |
| 3 | #421 | XS | Max entity age + session auto-complete in eviction |
| 4 | #422 | S | Resume TTL extension in ResearchOrchestrator |
| 5 | #419 | S | Wire SubsumptionRule into orchestrator search flow |
| 6 | #420 | M | Implement refreshStale — staleness-triggered re-fetch |
| 7 | #423 | M | CDI wiring — DefaultBeans, @ApplicationScoped |

Rationale: refactor and tests first (safe, no functional change), then XS/S gaps in ascending complexity, then the two schema-changing issues, then CDI last since it annotates the final class shapes.

## 3. #426 — Extract ensureSubgraph utility

### Change

Extract the find-or-create-subgraph pattern into a static utility in `mindmap-intelligence`:

```java
// mindmap-intelligence/src/main/java/.../intelligence/SubgraphUtils.java
public final class SubgraphUtils {
    private SubgraphUtils() {}

    public static String ensureSubgraph(MindMapStore store, String type, String tenantId) {
        return store.listSubgraphs(tenantId).stream()
            .filter(s -> type.equals(s.type()))
            .map(MindMapSubgraph::id)
            .findFirst()
            .orElseGet(() -> store.createSubgraph(
                new SubgraphInput(type, type, null), tenantId));
    }
}
```

### Call sites to update

| Class | Module | Calls |
|-------|--------|-------|
| `CheckInService` | mindmap-intelligence | 3 (PLACE, ACTIVITY, PERSON) |
| `EntityPromoter` | knowledge-pipeline | 1 (PLACE) |
| `MindMapExtractor` | mindmap-intelligence | copy to verify |
| `ExperienceConsolidationPhase` | mindmap-intelligence | copy to verify |
| `ConversationBridge` | mindmap-intelligence | copy to verify |

Each private `ensureSubgraph` method is deleted. No behavioral change — pure refactor.

### Dependency

`knowledge-pipeline` already depends on `mindmap-intelligence` (via `EntityPromoter` → `MindMapStore`). If it does not, add the dependency.

## 4. #427 — Contract test abstract bases

### Abstract bases

All placed in `knowledge-pipeline/src/test/java/.../knowledge/testing/`:

**`SpatialCacheStoreContractTest`** — abstract base for:
- set/get round-trip
- nearby query (Haversine radius filter)
- within bounding box
- remove
- expire / findExpired
- discoverTenants isolation

Provides `abstract SpatialCacheStore createStore()`. Existing `InMemorySpatialCacheStoreTest` and `SqliteSpatialCacheStoreTest` become thin subclasses.

**`EntityMatcherContractTest`** — abstract base for:
- exact name match → EXACT tier
- fuzzy name match → FUZZY tier
- phone normalization match → SIGNAL tier
- no match → empty result

Provides `abstract EntityMatcher createMatcher()`. `PlaceMatcherTest` becomes a subclass.

**`ResearchSessionStoreContractTest`** — abstract base for:
- insert / get round-trip
- state transitions (ACTIVE → PAUSED → ACTIVE → COMPLETED)
- listByState filtering
- tenant isolation

Provides `abstract ResearchSessionStore createStore()`. `ResearchSessionStoreTest` becomes a subclass.

## 5. #421 — Max entity age + session auto-complete

### Changes to `CacheEvictionScheduler`

Two new constructor parameters: `Duration maxEntityAge`, `Duration maxSessionDuration`.

`evictForTenant(tenantId, now)` gains two phases executed in order:

**Phase 1 — Session auto-complete:** Query `sessionStore.listByState(tenantId, ACTIVE)` and `listByState(tenantId, PAUSED)`. For each session where `createdAt + maxSessionDuration < now`, call `sessionStore.updateState(sessionId, COMPLETED, now)`. Log count.

**Phase 2 — Entity eviction (existing + age cap):** After the existing expired-entity loop, add: for entities where `fetchedAt + maxEntityAge < now`, evict regardless of session protection. These are the hard-cap evictions — session protection does not override the max age.

Order matters: session auto-complete runs first so that auto-completed sessions no longer protect their entities in the same pass.

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.knowledge.cache.max-entity-age` | `P90D` | Hard cap — entities older than this are evicted unconditionally |
| `casehub.knowledge.research.max-session-duration` | `P180D` | Sessions older than this auto-complete |

### SpatialCacheStore

Client-side age check on the already-loaded expired set plus a second scan. The `findExpired` method returns entities past `expiresAt`. For the age cap, either:
- Add `findOlderThan(tenantId, cutoff)` to `SpatialCacheStore`, or
- Use `listAll(tenantId)` (added for #420) and filter client-side

This issue adds `listAll(tenantId)` to `SpatialCacheStore` (also reused by #420). Filter client-side since the entity count per tenant is small.

## 6. #422 — Resume TTL extension

### Changes to `ResearchOrchestrator`

New constructor dependencies: `SpatialCacheStore`, `EntityMetadataStore`, `CacheDecayPolicy`.

`resume(sessionId)` becomes:

```
1. sessionStore.updateState(sessionId, ACTIVE, now)
2. session = sessionStore.get(sessionId)
3. entityIds = metadataStore.entitiesForSession(sessionId)  // new method
4. for each entityId:
     entity = cacheStore.get(entityId, session.tenantId)
     if entity != null:
       newExpiry = now + decayPolicy.ttlFor(entity)
       cacheStore.updateExpiry(entityId, session.tenantId, newExpiry)
```

### New methods

**`EntityMetadataStore.entitiesForSession(String sessionId)`** — inverse of `sessionsFor(entityId)`. Returns `Set<String>` of entity IDs. SQL: `SELECT entity_id FROM entity_sessions WHERE session_id = ?`.

**`SpatialCacheStore.updateExpiry(String entityId, String tenantId, Instant newExpiresAt)`** — or add to the SPI interface if it doesn't exist. SQL: `UPDATE spatial_cache SET expires_at = ? WHERE id = ? AND tenant_id = ?`. Also add to `InMemorySpatialCacheStore`.

## 7. #419 — Wire SubsumptionRule into orchestrator search flow

### QueryCacheStore schema migration

Add columns to `query_cache` table:

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `query_type` | TEXT | NOT NULL | TEXT, NEARBY, or CATEGORY |
| `lat` | REAL | YES | Center latitude (NEARBY, CATEGORY) |
| `lng` | REAL | YES | Center longitude (NEARBY, CATEGORY) |
| `radius_meters` | INTEGER | YES | Search radius (NEARBY, CATEGORY) |
| `category` | TEXT | YES | Category string (CATEGORY) |

### QueryCacheStore API changes

**`record()`** gains query parameters — extracted from the `NormalizedQuery` passed by the orchestrator.

**New `listForTenant(String tenantId)`** — returns `List<QueryCacheEntry>` of all non-expired entries for a tenant. Each entry includes the stored query parameters and entity IDs. `QueryCacheEntry` gains a `toNormalizedQuery()` method that reconstructs the `KnowledgeQuery` sealed variant and wraps it in a `NormalizedQuery`.

### Orchestrator search flow

Updated flow in `KnowledgePipelineOrchestrator.search()`:

```
1. Generate NormalizedQuery from KnowledgeQuery (existing)
2. Exact cache key lookup via queryCache.lookup() (existing — O(1))
3. If hit: return cached entities (existing)
4. SUBSUMPTION SCAN (new):
   a. cachedQueries = queryCache.listForTenant(tenantId)
   b. for each cachedQuery:
        broaderNormalized = cachedQuery.toNormalizedQuery()
        broaderEntities = load entities by cachedQuery.entityIds()
        subsumptionResult = subsumptionRule.subsume(normalized, broaderEntities, broaderNormalized)
        if subsumptionResult.isPresent(): return subsumptionResult.get()
5. If no subsumption hit: fetch from providers (existing path)
```

### Constructor change

`KnowledgePipelineOrchestrator` gains `SubsumptionRule` parameter. `SpatialSubsumptionRule` is the default.

### Performance

`listForTenant` scans all cached queries per tenant. For typical usage (personal spatial queries), this is tens to low hundreds of entries. If it becomes a bottleneck, a spatial index on the query table is the upgrade path.

## 8. #420 — Implement refreshStale

### CachedEntity change

Add `detailFetchedAt` field (nullable `Instant`). Null means details never fetched. All existing constructors gain the parameter (existing callers pass `null` or compute from context).

Schema migration adds `detail_fetched_at` column to `spatial_cache` table (nullable TEXT, ISO-8601).

### CacheDecayPolicy additions

**`StaleFieldGroup` enum:**
- `BASIC` — coordinates, name, category. Uses `coordinatesTtl` (30d default).
- `DETAIL` — rating, hours, phone, website, reviews. Uses the minimum of `ratingTtl`, `contactTtl`, `hoursTtl`, `reviewsTtl` (3d default).

**`staleGroups(CachedEntity entity, Instant now)`** — returns `Set<StaleFieldGroup>`:
- BASIC stale when `fetchedAt + coordinatesTtl < now`
- DETAIL stale when `detailFetchedAt == null` or `detailFetchedAt + detailTtl < now`

### SpatialCacheStore

Reuses `listAll(String tenantId)` added by #421.

### Orchestrator implementation

```
refreshStale(tenantId):
  entities = cacheStore.listAll(tenantId)
  now = Instant.now()
  refreshed = 0
  for each entity:
    try:
      stale = decayPolicy.staleGroups(entity, now)
      if stale.isEmpty(): continue
      if stale.contains(DETAIL):
        re-fetch via LocationPlatform.placeDetails(entity.source(), entity.externalId())
        update properties + detailFetchedAt
      if stale.contains(BASIC):
        re-fetch via LocationPlatform.placeSearch() by name/coordinates
        match back via entity resolution, update core fields + fetchedAt
        (safety net — coordinates/name rarely change; DETAIL is the common case)
      cacheStore.set(updatedEntity, tenantId)
      refreshed++
    catch Exception:
      log warning, skip entity
  fire StaleEntitiesRefreshed(tenantId, refreshed) CDI event
```

### CDI event

```java
public record StaleEntitiesRefreshed(String tenantId, int refreshedCount) {}
```

Fired after each tenant's refresh pass. Consumers (e.g., downstream caches) can react to data freshness changes.

### Error isolation

Per-entity try/catch. Provider failures are logged and the entity is skipped. Partial refresh is acceptable — the entity retains its existing (stale) data and will be retried on the next pass.

## 9. #423 — CDI wiring

### Config mapping

```java
@ConfigMapping(prefix = "casehub.knowledge")
public interface KnowledgePipelineConfig {
    @WithDefault("5") int geohashPrecision();
    CacheConfig cache();
    ResearchConfig research();

    interface CacheConfig {
        @WithDefault("P90D") Duration maxEntityAge();
        @WithDefault("P1D") Duration searchResultsTtl();
        @WithDefault("P30D") Duration coordinatesTtl();
        @WithDefault("P3D") Duration ratingTtl();
        @WithDefault("P7D") Duration contactTtl();
        @WithDefault("P7D") Duration hoursTtl();
        @WithDefault("P3D") Duration reviewsTtl();
        @WithDefault("P14D") Duration imagesTtl();
        @WithDefault("24h") Duration evictionInterval();
    }

    interface ResearchConfig {
        @WithDefault("P180D") Duration maxSessionDuration();
    }
}
```

### KnowledgePipelineDefaultBeans

```java
@ApplicationScoped
public class KnowledgePipelineDefaultBeans {

    @Produces @DefaultBean
    CacheDecayPolicy cacheDecayPolicy(KnowledgePipelineConfig config) { ... }

    @Produces @DefaultBean
    SubsumptionRule subsumptionRule() { return new SpatialSubsumptionRule(); }

    @Produces @DefaultBean @ApplicationScoped
    CacheEvictionScheduler cacheEvictionScheduler(...) { ... }

    @Produces @DefaultBean
    EntityResolutionEngine entityResolutionEngine(...) { ... }
}
```

### @ApplicationScoped annotations

| Class | Injection |
|-------|-----------|
| `KnowledgePipelineOrchestrator` | `@Inject` constructor, `Instance<LocationPlatform>` for multi-provider discovery |
| `ResearchOrchestrator` | `@Inject` constructor |
| `EntityPromoter` | `@Inject` constructor |
| `SqliteSpatialCacheStore` | `@ApplicationScoped`, `HikariDataSource` via `SqliteDataSourceFactory` |
| `QueryCacheStore` | `@ApplicationScoped`, shared `HikariDataSource` |
| `DedupIndexStore` | `@ApplicationScoped`, shared `HikariDataSource` |
| `EntityMetadataStore` | `@ApplicationScoped`, shared `HikariDataSource` |
| `ResearchSessionStore` | `@ApplicationScoped`, shared `HikariDataSource` |

SQLite path config: `casehub.knowledge.sqlite.path` via `SqliteDataSourceFactory` (shared module).

### Alternatives for testing

`InMemorySpatialCacheStore` — `@Alternative @Priority(2)`.

### Graceful degradation

- `Instance<LocationPlatform>` — zero providers means search returns empty list, no startup failure
- `Instance<CognitiveAttentionAccumulator>` — optional, no-op when absent

### Eviction scheduling

```java
@ApplicationScoped
public class CacheEvictionTask {
    @Inject CacheEvictionScheduler scheduler;

    @Scheduled(every = "${casehub.knowledge.cache.eviction-interval:24h}")
    void runEviction() { scheduler.runEviction(); }
}
```

## 10. Schema Migrations

Two Flyway migrations for the SQLite database:

**V2 — Query cache query parameters (#419):**
```sql
ALTER TABLE query_cache ADD COLUMN query_type TEXT;
ALTER TABLE query_cache ADD COLUMN lat REAL;
ALTER TABLE query_cache ADD COLUMN lng REAL;
ALTER TABLE query_cache ADD COLUMN radius_meters INTEGER;
ALTER TABLE query_cache ADD COLUMN category TEXT;
```

**V3 — Detail fetch timestamp (#420):**
```sql
ALTER TABLE spatial_cache ADD COLUMN detail_fetched_at TEXT;
```

## References

- `knowledge-pipeline/KnowledgePipelineOrchestrator.java` — search flow, refreshStale stub
- `knowledge-pipeline/cache/QueryCacheStore.java` — exact key lookup, schema
- `knowledge-pipeline/cache/CacheEvictionScheduler.java` — eviction loop
- `knowledge-pipeline/cache/CacheDecayPolicy.java` — per-field TTLs
- `knowledge-pipeline/cache/SpatialSubsumptionRule.java` — subsumption SPI impl
- `knowledge-pipeline/research/ResearchOrchestrator.java` — session lifecycle
- `knowledge-pipeline/promotion/EntityPromoter.java` — ensureSubgraph copy
- `knowledge-pipeline-api/CachedEntity.java` — entity record
- `knowledge-pipeline-api/KnowledgeQuery.java` — sealed query hierarchy
- `knowledge-pipeline-api/SubsumptionRule.java` — SPI
- `mindmap-intelligence/CheckInService.java` — ensureSubgraph copy
- casehubio/neocortex#418 — parent epic
- casehubio/neocortex#413 — Phase 1 implementation
