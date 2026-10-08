# Wire TermNormalizer into Platform Search SPIs

**Issue:** casehubio/neocortex#480 (partially addresses #473 — general domain only)
**Date:** 2026-10-08
**Module:** knowledge-pipeline, knowledge-pipeline-api

---

## Problem

Connectors exposes search operations across five platform SPIs. Only LocationPlatform is wired into the knowledge pipeline via `SpatialSearchableProvider`. The other four — CommercePlatform, ContactsPlatform, DocumentPlatform, ProjectPlatform — have no normalization, caching, or entity resolution. Queries go through to the backing providers (Google Drive, GitHub, Google Contacts, etc.) without synonym expansion, stemming, or phonetic matching.

## Solution

Create `SearchableProvider` adapter classes for the four remaining platform search SPIs, following the established `SpatialSearchableProvider` pattern. Each adapter converts platform-specific results to `CachedEntity` and plugs into the knowledge pipeline via `DomainSupport` registration in `DomainRegistry`.

Additionally, implement the general domain for `WordNetTermNormalizer` (#473), add a `StemmingTermNormalizer`, and add a `PhoneticTermNormalizer` to provide per-domain normalization chains.

## Architecture

The knowledge pipeline's core types are domain-neutral:

- `CachedEntity.coordinates` is nullable — non-spatial entities use null
- `KnowledgePipelineOrchestrator` uses the generic `CacheStore` interface
- `SqliteSpatialCacheStore.set()` handles null coordinates — stores in `entity_metadata` without R*Tree indexing
- `DomainRegistry` auto-discovers all `DomainSupport` beans via `@Any Instance<DomainSupport>`

However, spec review identified three infrastructure gaps that must be fixed for normalization to function:

1. **`TextCacheKeyGenerator` ignores expansions** — it uses `query.toString()` and discards the normalized terms. Needs a `NormalizingTextCacheKeyGenerator` that substitutes canonical forms (mirroring `SpatialCacheKeyGenerator` lines 25-29).
2. **Normalizer loop is first-match-wins** — `KnowledgePipelineOrchestrator.normalize()` breaks on the first normalizer that produces a result. Multi-element chains like `[Stemming, WordNet]` mean "stemming OR synonyms," not "stemming then synonyms." Needs a composing rewrite.
3. **Domain name mismatch** — `KnowledgeDomain.PLACE` = `"place"` but `SpatialSearchableProvider.DOMAIN` = `"location"`. WordNet normalization for the location domain is silently inert. `KnowledgeDomain` is `@Deprecated(forRemoval=true)` — retire it and use actual domain strings.

The work is:
1. Three infrastructure fixes (cache key generator, normalizer loop, domain naming)
2. Four adapter classes (one per platform SPI)
3. Four `DomainSupport` CDI producers
4. Two new `TermNormalizer` implementations (stemming, phonetic) + WordNet general domain
5. CDI wiring (resolve bean ambiguity)
6. Maven dependency wiring

---

## 0. Infrastructure Fixes

These must land before the adapters and normalizers — without them, normalization is inert.

### 0.1 NormalizingTextCacheKeyGenerator

`TextCacheKeyGenerator` ignores the `expansions` map — it uses `query.toString()` as the cache key. A search for "headphones" and "earphones" produce different cache keys even though both normalize to "earphone."

Create `NormalizingTextCacheKeyGenerator` in `knowledge-pipeline` that substitutes canonical forms from the expansions map, following `SpatialCacheKeyGenerator`'s pattern:

```java
public class NormalizingTextCacheKeyGenerator implements CacheKeyGenerator {
    @Override
    public NormalizedQuery generate(KnowledgeQuery query, Map<String, ExpandedTerm> expansions) {
        if (!(query instanceof KnowledgeQuery.TextSearch t)) {
            return new NormalizedQuery(query, query.toString().toLowerCase().strip());
        }
        String normalized = t.query().toLowerCase().strip();
        if (expansions != null && !expansions.isEmpty()) {
            for (var entry : expansions.entrySet()) {
                String term = entry.getKey().toLowerCase().strip();
                normalized = normalized.replace(term, entry.getValue().canonical());
            }
        }
        return new NormalizedQuery(query, "TEXT:" + normalized);
    }
}
```

New domains use `NormalizingTextCacheKeyGenerator` instead of `DomainSupport.minimal()`. Update `DomainSupport.minimal()` to use it as well.

### 0.2 Composing normalizer loop

`KnowledgePipelineOrchestrator.normalize()` (lines 247-265) breaks on the first normalizer that produces a result. For chains like `[StemmingTermNormalizer, WordNetTermNormalizer]`, this means "stemming OR synonyms" — WordNet never sees the stemmed form.

Change the loop to pipeline output through all normalizers:

```java
private Map<String, ExpandedTerm> normalize(KnowledgeQuery query, DomainSupport domainSupport) {
    if (!(query instanceof KnowledgeQuery.TextSearch textSearch)) {
        return Map.of();
    }
    Map<String, ExpandedTerm> expansions = new LinkedHashMap<>();
    String text = textSearch.query().toLowerCase().strip();
    String[] tokens = text.split("\\s+");
    for (String token : tokens) {
        String current = token;
        Set<String> allVariants = new LinkedHashSet<>();
        allVariants.add(token);
        for (TermNormalizer normalizer : domainSupport.normalizerChain()) {
            ExpandedTerm expanded = normalizer.normalize(current, query.domain());
            if (!current.equals(expanded.canonical()) || expanded.variants().size() > 1) {
                current = expanded.canonical();
                allVariants.addAll(expanded.variants());
            }
        }
        if (!token.equals(current) || allVariants.size() > 1) {
            expansions.put(token, new ExpandedTerm(current, allVariants));
        }
    }
    return expansions;
}
```

Each normalizer receives the canonical form from the previous normalizer, and all variants are accumulated. "running" → stemmer → "run" → WordNet → canonical "run" with variants {"run", "running", "jog", "trot"}.

### 0.3 Domain name alignment

`KnowledgeDomain` is `@Deprecated(forRemoval=true)` with constants `PLACE="place"`, `THING="thing"`, `ACTIVITY="activity"`. These don't match the actual domain strings used by `SpatialSearchableProvider` (`"location"`). The WordNet normalizer's `DOMAIN_TO_LEX_FILES` map is keyed by these deprecated constants, meaning it never matches the domain strings passed by the orchestrator.

Fix: Replace `KnowledgeDomain` references in `WordNetTermNormalizer.DOMAIN_TO_LEX_FILES` with the actual domain strings used by SearchableProvider adapters:

| Old key | New key | Lex files |
|---|---|---|
| `KnowledgeDomain.PLACE` (`"place"`) | `"location"` | noun.artifact, noun.location |
| `KnowledgeDomain.THING` (`"thing"`) | `"commerce"` | noun.artifact, noun.object |
| `KnowledgeDomain.ACTIVITY` (`"activity"`) | (keep `"activity"` if used elsewhere) | noun.act |

Remove the `KnowledgeDomain` import. The deprecated class can be deleted in a follow-up if no other consumers remain.

---

## 1. SearchableProvider Adapters

Each adapter follows the `SpatialSearchableProvider` pattern: wraps the platform SPI, bridges pagination types, converts results to `CachedEntity`, supports `KnowledgeQuery.TextSearch`.

### 1.1 CommerceSearchableProvider

**Domain:** `"commerce"`
**Wraps:** `CommercePlatform.ProductSearch`
**Supports:** `KnowledgeQuery.TextSearch`

```java
public class CommerceSearchableProvider implements SearchableProvider {
    private static final String DOMAIN = "commerce";
    private final CommercePlatform platform;
    private final Duration entityTtl;
}
```

**Result conversion** (`Product` → `CachedEntity`):

| CachedEntity field | Source |
|---|---|
| id | `CacheEntityIdGenerator.generate(platform.id(), product.id())` |
| name | `product.name()` |
| coordinates | null |
| category | `product.category()` |
| source | `platform.id()` |
| externalId | `product.id()` |
| properties | brand, price (amount + currency), rating, reviewCount, inStock, thumbnailUrl |
| domain | `"commerce"` |

**Search dispatch:** `TextSearch` → `productSearch(userId).search(query, pageRequest)`. The `userId` is not available from `KnowledgeQuery` — use a fixed pipeline identifier `"pipeline"` (same pattern as `SpatialSearchableProvider`).

**Note:** CommercePlatform, ContactsPlatform, and ProjectPlatform take `userId` for capability accessors. DocumentPlatform's `search()` is a no-arg accessor — returns `SearchOperations` directly without userId. Each adapter handles its platform's specific accessor signature.

### 1.2 ContactsSearchableProvider

**Domain:** `"contacts"`
**Wraps:** `ContactsPlatform.ContactRead`
**Supports:** `KnowledgeQuery.TextSearch`

**Result conversion** (`Contact` → `CachedEntity`):

| CachedEntity field | Source |
|---|---|
| id | `CacheEntityIdGenerator.generate(platform.id(), contact.id())` |
| name | `contact.name().displayName()` (or `givenName + " " + familyName` if no displayName) |
| coordinates | null |
| category | null |
| source | `platform.id()` |
| externalId | `contact.id()` |
| properties | company, jobTitle, first email, first phone, photoUrl |
| domain | `"contacts"` |

### 1.3 DocumentSearchableProvider

**Domain:** `"documents"`
**Wraps:** `DocumentPlatform.SearchOperations`
**Supports:** `KnowledgeQuery.TextSearch`

**Result conversion** (`DocumentSummary` → `CachedEntity`):

| CachedEntity field | Source |
|---|---|
| id | `CacheEntityIdGenerator.generate(platform.id(), doc.id())` |
| name | `doc.name()` |
| coordinates | null |
| category | `doc.contentType()` |
| source | `platform.id()` |
| externalId | `doc.id()` |
| properties | folderId, contentType, size, createdAt, modifiedAt |
| domain | `"documents"` |

### 1.4 ProjectSearchableProvider

**Domain:** `"projects"`
**Wraps:** `ProjectPlatform.Issues`
**Supports:** `KnowledgeQuery.TextSearch`

**Extra parameter:** `ProjectPlatform.Issues.search()` requires an `OwnerRepo` parameter. The adapter stores a default `OwnerRepo` at construction time, provided by the CDI producer from configuration.

**Result conversion** (`Issue` → `CachedEntity`):

| CachedEntity field | Source |
|---|---|
| id | `CacheEntityIdGenerator.generate(platform.id(), issue.id())` |
| name | `issue.title()` |
| coordinates | null |
| category | first label name, or null |
| source | `platform.id()` |
| externalId | `issue.id()` |
| properties | number, state, body (truncated to 500 chars), assignees (comma-joined), milestone title |
| domain | `"projects"` |

---

## 2. DomainSupport Producers

Four new `@Produces @DefaultBean @ApplicationScoped @Named` methods in `KnowledgePipelineDefaultBeans`, following the `spatialDomainSupport()` pattern.

Each producer:
1. Auto-discovers `Instance<XxxPlatform>` beans
2. Filters those supporting the search capability class
3. Wraps in the appropriate adapter
4. Handles zero/one/many providers (NoOp / direct / CompositeSearchableProvider)
5. Returns `DomainSupport` with `NormalizingTextCacheKeyGenerator` + text-based defaults + per-domain normalizer chain

### CDI wiring — normalizer bean resolution

Adding `StemmingTermNormalizer` and `PhoneticTermNormalizer` as `@ApplicationScoped` beans creates CDI ambiguity — the existing `spatialDomainSupport()` producer injects a single `TermNormalizer`.

Fix: Inject normalizers by concrete type rather than the `TermNormalizer` interface. Each producer method declares its specific normalizer parameters:

```java
@Produces @Named("documents")
DomainSupport documentsDomainSupport(
        Instance<DocumentPlatform> platforms,
        StemmingTermNormalizer stemmer,
        WordNetTermNormalizer wordnet, ...) {
    return new DomainSupport("documents", provider,
        new NormalizingTextCacheKeyGenerator(), ...
        List.of(stemmer, wordnet));
}
```

Update the existing `spatialDomainSupport()` producer to inject `WordNetTermNormalizer` by concrete type instead of `TermNormalizer`.

### Per-domain normalizer chains

| Domain | Chain | Rationale |
|---|---|---|
| commerce | `[WordNetTermNormalizer]` | Synonym expansion: "headphones" → "earphones" |
| contacts | `[PhoneticTermNormalizer]` | Phonetic matching for name variants |
| documents | `[StemmingTermNormalizer, WordNetTermNormalizer]` | Stemming ("running" → "run") + synonyms |
| projects | `[StemmingTermNormalizer, WordNetTermNormalizer]` | Stemming + synonyms for issue text |

All four domains also benefit from WordNet's general domain (#473). The stemming normalizer applies first to reduce inflected forms before synonym lookup.

---

## 3. New TermNormalizer Implementations

### 3.1 WordNetTermNormalizer — general domain (#473)

Extend the existing `DOMAIN_TO_LEX_FILES` map to support the `"general"` domain with a broad set of lexicographer files:

```java
"general", List.of("noun.artifact", "noun.object", "noun.act",
    "noun.cognition", "noun.communication", "noun.event",
    "noun.person", "noun.group", "noun.state")
```

Also add domain entries for the four new domains, each mapping to domain-appropriate lex files:

| Domain | Lex files |
|---|---|
| commerce | noun.artifact, noun.object (same as THING — products are artifacts) |
| contacts | noun.person, noun.group |
| documents | noun.communication, noun.cognition |
| projects | noun.act, noun.cognition, noun.communication |

This allows WordNet synonym expansion to be domain-aware: a search for "headphones" in the commerce domain matches synsets in noun.artifact, finding "earphone" as a synonym.

### 3.2 StemmingTermNormalizer

A `TermNormalizer` implementation using WordNet's morphological processor for lemmatization. This avoids adding a Lucene/Snowball dependency — extJWNL is already on the classpath and provides `dictionary.getMorphologicalProcessor().lookupBaseForm()`.

```java
@ApplicationScoped
public class StemmingTermNormalizer implements TermNormalizer {
    // Uses WordNet morphological processor
    // "running" → canonical "run", variants {"run", "running"}
    // "studies" → canonical "study", variants {"study", "studies"}
}
```

**Domain scoping:** Stemming is not domain-gated internally — it always processes the input term. Domain scoping is handled by which `DomainSupport.normalizerChain` includes it. Only the `documents` and `projects` domains include `StemmingTermNormalizer` in their chains (see §2). Domains like `contacts` (name stemming is counterproductive) and `commerce` (product names shouldn't be stemmed) omit it from their chains entirely.

### 3.3 PhoneticTermNormalizer

A `TermNormalizer` implementation using Apache Commons Codec Double Metaphone. Double Metaphone has better multilingual support than Soundex (which was designed for US Census data).

```java
@ApplicationScoped
public class PhoneticTermNormalizer implements TermNormalizer {
    // "Smith" → canonical "Smith", variants {"Smith"} + phoneticCode stored in variant
    // "Catherine" → canonical "Catherine", variants {"Catherine"} + phoneticCode
}
```

**Cache key semantics:** Spec review identified that phonetic cache key canonicalization produces incorrect results — "Smith" and "Smyth" are different contacts, so sharing a cache entry returns wrong results. Phonetic normalization should NOT canonicalize the cache key.

Instead, `PhoneticTermNormalizer` adds the phonetic code as a variant but preserves the original term as canonical. This means:
- Cache keys remain distinct ("Smith" and "Smyth" get separate cache entries)
- The phonetic code is available in the `ExpandedTerm.variants()` set for downstream use (e.g., entity matching/dedup — are "Smith" and "Smyth" the same contact?)
- `ExpandedTerm("Smith", Set.of("Smith", "SM0"))` — original is canonical, phonetic code is a variant

This makes the contacts normalizer chain useful for entity dedup (the `EntityMatcher` can compare phonetic codes) rather than cache key canonicalization. A `PhoneticEntityMatcher` that uses Double Metaphone codes for contact dedup is a natural follow-up.

**Dependency:** `org.apache.commons:commons-codec` (already a transitive dependency via connectors).

---

## 4. KnowledgeQuery.domain() default

The current default `domain()` method on `KnowledgeQuery` returns `"location"`. This is misleading for non-spatial queries. `TextSearch` already carries an explicit `domain` field.

**Change:** Update the `KnowledgeQuery.domain()` default to return `null` instead of `"location"`. The orchestrator already handles null domain with a fallback to `"location"` at line 73 — this preserves backward compatibility while making the default semantically correct.

---

## 5. Maven Dependencies

### Root pom.xml — dependencyManagement

Add four new managed dependencies (alongside existing location-spi/location-ref):

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-connectors-commerce-spi</artifactId>
    <version>${casehub-connectors.version}</version>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-connectors-contacts-spi</artifactId>
    <version>${casehub-connectors.version}</version>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-connectors-document-spi</artifactId>
    <version>${casehub-connectors.version}</version>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-connectors-project-spi</artifactId>
    <version>${casehub-connectors.version}</version>
</dependency>
```

### knowledge-pipeline/pom.xml — compile dependencies

Add the four connector SPI dependencies (alongside existing connectors-location-spi).

---

## 6. Testing

### Contract tests

Each adapter gets a unit test verifying:
- `domain()` returns the correct string
- `id()` delegates to the platform
- `supports()` returns true for `TextSearch`, false for spatial queries
- `search()` with a `TextSearch` query delegates to the platform, converts results correctly
- `toEntity()` maps all fields correctly, handles null optional fields

### DomainSupport producer tests

Verify each producer:
- Handles zero platforms (returns NoOpSearchableProvider)
- Handles one platform (returns the adapter directly)
- Handles multiple platforms (returns CompositeSearchableProvider)
- Wires the correct normalizer chain

### Normalizer tests

- **StemmingTermNormalizer:** "running" → "run", "studies" → "study"; processes all inputs (domain scoping is via chain composition)
- **PhoneticTermNormalizer:** "Smith"/"Smyth" produce same phonetic code; processes all inputs (domain scoping is via chain composition)
- **WordNetTermNormalizer general domain:** "headphones" → canonical "earphone" with variants; existing PLACE/THING/ACTIVITY domains unaffected

### Integration tests

Extend `KnowledgePipelineOrchestratorTest` to cover:
- Searching a non-spatial domain through the orchestrator
- Cache hit with normalized key (search "headphones", cache hit on "earphones")
- Subsumption across normalized queries

---

## Not in scope

- **SearchableProvider for non-search platform capabilities** (cart, checkout, file upload, issue creation) — only search methods are wrapped
- **Detail refresh for non-spatial entities** — `refreshStale()` currently only refreshes location details. Non-spatial detail refresh is deferred (track as issue)
- **Query fan-out from ExpandedTerm variants** — normalization is used for cache key canonicalization (treating synonyms as the same cache entry), not for expanding a single query into multiple provider searches (track as issue)
- **PhoneticEntityMatcher for contacts dedup** — phonetic codes are stored in variants but entity matching by phonetic similarity is a follow-up (track as issue)
- **CDI decorators on connector SPIs** — considered and rejected (D1 revision). Normalization is pipeline-only; direct SPI callers bypass it
- **#473 sub-dictionary loading** — #473 also requests configurable sub-dictionary loading (IT/computing, business). This spec implements the general domain portion only. Sub-dictionary loading remains open on #473
- **Decoupling location-specific types from knowledge-pipeline-api** — `CachedEntity` uses `Coordinates` from `connectors-location-spi`, coupling the API module to the location SPI. Cleanup is a separate concern

---

## References

- `knowledge-pipeline/src/main/java/io/casehub/neocortex/knowledge/resolution/SpatialSearchableProvider.java` — pattern to follow
- `knowledge-pipeline-api/src/main/java/io/casehub/neocortex/knowledge/DomainSupport.java` — per-domain bundle
- `knowledge-pipeline/src/main/java/io/casehub/neocortex/knowledge/KnowledgePipelineDefaultBeans.java:123-156` — spatialDomainSupport producer
- `knowledge-pipeline/src/main/java/io/casehub/neocortex/knowledge/KnowledgePipelineOrchestrator.java:247-265` — normalize method
- `knowledge-pipeline/src/main/java/io/casehub/neocortex/knowledge/normalization/WordNetTermNormalizer.java` — existing domain-to-lex mapping
- `knowledge-pipeline/src/main/java/io/casehub/neocortex/knowledge/cache/SqliteSpatialCacheStore.java:265-293` — null coordinate handling
- Issue casehubio/neocortex#473 — general domain for WordNetTermNormalizer
- Issue casehubio/neocortex#459 — SpatialSearchableProvider (closed, pattern established)
- Decision review: `/Users/mdproctor/reviews/casehub-neocortex/issue-480-decision-20261008-151710/responses/reviewer-1.md`
