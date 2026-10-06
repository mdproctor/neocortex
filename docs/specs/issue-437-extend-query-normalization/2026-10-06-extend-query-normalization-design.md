# Design: Extend Query Normalization Pipeline Beyond Location

**Issue:** casehubio/neocortex#437
**Date:** 2026-10-06
**Status:** Design approved
**Scale:** L | **Complexity:** High

---

## Summary

Generalize the knowledge pipeline from a location-specific orchestrator to a domain-neutral shared pipeline with fan-out, supporting all 6 searchable platform SPIs (location, commerce, contacts, documents, email, project). Travel is excluded — its structured multi-parameter queries need a different decomposition approach (tracked as a separate issue to be filed during implementation).

The pipeline's search→cache→resolve→promote flow is domain-agnostic. Variation is in what gets searched, how entities match, and how cache keys are generated. These are thin strategy interfaces registered per domain via `DomainSupport`.

---

## Architecture — Shared Pipeline with Fan-out

```
Query (natural language or typed KnowledgeQuery)
  │
  ▼
┌──────────────────┐
│  Normalize        │  Configurable chain per domain.
│                   │  Location: [WordNet]
│                   │  Commerce: [Taxonomy, WordNet]
│                   │  Contacts: [Phonetic]
│                   │  Docs/Email/Project: [passthrough]
└────────┬─────────┘
         │ NormalizedQuery + ExpandedTerms
         ▼
┌──────────────────┐
│  Classify         │  Routes to domain-specific KnowledgeQuery.
│                   │  Multi-domain QueryClassifier returns
│                   │  List<KnowledgeQuery> (one per relevant domain).
└────────┬─────────┘
         │ List<KnowledgeQuery> — typed queries per domain
         ▼
┌──────────────────┐
│  Fan-out          │  Per domain, using shared machinery:
│                   │  Error isolation: per-domain exceptions caught,
│                   │  partial results from successful domains returned.
│                   │
│  Cache lookup ────│─ generic CacheStore, per-domain KeyGenerator
│  Subsumption ─────│─ per-domain SubsumptionRule
│  Fetch ───────────│─ shared pagination, per-domain SearchableProvider
│  Resolve ─────────│─ shared EntityResolutionEngine, per-domain
│                   │  EntityMatcher + BlockingStrategy
│  Cache store ─────│─ generic CacheStore
└────────┬─────────┘
         │ List<CachedEntity> merged from all domains
         ▼
┌──────────────────┐
│  Promote          │  Per-domain PromotionStrategy.
│                   │  Location: PLACE subgraph.
│                   │  Commerce: THING subgraph.
│                   │  Contacts: PERSON subgraph.
│                   │  Docs/Email/Project: optional, may not promote.
└──────────────────┘
```

### Error Isolation

Per-domain fan-out uses the same error isolation pattern as the current orchestrator (line 280): each domain's search/cache/resolve cycle runs in a try-catch. A domain failure is logged and skipped — partial results from successful domains are returned. `KnowledgePipelineMetrics` records per-domain errors.

### DomainSupport Registration

```java
public record DomainSupport(
    String domain,
    SearchableProvider provider,
    CacheKeyGenerator keyGenerator,
    SubsumptionRule subsumption,
    EntityMatcher<CachedEntity> matcher,
    BlockingStrategy blockingStrategy,
    List<TermNormalizer> normalizerChain,
    PromotionStrategy promotionStrategy
) {
    public static DomainSupport minimal(String domain,
            SearchableProvider provider) {
        return new DomainSupport(domain, provider,
            TextCacheKeyGenerator.INSTANCE,
            TextSubsumptionRule.INSTANCE,
            NameEntityMatcher.INSTANCE,
            NoOpBlockingStrategy.INSTANCE,
            List.of(),
            NoOpPromotionStrategy.INSTANCE);
    }
}
```

CDI wiring: each domain module registers a `DomainSupport` bean via `@ApplicationScoped` producer. The shared `DomainRegistry` collects all instances via `Instance<DomainSupport>`. Classpath-activated — if a domain module isn't on the classpath, that domain isn't registered.

---

## SPI Definitions

All SPIs in `knowledge-pipeline-api` — domain-neutral, zero external dependencies. Pipeline-local pagination types replace `io.casehub.connectors.Page`/`PageRequest` to maintain the zero-dependency guarantee.

### Pipeline Pagination Types

```java
// In knowledge-pipeline-api — no connectors dependency
public record PipelinePageRequest(String cursor, int pageSize) {
    public static PipelinePageRequest first(int pageSize) {
        return new PipelinePageRequest(null, pageSize);
    }
}

public record PipelinePage<T>(List<T> items, String nextCursor, boolean hasMore) {}
```

### SearchableProvider

```java
public interface SearchableProvider {
    String domain();
    String id();
    PipelinePage<CachedEntity> search(KnowledgeQuery query, PipelinePageRequest page);
    boolean supports(KnowledgeQuery query);
}
```

Wraps any platform SPI's search capability and maps results to `CachedEntity`. Each domain module implements one adapter (e.g., `LocationSearchAdapter` wraps `LocationPlatform.PlaceSearch`, converts connectors `Page<Place>` to `PipelinePage<CachedEntity>`, and serializes coordinates into the properties map).

### CacheStore

Derived from the actual `SpatialCacheStore` method signatures to ensure the generic interface is a valid supertype:

```java
public interface CacheStore {
    void set(CachedEntity entity, String tenantId);
    CachedEntity get(String entityId, String tenantId);
    void remove(String entityId, String tenantId);
    void expire(String entityId, Instant expiresAt, String tenantId);
    List<CachedEntity> listAll(String tenantId);
    List<String> findExpired(String tenantId, Instant now);
    Set<String> discoverTenants();
}
```

Note: `expire()` sets expiry on a specific entity (matching existing semantics), not bulk-delete. `findExpired()` returns entity IDs (matching existing signature).

`SpatialCacheStore` extends `CacheStore` with `nearby()` and `within()` — stays in `knowledge-pipeline-location`. Non-spatial domains use `TextCacheStore` (backed by SQLite, text-keyed).

### BlockingStrategy

```java
public interface BlockingStrategy {
    List<CachedEntity> findCandidates(CachedEntity entity, CacheStore store, String tenantId);
}
```

Domain-specific entity resolution blocking. Location's `SpatialBlockingStrategy` casts to `SpatialCacheStore` and calls `nearby()`. Contacts uses phonetic name matching against `CacheStore.listAll()`. Default: `NoOpBlockingStrategy` returns empty list (dedup-only matching via `DedupIndexStore`).

### EntityResolutionEngine Refactoring

The existing `EntityResolutionEngine` takes `SpatialCacheStore` and `int blockingRadiusMeters`. The refactored version becomes domain-agnostic:

```java
public class EntityResolutionEngine {
    private final EntityMatcher<CachedEntity> matcher;
    private final double autoMergeThreshold;
    private final double signalThreshold;

    // blockingRadiusMeters removed — blocking is per-domain via BlockingStrategy

    public ResolutionResult resolve(List<CachedEntity> newEntities,
                                     BlockingStrategy blockingStrategy,
                                     CacheStore cacheStore,
                                     DedupIndexStore dedupStore,
                                     String tenantId) {
        for (CachedEntity entity : newEntities) {
            // 1. DedupIndexStore lookup (unchanged)
            // 2. BlockingStrategy.findCandidates() replaces SpatialCacheStore.nearby()
            // 3. EntityMatcher.match() (unchanged)
            // 4. Auto-merge or signal (unchanged)
        }
    }
}
```

The spatial blocking radius moves into `SpatialBlockingStrategy`'s constructor (configuration, not a generic pipeline concern). The `CachedEntity.coordinates() == null` guard is removed — `BlockingStrategy` handles domain-appropriate candidate finding.

### PromotionStrategy

```java
@FunctionalInterface
public interface PromotionStrategy {
    PromotionResult promote(CachedEntity entity, PromotionRequest request,
                             MindMapStore mindMapStore, DedupIndexStore dedupStore);
}
```

Per-domain MindMap promotion. The existing `EntityPromoter` is refactored:
- Core dedup logic (check `DedupIndexStore`, resolve by name, enrich existing nodes) moves to a shared `PromotionEngine` in `knowledge-pipeline`.
- Domain-specific logic (subgraph type selection, node creation) moves to per-domain `PromotionStrategy` implementations.

| Domain | Subgraph | Promotion behavior |
|--------|----------|--------------------|
| Location | PLACE | Creates PLACE nodes with lat/lng properties (current behavior) |
| Commerce | THING | Creates THING nodes with price/brand properties |
| Contacts | PERSON | Creates PERSON nodes with contact properties |
| Documents | — | `NoOpPromotionStrategy` — documents don't promote to MindMap |
| Email | — | `NoOpPromotionStrategy` — emails don't promote |
| Project | — | `NoOpPromotionStrategy` — issues don't promote |

### CacheKeyGenerator

```java
public interface CacheKeyGenerator {
    NormalizedQuery generate(KnowledgeQuery query, Map<String, ExpandedTerm> expansions);
}
```

Per-domain cache key generation. Location uses geohash-based keys. Non-spatial domains use normalized text keys.

### QueryClassifier — Multi-domain

The current `QueryClassifier` returns `Optional<KnowledgeQuery>` for a single domain:

```java
// Current — single domain
Optional<KnowledgeQuery> classify(String naturalLanguage, String domain);
```

The new interface supports multi-domain routing:

```java
// New — multi-domain fan-out
public interface QueryClassifier {
    List<KnowledgeQuery> classify(String naturalLanguage);
    Optional<KnowledgeQuery> classify(String naturalLanguage, String domain);
}
```

The zero-arg `classify(NL)` determines which domains are relevant and produces a typed `KnowledgeQuery` per domain. The single-domain overload is retained for caller-directed queries (e.g., "search commerce for headphones"). `LlmQueryClassifier` becomes multi-domain-aware — its system prompt lists all registered domains and their query types.

### KnowledgeQuery — Non-sealed with Per-domain Families

```java
// In knowledge-pipeline-api:
public interface KnowledgeQuery {
    String domain();
}
```

No API-level `TextSearch` record — each domain defines its own query types in its sealed family. This avoids naming collisions across domains.

```java
// In knowledge-pipeline-location:
public sealed interface LocationQuery extends KnowledgeQuery
    permits LocationQuery.TextSearch, LocationQuery.NearbySearch, LocationQuery.CategorySearch {
    default String domain() { return "location"; }
    record TextSearch(String query) implements LocationQuery {}
    record NearbySearch(Coordinates center, int radiusMeters, CacheFilter filters)
        implements LocationQuery {}
    record CategorySearch(String category, Coordinates center, int radiusMeters)
        implements LocationQuery {}
}

// In knowledge-pipeline-commerce:
public sealed interface CommerceQuery extends KnowledgeQuery
    permits CommerceQuery.TextSearch, CommerceQuery.CategorySearch, CommerceQuery.BrandSearch {
    default String domain() { return "commerce"; }
    record TextSearch(String query) implements CommerceQuery {}
    record CategorySearch(String category) implements CommerceQuery {}
    record BrandSearch(String brand) implements CommerceQuery {}
}

// In knowledge-pipeline-project:
public sealed interface ProjectQuery extends KnowledgeQuery
    permits ProjectQuery.TextSearch, ProjectQuery.RepoSearch {
    default String domain() { return "project"; }
    record TextSearch(String query) implements ProjectQuery {}
    record RepoSearch(String repo, String query) implements ProjectQuery {}
    // OwnerRepo threaded as repo field — ProjectSearchAdapter resolves to OwnerRepo internally
}
```

The generic pipeline routes by `domain()`. Domain-specific code uses exhaustive matching on its own sealed hierarchy. `CacheKeyGenerator` implementations extract text from domain-specific query types for cache key generation.

### KnowledgePipelineService — Updated for Multi-domain

```java
public interface KnowledgePipelineService {
    // Existing — caller provides typed query (single domain)
    List<CachedEntity> search(KnowledgeQuery query, String tenantId);
    List<CachedEntity> search(KnowledgeQuery query, String tenantId,
                               String researchSessionId);

    // New — natural language with auto-routing to multiple domains
    List<CachedEntity> search(String naturalLanguage, String tenantId);
    List<CachedEntity> search(String naturalLanguage, String tenantId,
                               String researchSessionId);

    PromotionResult promote(PromotionRequest request);
    void refreshStale(String tenantId);
}
```

The NL overloads use `QueryClassifier.classify(naturalLanguage)` to fan out across domains. The typed overloads route to a single domain by `query.domain()`. Both return merged `List<CachedEntity>` with per-entity `domain` field.

### CachedEntity — Domain-neutral

```java
public record CachedEntity(
    String id,
    String name,
    String domain,
    String category,
    String source,
    String externalId,
    Map<String, String> properties,
    Instant fetchedAt,
    Instant detailFetchedAt,
    Instant expiresAt,
    Set<String> sessionIds,
    boolean hasDetail
) {}
```

`detailFetchedAt` and `hasDetail` are retained as generic lifecycle fields — all 6 in-scope SPIs have a search-for-summaries → fetch-details pattern.

### TermNormalizer — Domain Parameter Semantics

The `TermNormalizer.normalize(String term, String domain)` interface is retained but the `domain` parameter semantics change:

- **Current:** Receives `KnowledgeDomain` constants (`PLACE`, `THING`, `ACTIVITY`). Used by `WordNetTermNormalizer` to select lexical files.
- **New:** Receives the `DomainSupport.domain()` string (`"location"`, `"commerce"`, etc.). Normalizers that need finer-grained domain context (e.g., WordNet's lexical file selection) maintain an internal mapping from pipeline domain to their domain-specific concepts.

`KnowledgeDomain` class is deprecated — its constants become internal to `WordNetTermNormalizer`'s lexical file mapping. The pipeline uses `DomainSupport.domain()` strings exclusively.

---

## Spatial Type Extraction

Types moving from `knowledge-pipeline-api` to `knowledge-pipeline-location`:

| Type | Why it moves |
|------|-------------|
| `Coordinates` (import from location-spi) | Creates Maven dependency on `casehub-connectors-location-spi` |
| `BoundingBox` | Spatial concept with no non-location use |
| `SpatialCacheStore` | Extends generic `CacheStore` with spatial methods |
| `CacheFilter` | Fields (`priceLevel`, `minRating`, `maxRadius`) are location-specific |
| `SpatialBucket` | Geohash encoding — purely spatial |

After extraction, `knowledge-pipeline-api` has zero connector SPI dependencies — matching `inference-api` and `rag-api` conventions.

---

## Module Structure

```
knowledge-pipeline-api/          — domain-neutral SPIs (CacheStore, SearchableProvider,
                                   EntityMatcher, BlockingStrategy, PromotionStrategy,
                                   DomainSupport, KnowledgeQuery, CachedEntity,
                                   TermNormalizer, QueryClassifier, PipelinePage,
                                   PipelinePageRequest, MatchResult, MatchTier,
                                   PromotionRequest, PromotionResult, NormalizedQuery,
                                   SubsumptionRule, KnowledgePipelineService,
                                   ResearchSessionService, ResearchSession, ResearchState)
knowledge-pipeline/              — shared pipeline orchestrator (KnowledgePipelineOrchestrator),
                                   EntityResolutionEngine (refactored — BlockingStrategy),
                                   PromotionEngine (shared dedup + per-domain delegate),
                                   TextCacheStore, TextCacheKeyGenerator, TextSubsumptionRule,
                                   NameEntityMatcher, NoOpBlockingStrategy,
                                   NoOpPromotionStrategy, DomainRegistry,
                                   QueryCacheStore, DedupIndexStore, EntityMetadataStore,
                                   CacheDecayPolicy, ExpansionStrategy,
                                   ResearchOrchestrator, ResearchSessionStore,
                                   KnowledgePipelineMetrics (domain-tagged counters)
knowledge-pipeline-location/     — LocationSearchAdapter, PlaceMatcher, SpatialCacheStore
                                   (extends CacheStore), SpatialKeyGenerator,
                                   SpatialSubsumptionRule, SpatialBlockingStrategy,
                                   LocationPromotionStrategy (PLACE subgraph),
                                   LocationQuery sealed family, CacheFilter, SpatialBucket,
                                   BoundingBox, Haversine, PhoneNormalizer
knowledge-pipeline-commerce/     — CommerceSearchAdapter, ProductMatcher,
                                   CommerceQuery sealed family,
                                   CommercePromotionStrategy (THING subgraph)
knowledge-pipeline-contacts/     — ContactsSearchAdapter, ContactMatcher,
                                   PhoneticTermNormalizer, PhoneticBlockingStrategy,
                                   ContactsQuery sealed family,
                                   ContactsPromotionStrategy (PERSON subgraph)
knowledge-pipeline-documents/    — DocumentSearchAdapter, DocumentMatcher,
                                   DocumentsQuery sealed family
knowledge-pipeline-email/        — EmailSearchAdapter, EmailMatcher,
                                   EmailQuery sealed family
knowledge-pipeline-project/      — ProjectSearchAdapter, IssueMatcher,
                                   ProjectQuery sealed family
```

### Existing Components Placement

| Component | Current location | Disposition |
|-----------|-----------------|-------------|
| `QueryCacheStore` | knowledge-pipeline | Stays shared — caches normalized queries across all domains, domain field in cache entries |
| `DedupIndexStore` | knowledge-pipeline | Stays shared — cross-domain dedup index |
| `EntityMetadataStore` | knowledge-pipeline | Stays shared — session associations are domain-neutral |
| `ExpansionStrategy` | knowledge-pipeline | Stays shared — per-provider variant dispatch applies to all domains |
| `CacheDecayPolicy` | knowledge-pipeline | Stays shared — field-type TTLs are domain-neutral |
| `ResearchSessionService/Store` | knowledge-pipeline-api + knowledge-pipeline | Stays shared — research sessions span domains |
| `KnowledgeDomain` | knowledge-pipeline-api | Deprecated — replaced by `DomainSupport.domain()` strings |
| `MatchResult`, `MatchTier` | knowledge-pipeline-api | Stays in API — used by `EntityMatcher` contract |
| `KnowledgePipelineMetrics` | knowledge-pipeline | Stays shared — domain-tagged via `DomainSupport.domain()` |

**Dependency graph:**

```
knowledge-pipeline-api  ← zero external deps
       ↑
knowledge-pipeline      ← depends on pipeline-api + mindmap-api (for PromotionEngine)
       ↑
knowledge-pipeline-{domain}  ← depends on pipeline-api + connectors-{domain}-spi
```

---

## Per-domain Implementation Summary

| Domain | SearchableProvider | EntityMatcher | Normalizer chain | Key resolution strategies |
|--------|-------------------|---------------|-----------------|--------------------------|
| **Location** | Wraps `PlaceSearch`, `Geocoding` | Haversine distance + phone normalization | [WordNet] | Spatial cache keys, spatial blocking, spatial subsumption |
| **Commerce** | Wraps `ProductSearch` | SKU + brand + name matching | [Taxonomy, WordNet] | Text cache keys, category hierarchy subsumption |
| **Contacts** | Wraps `ContactRead` | Phonetic name + email/phone dedup | [Phonetic] | Text cache keys, phonetic blocking |
| **Documents** | Wraps `SearchOperations` | Filename + content hash dedup | [passthrough] | Text cache keys, hash-based dedup |
| **Email** | Wraps `EmailPlatform.search` | Message-id dedup | [passthrough] | Text cache keys, message-id dedup |
| **Project** | Wraps `Issues.search` (OwnerRepo via `ProjectQuery.RepoSearch`) | Issue number + repo dedup | [passthrough] | Text cache keys, issue-number dedup |

---

## Normalizer Architecture

Each domain declares its normalizer chain via `DomainSupport`. No universal fallback — WordNet is registered explicitly by domains that benefit from synonym expansion.

| Domain | Chain | Rationale |
|--------|-------|-----------|
| Location | [WordNetTermNormalizer] | Synonym expansion for place/thing/activity terms |
| Commerce | [TaxonomyTermNormalizer, WordNetTermNormalizer] | Product category hierarchy + general synonyms |
| Contacts | [PhoneticTermNormalizer] | Soundex/Metaphone for name variants |
| Documents | [] (passthrough) | Stemming done at search layer by provider |
| Email | [] (passthrough) | Temporal/structural normalization at classifier |
| Project | [] (passthrough) | Structured field extraction at classifier |

New implementations:
- `PhoneticTermNormalizer` — Apache Commons Codec (Soundex, Metaphone). Generates phonetic variants of person names.
- `TaxonomyTermNormalizer` — Product category hierarchy expansion. "headphones" → "earphones", "wireless audio", "over-ear headphones".

---

## Epic Decomposition

Validation-first: prove abstractions with commerce before generalizing.

| # | Title | Scale | Complexity | Blocked by | Deliverable |
|---|-------|-------|------------|------------|-------------|
| 1 | Extract generic pipeline SPIs from location | M | Med | — | All SPIs in `knowledge-pipeline-api`. Domain-neutral `CachedEntity`. Non-sealed `KnowledgeQuery`. Refactored `EntityResolutionEngine` (BlockingStrategy). Refactored `PromotionEngine`. `knowledge-pipeline-location` module using new SPIs. Existing tests pass. Deprecate `KnowledgeDomain`. |
| 2 | Commerce vertical slice — validate generic pipeline | M | Med | 1 | `knowledge-pipeline-commerce` module. `CommerceSearchAdapter`, `ProductMatcher`, `CommerceQuery`. `TaxonomyTermNormalizer` (needed for commerce chain). End-to-end: search → cache → resolve → promote. Validates abstractions. |
| 3 | Contacts domain module | S | Med | 1 | `knowledge-pipeline-contacts`. `PhoneticTermNormalizer` (needed for contacts chain). `PhoneticBlockingStrategy`. Phonetic name matching. |
| 4 | Documents domain module | S | Low | 1 | `knowledge-pipeline-documents`. Content-hash dedup. |
| 5 | Email domain module | S | Low | 1 | `knowledge-pipeline-email`. Message-id dedup. |
| 6 | Project domain module | S | Low | 1 | `knowledge-pipeline-project`. `ProjectQuery.RepoSearch` with OwnerRepo threading. Issue-number dedup. |
| 7 | ARC42STORIES knowledge pipeline chapter | XS | Low | 1 | Knowledge pipeline journey + chapter entries in ARC42STORIES.MD |

**Order:** 1 → 2 (validates) → 3-6 (parallel) → 7

Note: `TaxonomyTermNormalizer` ships with issue 2 (commerce), `PhoneticTermNormalizer` ships with issue 3 (contacts) — each normalizer is delivered alongside the domain that needs it, not as a separate issue.

---

## Architectural Placement

`TermNormalizer` SPI and implementations remain in neocortex. Extraction to a shared module (e.g., `casehub-nlp`) is deferred until connectors #138 Phase 2 creates a concrete consumer. The extraction is mechanical — one Maven module, one package move.

Knowledge pipeline and CBR remain separate subsystems. The pipeline caches external API responses; CBR stores accumulated experiences. They solve different problems with different data flows. Phase 3's dimensional memory (from the R&D doc) builds ON TOP of both — using CBR's outcome learning to weight the pipeline's cached entities.

---

## Testing Strategy

- **Contract tests:** `CacheStoreContractTest` abstract base for all CacheStore implementations (mirrors `MindMapStoreContractTest`, `CbrRecordStoreContractTest` pattern)
- **Per-domain integration tests:** Each domain module has integration tests exercising search → cache → resolve round-trip with stub providers
- **Orchestrator unit tests:** Shared pipeline tested with mock `DomainSupport` registrations — verifies fan-out, error isolation (per-domain failure returns partial results), cache hit/miss, subsumption delegation
- **Existing location tests:** Must pass unchanged after refactoring — the location module implements the same SPIs the old orchestrator used directly

---

## References

- `KnowledgePipelineOrchestrator.java` — current location-specific implementation (383 lines, ~50% reusable flow)
- `EntityResolutionEngine.java` — current spatial-specific resolution (SpatialCacheStore + blockingRadiusMeters)
- `EntityPromoter.java` — current PLACE-specific promotion (resolveInPlaceSubgraphs, createNewPlaceNode)
- `KnowledgePipelineService.java` — current single-domain service interface
- `SpatialCacheStore.java` — 9 methods (7 generic, 2 spatial: nearby, within)
- `KnowledgeQuery.java` — current sealed hierarchy (7+ exhaustive switches, all location-specific)
- `WordNetTermNormalizer.java` — 3 mapped domains only (PLACE, THING, ACTIVITY)
- `CachedEntity.java` — current implementation with Coordinates dependency
- `KnowledgeDomain.java` — current domain constants (to be deprecated)
- connectors #140 — NLP-backed normalization exploration
- connectors #138 — ref/simulation unification (interpretive capability classification)
- Phase 3 R&D doc — `specs/2026-10-05-knowledge-pipeline-phase-3-rnd.md`
- All 6 connector SPI interfaces (verified via IntelliJ MCP)
