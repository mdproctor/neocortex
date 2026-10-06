## D1: Pipeline architecture — shared pipeline with fan-out

**Choice:** Shared pipeline with fan-out at classification layer. One pipeline, shared layers (normalize, cache, fetch, resolve, promote), per-domain strategy bundles via `DomainSupport` registration. Multi-domain fan-out from classifier. Scope: 6 of 7 known connector SPIs — travel excluded (see below).

**Alternatives:**
- Per-domain pipeline subclasses — maximum flexibility but ~50% code duplication of the cache→resolve→promote flow across domains, testing burden multiplied
- Microkernel with domain plugins — over-engineered for 6 known domains, plugin lifecycle complexity
- Pipeline toolkit (reusable components, no shared orchestration) — provides cache store, dedup index, normalizer, query cache as independent SPIs that each domain assembles. More flexible but each domain must re-implement the orchestration flow (cache lookup → subsumption check → fetch → resolve → store → record), which is the same across domains.

**Rationale:** The normalize→cache→resolve→promote orchestration flow is domain-agnostic. Verified against the codebase: `KnowledgePipelineOrchestrator.java` (383 lines) decomposes into ~50% flow orchestration (cache lookup, subsumption delegation, entity resolution delegation, cache storage, query cache recording) and ~50% location-specific dispatch (`Place` mapping, `LocationPlatform.PlaceSearch` calls, spatial blocking). The flow half is genuinely reusable; the dispatch half is strategy implementations.

Variation is in *what* gets searched (SearchableProvider), *how* entities are mapped (EntityMapper), *how* entities match (EntityMatcher), *how* blocking candidates are found (BlockingStrategy), *how* cache keys are generated (CacheKeyGenerator), and *how* subsumption works (SubsumptionRule). These are thin strategy interfaces, not fundamental flow differences. The shared pipeline orchestrates the common flow; strategies handle domain variation.

The connector SPIs are heterogeneous in their search interfaces — each has domain-specific search methods beyond `search(String, PageRequest)`. This does NOT invalidate the shared pipeline because the pipeline doesn't abstract away SPI methods. Each domain's `SearchableProvider` encapsulates the SPI-specific dispatch internally. The pipeline manages the flow around it.

**Travel exclusion:** `TravelPlatform` has fundamentally structured multi-parameter queries (`TransportSearch.search(origin, destination, date, PageRequest)`, `AccommodationSearch.search(location, checkIn, checkOut, guests, PageRequest)`) that don't decompose into text-search-based normalization. Travel would need structured field extraction and date parsing — a different query decomposition approach, better handled as a separate issue.

**Strategy resolution:** Each pipeline stage resolves its implementation via a configurable chain of strategies. Chain order is declared per domain via `DomainSupport` registration, not hardcoded. Adding a domain can be as minimal as SearchableProvider + EntityMapper — everything else falls back to generic defaults (text-based cache keys, name-match resolution). Domain-specific overrides only where genuinely needed.

**Trade-offs:** Assumes all in-scope domains fit the same pipeline shape. If a domain needs a fundamentally different flow (e.g., streaming results, multi-hop resolution), the shared pipeline may need escape hatches. Acceptable risk — the 6 in-scope SPIs all follow request→response search patterns.

**Sources:** KnowledgePipelineOrchestrator.java (current location-specific implementation), connector SPI interfaces (verified: all 6 in-scope SPIs have `search(String, PageRequest)` at minimum; domain-specific methods handled by SearchableProvider strategy)

**Exploration:** quick
**Status:** revised — corrected domain count to 7 (was 6), explicitly excluded travel, corrected uniformity claim, added pipeline toolkit as considered alternative, added BlockingStrategy to strategy list

## D2: Module structure — per-domain modules

**Choice:** Per-domain modules in neocortex: `knowledge-pipeline-location`, `knowledge-pipeline-commerce`, `knowledge-pipeline-contacts`, `knowledge-pipeline-documents`, `knowledge-pipeline-email`, `knowledge-pipeline-project`. Each depends on `knowledge-pipeline-api` + its connector SPI. Classpath-activated via CDI.

**Alternatives:**
- Single module with packages — simpler build but forces dependency on all 6 connector SPIs regardless of usage
- Domain support in connectors repo — clean ownership but circular dependency (connectors → neocortex pipeline-api, neocortex → connectors SPIs)

**Rationale:** Matches existing neocortex modular pattern (inference-api/runtime/tasks, rag-api/rag/crossencoder). Each domain is independently testable and classpath-activated. Adding a domain module doesn't touch existing modules.

**Trade-offs:** More Maven modules to maintain. Acceptable — the modules are thin (strategy implementations only), and the alternative (one fat module) creates unwanted transitive dependencies.

**Sources:** neocortex module structure, Maven conventions
**Exploration:** quick
**Status:** captured

## D3: Entity model — domain-neutral CachedEntity with spatial extraction

**Choice:** Extract all spatial types (`Coordinates`, `BoundingBox`) out of `knowledge-pipeline-api` into the location domain module (`knowledge-pipeline-location`). Also extract `CacheFilter`, `SpatialCacheStore`, and `SpatialBucket` — all carry location-specific semantics (see extraction inventory below). Define a domain-neutral `CachedEntity` in the API with no location-spi dependency. Add a `domain` field. Domain-specific logic goes in per-domain EntityMatcher/SubsumptionRule/BlockingStrategy implementations.

Retain `detailFetchedAt` and `hasDetail` as first-class `CachedEntity` fields — these are generic pipeline lifecycle fields, not location-specific (see rationale below).
**Depends on:** D1 (pipeline architecture)

**Alternatives:**
- Make coordinates nullable (original proposal) — insufficient: the `Coordinates` import and `casehub-connectors-location-spi` Maven dependency remain in the API module, forcing every domain module to transitively depend on the location SPI
- Sealed entity hierarchy (CachedPlace, CachedProduct, etc.) — type-safe but rigid, every domain = new subtype
- Generic pipeline `<E>` — maximum type safety but complex wiring, each domain needs its own pipeline instance
- Domain-neutral `GeoPoint(double lat, double lng)` in the API — simpler but still forces a spatial concept into the generic API; the right design treats location as just another domain

**Extraction inventory** — full scope of types moving from `knowledge-pipeline-api` to `knowledge-pipeline-location`:

| Type | Current location | Why it moves |
|---|---|---|
| `Coordinates` (import) | CachedEntity, KnowledgeQuery, SpatialCacheStore | Creates Maven dependency on `casehub-connectors-location-spi` |
| `BoundingBox` | SpatialCacheStore.within() | Spatial concept with no non-location use |
| `SpatialCacheStore` | knowledge-pipeline-api | Extends generic `CacheStore` (D4) with spatial methods `nearby()` and `within()` |
| `CacheFilter` | knowledge-pipeline-api | Fields `priceLevel`, `minRating`, `maxRadius` are location-specific; used only in `SpatialCacheStore.nearby/within()`. Replace with per-domain filter types or generic `Map<String, Object>` predicates. |
| `SpatialBucket` | knowledge-pipeline-api | Geohash encoding — purely spatial. Used only by `CacheKeyGenerator` for NearbySearch/CategorySearch cache keys. |

**Types staying in the API** (domain-neutral):

| Type/field | Stays because |
|---|---|
| `CachedEntity.detailFetchedAt` | Generic pipeline lifecycle field — tracks when secondary detail fetch occurred. ALL 6 in-scope SPIs have a search-for-summaries → fetch-details pattern: `LocationPlatform.PlaceDetails.get()`, `CommercePlatform.ProductDetails.get()`, `ContactsPlatform.ContactRead.get()`, `DocumentPlatform.FileOperations.get()`, `EmailPlatform.getMessage()`, `ProjectPlatform.Issues.get()`. Currently referenced only by location code because no other domain is implemented yet. |
| `CachedEntity.hasDetail` | Boolean companion to `detailFetchedAt` — tracks whether the detail fetch has happened. Same generality argument. |
| `SubsumptionRule` | @FunctionalInterface, domain-neutral SPI |
| `TermNormalizer` | @FunctionalInterface, domain-neutral SPI |
| `EntityMatcher<T>` | @FunctionalInterface, domain-neutral SPI |
| `NormalizedQuery` | Domain-neutral cache key wrapper |

**Rationale:** `knowledge-pipeline-api` currently has a hard compile dependency on `casehub-connectors-location-spi` (verified: `CachedEntity.java`, `KnowledgeQuery.java`, `SpatialCacheStore.java` all import `io.casehub.connectors.location.model.Coordinates`; `knowledge-pipeline-api/pom.xml` line 17 declares the dependency). This violates neocortex's SPI layering principle — `inference-api` and `rag-api` have zero external domain dependencies, enforced by ArchUnit. The pipeline API should follow the same pattern.

Extracting spatial types to the location domain module treats location as just another domain, which is what D1 intends. The location module maps `Coordinates` to/from the generic entity's properties map internally.

**Trade-offs:** Location-domain code must serialize/deserialize coordinates through the properties map. Small runtime cost, significant architectural gain — the API module becomes domain-neutral.

**Sources:** CachedEntity.java, KnowledgeQuery.java, CacheFilter.java, SpatialBucket.java, SpatialCacheStore.java, knowledge-pipeline-api/pom.xml, neocortex ArchUnit conventions, all 6 in-scope connector SPI interfaces (detail-fetch pattern verification)
**Exploration:** quick
**Status:** revised — added full extraction inventory (CacheFilter, SpatialBucket, SpatialCacheStore alongside Coordinates/BoundingBox); retained detailFetchedAt/hasDetail as generic fields with SPI evidence

## D4: Cache strategy — generic CacheStore SPI with spatial extension

**Choice:** Extract a generic `CacheStore` interface with the domain-neutral methods (`set`, `get`, `remove`, `expire`, `listAll`, `findExpired`, `discoverTenants`). `SpatialCacheStore` extends `CacheStore` with spatial methods (`nearby`, `within`). Non-spatial domains use `CacheStore` directly (e.g., a `TextCacheStore` implementation with text-based lookup). Cache key generation delegates to per-domain `CacheKeyGenerator`. Subsumption delegates to per-domain `SubsumptionRule`. Generic defaults: text-based keys, text subsumption (prefix/containment matching).
**Depends on:** D1 (pipeline architecture), D3 (spatial types extracted from API)

**Alternatives:**
- Everything through text keys — simpler but loses spatial subsumption efficiency for location
- Keep spatial + add text alongside — the orchestrator already separates flow from dispatch via strategy SPIs, so this is what happens naturally

**Rationale:** Verified: `SpatialCacheStore` has 9 methods; 7 are domain-neutral (`set`, `get`, `remove`, `expire`, `listAll`, `findExpired`, `discoverTenants`) and 2 are spatial-specific (`nearby`, `within`). The generic interface captures the 7 shared methods; spatial methods are an extension. `EntityResolutionEngine` uses `cacheStore.nearby()` for spatial blocking — this becomes a domain-specific `BlockingStrategy` (see D1) rather than a hardcoded spatial method call, making the resolution engine domain-agnostic.

**Trade-offs:** SpatialCacheStore needs refactoring to extend the generic SPI. `EntityResolutionEngine` needs a `BlockingStrategy` abstraction to replace the hardcoded `nearby()` call. Existing tests need updating.

**Sources:** SpatialCacheStore.java (7/9 methods generic), EntityResolutionEngine.java (spatial blocking at line 63), CacheKeyGenerator.java, SpatialSubsumptionRule.java
**Exploration:** quick
**Status:** revised — clarified as inheritance (CacheStore ← SpatialCacheStore), added BlockingStrategy for entity resolution

## D5: Query types — non-sealed parent with per-domain sealed families

**Choice:** `KnowledgeQuery` becomes a non-sealed interface with a `domain()` method in the API module. Each domain module defines its own sealed query hierarchy implementing `KnowledgeQuery`. Location: `LocationQuery sealed permits TextSearch, NearbySearch, CategorySearch`. Commerce: `CommerceQuery sealed permits TextSearch, CategorySearch, BrandSearch`. The generic pipeline routes by `domain()`; domain-specific pipelines use exhaustive matching on their sealed hierarchy.
**Depends on:** D1 (pipeline architecture), D3 (spatial types extracted from API)

**Alternatives:**
- Keep sealed, expand subtypes — rigid: every new domain modifies the sealed interface in the API module
- Fully open (no sealed families) — loses exhaustive matching everywhere, including within domain pipelines
- Keep sealed in API, add non-sealed subtypes — Java sealed interface permits clause only allows subtypes in the same module, so domain modules can't add subtypes

**Rationale:** The current sealed hierarchy drives 7+ exhaustive switch expressions in the codebase (verified: `KnowledgePipelineOrchestrator` has 5 switches, `CacheKeyGenerator` has 2, `SpatialSubsumptionRule` has 1). All of these are location-specific — they dispatch to `PlaceSearch` methods or extract spatial parameters. These switches belong in the location domain module, not the generic pipeline.

The generic pipeline needs only: (1) route by `domain()` to the right domain pipeline, (2) handle `TextSearch` as the common case for cache key generation. Domain-specific query parameters (coordinates, radius, category, brand, etc.) are opaque to the generic pipeline — the domain's own sealed hierarchy provides exhaustive matching where it matters.

**Trade-offs:** Location-specific switches must migrate from the current orchestrator to the location domain module. The generic orchestrator operates on `KnowledgeQuery` without exhaustive matching — acceptable because it delegates to domain-specific strategies.

**Sources:** KnowledgeQuery.java (current sealed hierarchy), KnowledgePipelineOrchestrator.java (7+ exhaustive switches, all location-specific)
**Exploration:** quick
**Status:** revised — per-domain sealed families instead of fully open hierarchy; preserves exhaustive matching within domains

## D6: Normalizer strategy — per-domain normalizer chain registration

**Choice:** Each domain explicitly declares its normalizer chain via `DomainSupport` registration. No universal fallback. WordNet is available as a registered `TermNormalizer` implementation for domains that benefit from synonym expansion (location, commerce). Domains that need different normalization (phonetic matching for contacts, structured field extraction for project/email) register their own normalizers without WordNet in the chain.

New `TermNormalizer` implementations: `PhoneticTermNormalizer` (Soundex/Metaphone for contacts), `TaxonomyTermNormalizer` (product category hierarchies for commerce). CDI multi-instance with domain-scoped selection.
**Depends on:** D1 (strategy resolution chain)

**Alternatives:**
- Universal WordNet fallback (original proposal) — WordNet is only useful for location and commerce (verified: `WordNetTermNormalizer` maps only `PLACE`, `THING`, `ACTIVITY` in `KnowledgeDomain`; for unmapped domains it returns passthrough). Having it in every domain's chain adds unnecessary dependency weight (extjwnl) for domains that never benefit.
- LLM-only normalization — single implementation handles any domain, but higher latency, API cost per query, less predictable
- Composite chain (WordNet → domain → LLM) — most thorough but complex ordering, potential redundancy

**Rationale:** WordNet's value is domain-specific, not universal. Verified: `WordNetTermNormalizer` maps only 3 domains (`PLACE`, `THING`, `ACTIVITY`) and returns passthrough for all others. The passthrough is cheap (no dictionary lookup for unmapped domains), but architecturally, each domain should declare exactly the normalizers it needs. This makes the normalizer chain explicit and auditable per domain.

| Domain | Normalizer chain |
|--------|-----------------|
| Location | WordNetTermNormalizer |
| Commerce | TaxonomyTermNormalizer → WordNetTermNormalizer |
| Contacts | PhoneticTermNormalizer |
| Documents | (text passthrough — stemming done at search layer) |
| Email | (text passthrough — temporal/structural normalization done at query classifier) |
| Project | (text passthrough — structured field extraction done at query classifier) |

**Trade-offs:** New dependencies for phonetic libraries (Apache Commons Codec for Soundex/Metaphone). Product taxonomy data needs sourcing. Both are well-understood problems with established Java libraries.

**Sources:** WordNetTermNormalizer.java (3 mapped domains only), connectors #140 (NLP-backed normalization exploration)
**Exploration:** quick
**Status:** revised — per-domain normalizer chain instead of universal WordNet fallback; explicit chain declaration per domain

## D7: Epic decomposition — validation-first, then layers

**Choice:** Validate abstractions against one real domain (commerce) before generalizing. Decomposition:
1. Commerce vertical slice — implement commerce end-to-end: extract generic pipeline SPIs from the location implementation, implement commerce SearchableProvider + EntityMapper + EntityMatcher, validate that the generic pipeline works for a non-location domain
2. Infrastructure extraction — extract validated generic infrastructure into the API module, refactor location to use it (both location and commerce now run on the shared pipeline)
3. Remaining domain modules — one issue per domain (contacts, documents, email, project), each adds SearchableProvider + EntityMapper + EntityMatcher + module wiring
4. Normalization extensions — add PhoneticTermNormalizer (contacts), TaxonomyTermNormalizer (commerce), per-domain normalizer chain registration
**Depends on:** D1, D3, D4, D5

**Alternatives:**
- Layers-first (original proposal) — builds generic infrastructure before any domain validates it. Risk: every quick-pick decision (D1-D5) is unvalidated; if the pipeline shape, entity model, cache SPI, or query hierarchy is wrong, the infrastructure must be reworked. All 7 decisions are `Exploration: quick` with no validation against a non-location domain.
- Domain-first (all vertical slices) — each domain repeats infrastructure work until patterns stabilize. More expensive than validation-first because infrastructure patterns are discovered N times instead of once.

**Rationale:** Commerce is the right validation target — it's the most similar to location (text search + category search + product details) while being different enough to stress-test the abstractions (no spatial data, brand-based search, product taxonomy). If the generic infrastructure works for commerce, it will work for the remaining domains. If it doesn't, the abstractions need rethinking — better to discover this during one domain's implementation than after building an entire generic infrastructure layer.

The cost of one vertical slice is low. The cost of wrong generic infrastructure is high.

**Trade-offs:** Commerce module ships before the generic infrastructure is fully extracted. This means the first iteration may need refactoring when the infrastructure is extracted in step 2. Acceptable — the refactoring is informed by real validation, not by unvalidated assumptions.

**Sources:** Issue #437 scope (6 in-scope domains, full resolution)
**Exploration:** quick
**Status:** revised — validation-first (commerce) instead of layers-first; validates unproven abstractions before generalizing

## D8: Knowledge pipeline vs CBR — distinct subsystems with different purposes

**Choice:** The knowledge pipeline and CBR remain separate subsystems. The knowledge pipeline is a connector-side intelligence layer for caching, deduplicating, and resolving external API responses. CBR is a case-based reasoning system for storing accumulated experiences, scoring similarity, and learning from outcomes. They solve different problems and should not be merged.
**Surfaced by:** R1-04 reviewer challenge

**Alternatives:**
- Converge onto CBR — migrate the knowledge pipeline's caching/resolution to CbrCaseMemoryStore. Non-spatial domains would use CBR for entity caching, gaining outcome learning, trust-weighted retrieval, temporal/scope decay, cross-encoder reranking, and retrieval traceability.
- Converge onto knowledge pipeline — extend the pipeline with CBR-like capabilities (outcome tracking, confidence decay).
- Keep separate (chosen) — each system evolves independently for its specific purpose.

**Rationale:** The apparent overlap is misleading when examined against the actual data flows:

| Knowledge Pipeline | CBR | Different because |
|---|---|---|
| Entity resolution (EntityMatcher) — merges duplicates from different providers | CbrSimilarityScorer — ranks past experiences by relevance | Resolution eliminates duplicates; scoring ranks candidates |
| SpatialCacheStore — caches spatial data from external APIs | CbrCaseMemoryStore — stores learned experiences | Different data (external responses vs accumulated knowledge) |
| SubsumptionRule — determines if cached query A answers query B | CbrFilter — pre-similarity structural predicates | Subsumption is query-level semantic containment; filters are field-level value matching |
| DedupIndexStore — prevents storing same external entity twice | supersede() — marks a case as replaced by newer version | Dedup prevents insertion; supersede records history |

The Phase 3 R&D doc proposes CBR for **dimensional memory** — a higher-level cognitive capability built ON TOP of both the knowledge pipeline and CBR. This validates the separation: the knowledge pipeline caches what was fetched; CBR learns what worked. Phase 3's dimensional memory uses CBR's outcome learning to weight knowledge pipeline's cached entities.

**Trade-offs:** Two caching systems exist in the platform. This is acceptable because they cache different things for different purposes. Premature convergence would force one system's model onto the other's problem domain.

**Sources:** ARC42STORIES.MD §5.1 (CBR subsystem), Phase 3 R&D doc (knowledge-pipeline-phase-3-rnd.md), EntityResolutionEngine.java, CbrCaseMemoryStore interface
**Exploration:** quick (surfaced by review)
**Status:** captured

## D9: Normalization architectural placement — in neocortex, extractable when needed

**Choice:** `TermNormalizer` SPI and implementations remain in neocortex (`knowledge-pipeline-api` for the SPI, domain modules for implementations). Extraction to a shared module (e.g., `casehub-nlp`) is deferred until connectors #138 Phase 2 creates a concrete consumer.
**Surfaced by:** R1-10 reviewer challenge

**Alternatives:**
- In connectors framework — normalization lives in the simulation/search infrastructure. Wrong: the primary consumer is the knowledge pipeline's cache key generation flow, not the connectors framework. Moving it there inverts the dependency.
- Standalone NLP utility (`casehub-nlp`) — normalization as a shared module consumed by both neocortex and connectors. Right design if both need it, but premature: connectors #138 Phase 2 doesn't exist yet. YAGNI applies.
- In neocortex (chosen) — normalization lives where its primary consumer is. If connectors needs it later (per #138 Phase 2), extract the SPI to a shared module at that point. The extraction is mechanical.

**Rationale:** Issue #437's body says "A shared normalization layer means simulation strategies can use the same term expansion that production agents use." This is a future requirement from connectors #138 Phase 2 (simulation decoration). The current requirement is to extend normalization within the knowledge pipeline. Designing for the future consumer now would require extracting `TermNormalizer` to a shared module before any second consumer exists — premature extraction adds module maintenance cost for no current benefit.

When connectors #138 Phase 2 arrives, the extraction is one Maven module and one package move. The SPI interface (`TermNormalizer`) is a single `@FunctionalInterface` with one method. The cost of deferring is near-zero.

**Trade-offs:** If connectors #138 Phase 2 arrives soon, the extraction adds a small refactoring step. Acceptable — refactoring is informed by real requirements, not speculation.

**Sources:** Issue #437 body, connectors #138 (simulation decoration), connectors #140 (NLP-backed normalization exploration), TermNormalizer.java
**Exploration:** quick (surfaced by review)
**Status:** captured
