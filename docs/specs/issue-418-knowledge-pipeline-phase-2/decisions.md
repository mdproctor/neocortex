## D1: Subsumption scan query storage (#419)

**Choice:** Store query type + parameters as columns in `query_cache` table (lat, lng, radius_meters, category, query_type). Add `listForTenant(tenantId)` method returning `List<NormalizedQuery>`.
**Alternatives:**
- JSON blob column — simpler schema but loses queryability and adds deserialization cost
- Parse cache key back to parameters — zero schema change but geohash→coordinates loses precision, making Haversine containment checks unreliable
**Rationale:** Parameters are structured and fixed-schema (sealed hierarchy with 3 variants). Columns give exact reconstruction without geohash precision loss. Matches how SpatialCacheStore already stores coordinates. Enables indexed spatial lookups if needed later.
**Trade-offs:** Schema migration needed for query_cache table (Flyway). Slightly wider table.
**Sources:** knowledge-pipeline/cache/QueryCacheStore.java, knowledge-pipeline/cache/CacheKeyGenerator.java, knowledge-pipeline-api/NormalizedQuery.java, knowledge-pipeline-api/KnowledgeQuery.java
**Exploration:** quick
**Status:** captured

## D2: Per-field staleness tracking for refreshStale (#420)

**Choice:** Add `detailFetchedAt` to `CachedEntity` + field-type classification in `CacheDecayPolicy`. Two-timestamp model: `fetchedAt` for search-result fields (coordinates, name, category), `detailFetchedAt` for detail fields (rating, hours, phone, website, reviews).
**Alternatives:**
- Per-field timestamps in EntityMetadataStore — maximum flexibility but LocationPlatform SPI doesn't support per-field fetches, so per-field timestamps create illusion of granularity that can't be acted on
**Rationale:** LocationPlatform returns full Place objects — can't fetch individual fields. The granularity that matters is "basic search fields" vs "detail fields", mapping to the two fetch paths (search vs placeDetails). Per-field TTLs in CacheDecayPolicy determine which group is stale, but the refresh operation is always "re-fetch details."
**Trade-offs:** If a future provider supports per-field fetch, the two-timestamp model would need extension. Acceptable since no current provider has this capability.
**Sources:** knowledge-pipeline/cache/CacheDecayPolicy.java, knowledge-pipeline-api/CachedEntity.java, KnowledgePipelineOrchestrator.java
**Exploration:** quick
**Status:** captured
