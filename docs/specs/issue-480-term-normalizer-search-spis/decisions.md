## D1: Normalization architecture — SearchableProvider adapter classes

**Choice:** SearchableProvider adapter classes following the existing SpatialSearchableProvider pattern. The pipeline's domain-neutrality means no infrastructure changes needed — just 4 adapter classes + 4 DomainSupport producers.
**Rationale:** Decision review (R1-02) verified that the knowledge pipeline is already domain-neutral: CachedEntity.coordinates is nullable, KnowledgePipelineOrchestrator uses the generic CacheStore interface, DomainSupport.minimal() provides text-based defaults. Adapter classes are less boilerplate than decorators (4 classes vs 8 classes + pass-through methods) and integrate with the full pipeline (caching, subsumption, dedup, promotion, metrics) without an architectural fork.
**Trade-offs:** Non-spatial results are converted to CachedEntity (properties flattened to Map<String, String>). Consumers searching through KnowledgePipelineService get CachedEntity, not native types. Direct SPI callers bypass normalization (pipeline-only).
**Sources:** SpatialSearchableProvider.java, DomainSupport.minimal(), KnowledgePipelineOrchestrator.java, decision review R1-02/R1-05
**Exploration:** deep-analysis
**Status:** revised (was: CDI decorators at SPI boundary)

## D2: Normalization scope — full per-domain normalization

**Choice:** Full per-domain normalization — WordNet synonyms (via #473 general domain), stemming, and phonetic matching for contacts
**Alternatives:**
- WordNet synonyms only — simpler, but leaves stemming and phonetic matching as future work
- WordNet + stemming — adds stemming but defers phonetic matching for contacts
**Rationale:** The issue lists specific normalization values per domain (synonyms for commerce, phonetic for contacts, stemming for documents/projects). Delivering all three makes the normalization layer immediately useful across all domains rather than partially functional.
**Trade-offs:** Larger scope. Needs a stemming capability (WordNet morphological processor may suffice per R1-08, avoiding a Lucene dependency). Needs a phonetic library (Apache Commons Codec Double Metaphone — better multilingual support than Soundex per R1-08). Each new TermNormalizer implementation is added to the per-domain normalizerChain in DomainSupport.
**Sources:** Issue #480 body (per-domain normalization table), TermNormalizer SPI, WordNetTermNormalizer.java, decision review R1-08
**Exploration:** quick
**Depends on:** D1 (SearchableProvider — normalizers compose via DomainSupport.normalizerChain)
**Status:** captured

## D3: Adapter module placement — in knowledge-pipeline

**Choice:** In knowledge-pipeline module — add the four connector SPI dependencies and place adapter classes alongside SpatialSearchableProvider
**Alternatives:**
- New dedicated module — adds module overhead for 4 small adapter classes
**Rationale:** knowledge-pipeline already depends on connectors-location-spi and owns SpatialSearchableProvider, DomainSupport producers, normalizer wiring. Adding 4 more connector SPI dependencies and adapter classes is consistent with the existing pattern.
**Trade-offs:** knowledge-pipeline gains 4 new compile dependencies on connector SPI modules.
**Sources:** knowledge-pipeline/pom.xml, KnowledgePipelineDefaultBeans.java (spatialDomainSupport producer)
**Exploration:** quick
**Depends on:** D1 (SearchableProvider architecture)
**Status:** captured

## ~~D4: Decorator interception point~~ — WITHDRAWN

Withdrawn. D1 revision to SearchableProvider wrappers eliminates the need for CDI decorators.

## ~~D5: Normalizer chain composition~~ — WITHDRAWN

Withdrawn. With SearchableProvider wrappers, normalizer chains are composed per-domain via DomainSupport.normalizerChain — the existing mechanism. No domain-string dispatch needed.

## D6: Include #473 (general domain for WordNetTermNormalizer) in scope

**Choice:** Implement #473 as part of this branch — add the general domain to WordNetTermNormalizer before wiring adapter classes
**Alternatives:**
- Design without #473 — use empty normalizer chains for non-spatial domains
- Do #473 first on a separate branch — delays this work
**Rationale:** The general domain is a small, focused addition (add lex file mappings to the existing DOMAIN_TO_LEX_FILES map) and is a prerequisite for synonym normalization in all four new domains. Doing it inline ensures the adapter classes have working normalization from day one.
**Trade-offs:** Branch scope grows slightly. #473 will be closed by this branch rather than its own.
**Sources:** Issue #473 body, WordNetTermNormalizer.java:26-30 (DOMAIN_TO_LEX_FILES)
**Exploration:** quick
**Status:** captured
