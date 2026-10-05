# casehub-neocortex — Consumer Guide

> Neural text inference, RAG integration, CBR memory, and agent memory for the casehub platform.

**GitHub:** [casehubio/neocortex](https://github.com/casehubio/neocortex)
**Tier:** Foundation

---

## Purpose

Five related capabilities in one repo:

**Neural Text Inference** — a standalone, general-purpose ONNX inference layer for JVM projects. Zero casehub domain dependencies. Shared with Hortora. Fills the gap LangChain4j leaves: NLI, classification, regression, SPLADE sparse embeddings, cross-encoder reranking, and raw tensor classification.

**RAG Integration** — casehub-specific LangChain4j RAG pipeline wiring. Tenancy-isolated Qdrant corpus storage, hybrid dense+sparse+BM25 search via configurable fusion (RRF, DBSF, CC). Exposes `EmbeddingIngestor` and `CaseRetriever` SPIs for use by engine case steps and the typed fact space. Pre-ingestion dedup gate, retrieval tracking, corrective RAG, cross-encoder reranking, and query expansion.

**CBR Memory** — case-based reasoning with typed feature-vector similarity search over prior cases. `CbrRecordStore` SPI with multiple backends (in-memory, JPA/PostgreSQL, Qdrant). Typed feature values (7 value types, 9 field types), weighted similarity scoring, plan adaptation, ensemble analysis, temporal decay, trust-weighted retrieval, hierarchical scoping, and outcome feedback loops.

**Agent Memory** — queryable, permission-aware, persistent agent memory. `CaseMemoryStore` SPI with multiple backends (in-memory, JPA/PostgreSQL, SQLite, Mem0, Graphiti). Salience-based ranking, confidence-aware retention, fire-and-forget emission via `MemoryEmitter`.

**Knowledge Pipeline** — external data source integration with SQLite-backed spatial cache, query subsumption, cross-source entity resolution, multi-session research orchestration, and entity promotion to MindMap. Fetches from `LocationPlatform` providers (connectors), caches with field-type TTLs, deduplicates across sources, and promotes user-selected entities to durable MindMap knowledge.

---

## Modules to Depend On

### Inference

| Module | artifactId | What you get |
|--------|-----------|-------------|
| `inference-api` | `casehub-neocortex-inference-api` | `InferenceModel` SPI, `InferenceInput` sealed interface (Text + Tensor variants), `MultiModalEmbedder` interface, `EmbeddingMode` enum — pure Java, zero deps |
| `inference-tasks` | `casehub-neocortex-inference-tasks` | `NliClassifier`, `TextClassifier`, `TensorClassifier`, `ScalarRegressor`, `CrossEncoderReranker` |
| `inference-splade` | `casehub-neocortex-inference-splade` | SPLADE sparse embeddings — `SparseEmbedder.embed()` returns `Map<Integer, Float>` |
| `inference-bge-m3` | `casehub-neocortex-inference-bge-m3` | `BgeM3Embedder` — dense + sparse + ColBERT from a single ONNX model run |
| `inference-quarkus` | `casehub-neocortex-inference-quarkus` | CDI wiring, `@InferenceModel` qualifier, Dev Services, `@QuarkusTest` support |
| `inference-inmem` | `casehub-neocortex-inference-inmem` | Deterministic `InferenceModel` stubs — no JNI, safe in all test contexts |

### RAG

| Module | artifactId | What you get |
|--------|-----------|-------------|
| `rag-api` | `casehub-neocortex-rag-api` | `EmbeddingIngestor`, `CaseRetriever`, `RetrievalTracker`, `RelevanceEvaluator`, `QueryExpander`, `QueryExtractionStrategy`, `RetrievalAnalyzer` SPIs; `CaseContextRetriever` (multi-corpus retrieval with dedup and per-corpus error isolation; strategy-driven overload accepts `QueryExtractionStrategy` for domain-specific case context → query extraction) — pure Java |
| `rag` | `casehub-neocortex-rag` | LangChain4j pipeline, Qdrant, three-leg hybrid search, `MatryoshkaEmbeddingModel`, `DenseQuantization`, `DedupEmbeddingIngestor`, `PayloadBoostCaseRetriever` |
| `rag-tika` | `casehub-neocortex-rag-tika` | Apache Tika document parser — extracts text + metadata from binary documents (PDF, DOCX) for RAG ingestion |
| `rag-crossencoder` | `casehub-neocortex-rag-crossencoder` | Corrective RAG quality-gating + cross-encoder reranking. Config-gated decorators |
| `rag-expansion` | `casehub-neocortex-rag-expansion` | Query expansion — HyDE, step-back, multi-query fan-out with RRF fusion. Config-gated decorator |
| `rag-tracking` | `casehub-neocortex-rag-tracking` | SQLite-backed retrieval tracking with retention scheduling. Config-gated decorator |
| `rag-testing` | `casehub-neocortex-rag-testing` | In-memory stubs: `EmbeddingIngestor`, `CaseRetriever`, `CursorStore`, `RetrievalTracker`, `RelevanceEvaluator` for `@QuarkusTest` |

### Fusion

| Module | artifactId | What you get |
|--------|-----------|-------------|
| `fusion-api` | `casehub-neocortex-fusion-api` | `FusionStrategy` enum, `ScoreFusion` utility (RRF + CC), `CamelCaseExpander` — pure Java, zero deps. Shared by RAG and CBR |

### Agent Memory

| Module | artifactId | What you get |
|--------|-----------|-------------|
| `memory-api` | `casehub-neocortex-memory-api` | `CaseMemoryStore`, `GraphCaseMemoryStore` SPIs, `DelegatingCaseMemoryStore` (forwarding base for decorators), `MemoryOrder` (CHRONOLOGICAL, RELEVANCE, SALIENCE), `MemoryInput` with confidence, `MemoryRetentionPolicy`, `MemoryScanRequest` — pure Java |
| `memory` | `casehub-neocortex-memory` | CDI wiring, `MemoryEmitter` fire-and-forget wrapper, `CaseEnrichmentDecorator`, `MemoryRetentionScheduler` |
| `memory-inmem` | `casehub-neocortex-memory-inmem` | In-memory volatile backend — test + ephemeral |
| `memory-jpa` | `casehub-neocortex-memory-jpa` | PostgreSQL + Flyway + FTS via `websearch_to_tsquery` |
| `memory-sqlite` | `casehub-neocortex-memory-sqlite` | SQLite + HikariCP WAL + FTS5 |
| `memory-mem0` | `casehub-neocortex-memory-mem0` | Mem0 REST adapter — vector embeddings + semantic search |
| `memory-graphiti` | `casehub-neocortex-memory-graphiti` | Graphiti REST adapter — temporal knowledge graph |
| `memory-spring` | `casehub-neocortex-memory-spring` | Spring Boot auto-configuration for memory core beans (enrichment, retention, CBR runtime) |
| `memory-spring-jpa` | `casehub-neocortex-memory-spring-jpa` | Spring Data JPA `CaseMemoryStore` — PostgreSQL + Flyway + FTS. Configure: `casehub.memory.jpa.fts.enabled` (default `true`), `casehub.memory.jpa.fts.language` (default `english`) |
| `memory-testing` | `casehub-neocortex-memory-testing` | Test stubs for memory SPIs |

### CBR Memory

| Module | artifactId | What you get |
|--------|-----------|-------------|
| `memory-api` | `casehub-neocortex-memory-api` | `CbrRecordStore` SPI, typed feature values, field schema, similarity specs, `CbrCbrPlanAdapter`, `CbrCbrPlanEnsembleAnalyzer`, `AgentTrustProvider`, `CbrRetrievalTracker`, `PersonalityTransitionSchema` — pure Java |
| `memory` | `casehub-neocortex-memory` | CBR CDI decorator chain — outcome weighting, trust-weighted retrieval, scope decay, temporal decay, trend enrichment, erasure notification. `CbrRetentionScheduler`, `TrustRetentionService`, `CbrOutcomeConsumer` |
| `memory-cbr-inmem` | `casehub-neocortex-memory-cbr-inmem` | In-memory CBR case store for tests |
| `memory-cbr-jpa` | `casehub-neocortex-memory-cbr-jpa` | JPA/PostgreSQL CBR store with JSONB features, plan traces, outcome tracking |
| `memory-qdrant` | `casehub-neocortex-memory-qdrant` | Qdrant vector store backend + multi-leg hybrid fusion + `CbrReconciliationService` |
| `memory-cbr-embedding` | `casehub-neocortex-memory-cbr-embedding` | `EmbeddingTextSimilarity` — LangChain4j `EmbeddingModel`-based semantic text similarity for CBR fields |
| `memory-cbr-crossencoder` | `casehub-neocortex-memory-cbr-crossencoder` | Cross-encoder reranking for CBR retrieval. Config-gated decorator |
| `memory-cbr-spring-jpa` | `casehub-neocortex-memory-cbr-spring-jpa` | Spring Data JPA `CbrRecordStore` — PostgreSQL + Flyway, shared filter matching via `CbrRecordFilterMatcher` |
| `memory-cbr-tracking` | `casehub-neocortex-memory-cbr-tracking` | SQLite-backed CBR retrieval tracking + plan adaptation tracking + ensemble tracking |

### Knowledge Model

| Module | artifactId | What you get |
|--------|-----------|-------------|
| `thing-api` | `casehub-neocortex-thing-api` | `Thing` interface — id, name, type, properties, traits, `is()`/`as()`. Zero deps. Consumer-facing module |
| `cognitive-api` | `casehub-neocortex-cognitive-api` | `Confidence` record, `ConfidenceOrigin` enum, `TemporalMark` sealed hierarchy — cross-cutting cognitive types. Zero deps |
| `mindmap-api` | `casehub-neocortex-mindmap-api` | `MindMapStore` SPI, `MindMapNode` (extends Thing with confidence, PAD, temporal bounds), `MindMapQuery`, `SubgraphTypes`, `SchemaField`, `NodeRef`, `EdgeTypeDefinition`, `ActivityVocabulary` |
| `mindmap` | `casehub-neocortex-mindmap` | CDI wiring, `ConfidenceDecayDecorator`, `DerivedEdgeDecorator`, `MindMapAnalyzer` graph analytics |
| `mindmap-inmem` | `casehub-neocortex-mindmap-inmem` | In-memory `MindMapStore` for tests |
| `mindmap-sqlite` | `casehub-neocortex-mindmap-sqlite` | SQLite + HikariCP WAL + FTS5 — production backend for single-node deployments |
| `mindmap-intelligence` | `casehub-neocortex-mindmap-intelligence` | `TypeRegistry`, trait interfaces (`Personable`, `Projectlike`, `Organisational`, `Eventlike`, `Locatable`, `Reviewable`, `Temporal`), `TraitRule` implementations, `MindMapExtractor` (parse/apply decomposition), `ConversationBridge` (principalId + confidence params), `CheckInService` (activity recording), `ActivityQueryService` (CRM queries) |
| `mindmap-testing` | `casehub-neocortex-mindmap-testing` | `MindMapStoreContractTest` abstract base (72 tests) |

### Knowledge Pipeline

| Module | artifactId | What you get |
|--------|-----------|-------------|
| `knowledge-pipeline-api` | `casehub-neocortex-knowledge-pipeline-api` | `KnowledgePipelineService` SPI (search + promote), `SpatialCacheStore` SPI, `EntityMatcher<T>` SPI, `SubsumptionRule` SPI, `QueryNormalizer` SPI, `ResearchSessionService` SPI, `KnowledgeQuery` sealed hierarchy (TextSearch, NearbySearch, CategorySearch), value types |
| `knowledge-pipeline` | `casehub-neocortex-knowledge-pipeline` | CDI-ready runtime — `KnowledgePipelineOrchestrator`, `SqliteSpatialCacheStore` (R*Tree), `InMemorySpatialCacheStore` (@Alternative), `SpatialSubsumptionRule`, `EntityResolutionEngine`, `PlaceMatcher`, `EntityPromoter`, `ResearchOrchestrator`, `CacheEvictionScheduler` (@Scheduled via `CacheEvictionTask`), `CacheDecayPolicy`, `KnowledgePipelineMetrics` (Micrometer), `KnowledgePipelineConfig` (@ConfigMapping `casehub.knowledge.*`), `KnowledgePipelineDefaultBeans` (CDI producers). Auto-discovers `LocationPlatform` providers via `Instance<LocationPlatform>`. |

### Cognitive Index

| Module | artifactId | What you get |
|--------|-----------|-------------|
| `cognitive-index` | `casehub-neocortex-cognitive-index` | `TemporalIndex` (cross-store chronological aggregation), `CognitiveProfile` (entity resolution + multi-agent comparison), `SocialComparison` (perspectival divergence metrics), `DomainActivation` (cross-domain DTW correlation + mood/experience context correlation), `CognitiveDefaultsRegistry` (YAML-driven per-agent config), `CognitiveProfileWatcher` (file-watch hot-reload for profiles + rules via `casehub.cognitive.profiles-dir` / `casehub.cognitive.rules-dir`) |
| `schema-generator` | `casehub-neocortex-schema-generator` | JSON Schema generation (Draft 2020-12) for cognitive types — sealed hierarchy `oneOf`, enum inlining, shorthand patterns, YAML output |

### Corpus

| Module | artifactId | What you get |
|--------|-----------|-------------|
| `corpus-api` | `casehub-neocortex-corpus-api` | `CorpusStore`, `CorpusReader`, `ChangeSource` SPIs — pure Java, zero deps |
| `corpus` | `casehub-neocortex-corpus` | Zip, flat filesystem, and composite implementations |

---

## Key Abstractions

### InferenceModel / Task Adapters

`InferenceModel` SPI runs any ONNX model. `InferenceInput` is a sealed interface with two variants:
- `InferenceInput.Text` — tokenized text input (single text or text pair)
- `InferenceInput.Tensor` — raw named float tensors (bypasses tokenization)

Callers work through typed task adapters in `inference-tasks`, never raw tensors.

| Adapter | Input type | Model type | Use case |
|---------|-----------|-----------|----------|
| `NliClassifier` | Text pair | NLI | Hallucination detection — scores LLM output faithfulness against facts |
| `TextClassifier` | Text | Classification | Action risk classification in casehub-openclaw |
| `TensorClassifier` | Tensor | Classification | Multi-dimensional tensor classification with softmax + configurable labels. Used by strategy classifier (#76) |
| `ScalarRegressor` | Text pair | Regression | Epistemic domain confidence estimation in casehub-eidos |
| `CrossEncoderReranker` | Text pair | Cross-encoder | Precision-mode reranking — top-N from top-K candidates |

### SparseEmbedder (inference-splade)

`SparseEmbedder.embed(String text)` returns `Map<Integer, Float>` — sparse term weights after log-saturation (`log(1 + relu(weight))`) and threshold filtering. Output is suitable for direct Qdrant named vector space upsert. Forms the sparse leg of hybrid search.

### EmbeddingIngestor / CaseRetriever (rag-api)

`EmbeddingIngestor` — ingest pre-chunked text into vector store (embedding + storage). Tenancy-scoped via `CorpusRef` (tenant ID + corpus name).

`CaseRetriever` — retrieval entry point for case steps and the fact space. `retrieve(query, CorpusRef)` returns `List<RetrievedChunk>`. Hybrid search: dense + sparse + BM25 fused via configurable `FusionStrategy`.

### Pre-Ingestion Dedup Gate (rag)

`DedupEmbeddingIngestor` — CDI Decorator (Priority 50) on `EmbeddingIngestor`. Before indexing, embeds each chunk and queries Qdrant for existing near-duplicates via cosine similarity. Chunks exceeding the threshold (default 0.95) are skipped. Config: `casehub.rag.ingestion.dedup.enabled` (default `true`), `casehub.rag.ingestion.dedup.threshold` (default `0.95`).

### RetrievalQuery and Per-Query Weight Multipliers (rag-api)

`RetrievalQuery` carries `text`, optional `expandedText` (from query expansion), and `weightMultipliers` — a `Map<String, Double>` of per-leg weight overrides for this specific query. `searchText()` returns `expandedText` when present, `text` otherwise. Dense leg uses `searchText()`; sparse and BM25 legs use `text()`.

Convenience: `withBm25Boost(double)` sets the BM25 multiplier, `withWeightMultiplier(leg, multiplier)` sets any leg. `HybridCaseRetriever.effectiveWeight()` combines global `FusionWeightsConfig` with per-query multipliers.

### MatryoshkaEmbeddingModel (rag)

Truncating `EmbeddingModel` decorator. Takes a delegate model and `targetDimension`, truncates to the first N dimensions and L2-renormalizes. Config-driven: active when `casehub.rag.matryoshka.dimension` is set. `dimension()` returns the truncated size, flowing transparently to `ensureCollection()`.

### Configurable Fusion Strategy (fusion-api, rag)

`FusionStrategy` enum — `RRF` (Reciprocal Rank Fusion), `DBSF` (Distribution-Based Score Fusion), `CC` (Convex Combination). `ScoreFusion` utility implements RRF and CC algorithms with `ScoredLeg`/`FusedResult` records. Config: `casehub.rag.retrieval.fusion-strategy` (default `RRF`).

`FusionWeightsConfig` — unified per-leg weight configuration replacing the former `CcWeightsConfig`. Covers `dense`, `sparse`, `bm25`, and `quality` weights (default 1.0 each). CC uses these directly; weighted RRF auto-falls back to client-side when weights are non-equal. `PayloadBoostCaseRetriever` (Decorator Priority 60) applies the quality weight as post-fusion rescore for RRF/DBSF (CC integrates quality as a fusion leg natively).

### RelevanceEvaluator / ColBERT Relevance (rag-api, rag-crossencoder)

`RelevanceEvaluator` SPI — `evaluateChunks(query, chunks)` returns `List<ScoredGrade>` mapping each chunk to a `RelevanceGrade` (CORRECT, AMBIGUOUS, INCORRECT) with a score.

Two implementations:
- `CrossEncoderRelevanceEvaluator` in `rag-crossencoder` — uses ONNX cross-encoder model
- `ColBertRelevanceEvaluator` in `rag-api` — pure Java score-threshold mapper reading `relevanceScore` from chunks. `calibrate()` factory derives thresholds from sample score distributions at configurable percentiles

### RetrievalAnalyzer (rag-api)

Static utility for analytics over retrieval tracking data. Pure computation — no I/O:

- **Document-level:** `documentStats()` — retrieval count, average score, outcome distribution per document
- **Query-level:** `lowRelevanceQueries()`, `zeroHitQueries()`, `queryFrequency()`
- **Quality signals:** `qualitySignals()` — identifies underperforming documents via configurable thresholds
- **Correlation:** `correlationGraph()` — bipartite query-to-document graph with `EdgeStats` (co-occurrence, average score, outcome distribution). `queryClusters()` — single-linkage Jaccard clustering (MinHash LSH for n > 50 queries, brute-force below). `documentImpact()` — centrality ranking with outcome aggregation

### MultiModalEmbedder / BgeM3 (inference-api, inference-bge-m3)

`MultiModalEmbedder` interface produces all three embedding modes (dense, sparse, ColBERT) from a single model. `embed(String text)`, `embed(Map<EmbeddingMode, String>)`, and `embedSeparate(Map<EmbeddingMode, String>)` for per-leg embedding with different texts. `BgeM3Embedder` implements this for BGE-M3 ONNX models. `SeparateModelEmbedder` in `rag/` bridges LangChain4j `EmbeddingModel` + optional `SparseEmbedder` into the same contract — `@DefaultBean` displaced by BgeM3 when configured.

### CaseMemoryStore (memory-api)

Queryable, permission-aware, persistent memory. Key operations:
- `store(MemoryInput)` — store with optional `Confidence` field (origin + value [0.0-1.0])
- `query(MemoryQuery)` — retrieve with `MemoryOrder` ranking (CHRONOLOGICAL, RELEVANCE, SALIENCE)
- `erase(EraseRequest)`, `eraseEntity()`, `eraseById()`, `eraseEntityAcrossTenants()` — GDPR-compliant deletion
- `scan(MemoryScanRequest)` — paginated admin scan
- `purge(MemoryRetentionPolicy)` — confidence-based retention purge
- `discoverTenants()` — cross-tenant admin operation

`MemoryOrder.SALIENCE` — recency x confidence query-time scoring. Non-semantic adapters compute salience from `createdAt` and `confidence`; semantic adapters fall back to RELEVANCE.

`MemoryRetentionScheduler` — scheduled confidence-based purge across discovered tenants. Config-driven: `casehub.memory.retention.enabled`, `casehub.memory.retention.min-confidence`, `casehub.memory.retention.max-age-days`.

### CbrRecordStore (memory-api)

Structured feature-vector similarity search over past cases. Open `CbrRecord` type hierarchy with `recordType()` discriminator: `CbrGuidanceRecord` (with optional `features` and structured `CbrCbrGuidanceStep` list), `CbrFeatureRecord`, `CbrPlanRecord`.

**Typed feature values:** `FeatureValue` sealed interface with seven value types: `StringVal`, `NumberVal`, `RangeVal`, `StringListVal`, `NumberListVal`, `StructVal`, `StructListVal`. Booleans coerced via `FeatureValue.of(Object)`.

**Feature field schema:** `FeatureField` sealed interface with nine permits: `Categorical`, `Numeric`, `Text` (with `semantic` flag), `CategoricalList`, `NumericList`, `NestedObject`, `ObjectList`, `TimeSeries`, `DiscreteSequence`.

**Similarity scoring:** `CbrSimilarityScorer` — pure-Java weighted composite scoring with three-level precedence: caller override, field `SimilaritySpec`, type default. `SimilaritySpec` sealed interface: `CategoricalTable`, `GaussianDecay`, `StepDecay`, `ExponentialDecay`, `DtwSpec`, `EditDistanceSpec`.

**Cross-type retrieval:** `CaseTypeScope` sealed interface — `Specific(caseType)` for single-type queries, `AllInDomain()` for cross-type. `CbrQuery.crossType(tenantId, domain, scope, features, topK)` factory. Results carry `CbrMatch.caseType()` for type identification. Qdrant backend fans out across all collections matching the prefix.

**Retrieval modes:** `CbrQuery.RetrievalMode` — `FEATURE_ONLY`, `SEMANTIC_ONLY`, `HYBRID`. `FusionStrategy` from `fusion-api` for result merging.

**Hierarchical scoping:** `CbrQuery.scope` (required `Path`) for hierarchical visibility. `ScopeDecay` sealed interface (Exponential, Linear, Step) for scope-distance score decay.

**Temporal decay:** `TemporalDecay` sealed interface (HalfLife, Linear, Step) for smooth recency decay applied post-scoring.

**Filters:** `CbrFilter` sealed interface — `Contains`, `ContainsAll`, `ContainsAny`, `NotContains`, `NotContainsAny`, `ContainsRange`, `HasMatch`, `AllOf`.

**Supersession:** `supersede(caseId, tenantId, supersedingCaseId, reason)` and `reinstate(caseId, tenantId)`. `getSupersessionStatus()` and `findSupersededCases()` for audit.

**Outcome feedback:** `recordOutcome(CbrOutcome)` — CBR Revise feedback loop with EMA confidence adjustment.

**Retrieval feedback:** `CbrRetrievalTracker.feedback(traceId, List<CbrRetrievalFeedback>)` — per-result relevance signals with `CbrFeedbackOutcome` (RELEVANT, NOT_RELEVANT, PARTIALLY_RELEVANT, HIGHLY_RELEVANT, OUTDATED). Independent of rag-api's `RetrievalOutcome`.

**Retention:** `purge(CbrRetentionPolicy)` — age + count + trust-based purge. `CbrRetentionScheduler` for scheduled purging. `TrustRetentionService` — evaluates agent trust trajectories via `AgentTrustProvider` and purges cases below `minCurrentTrust`.

**Scan:** `scan(CbrScanRequest)` — paginated scan with tenant/domain/caseType filtering. Returns `List<CbrRecordSummary>` (caseId, entityId, caseType, producerAgentId, trustScore, storedAt).

### CbrPlanAdapter / CbrPlanEnsembleAnalyzer (memory-api)

`CbrCbrPlanAdapter` SPI — transforms retrieved plans for new case contexts. `adapt(caseType, CbrMatch<PlanCbrRecord>, features)` returns `AdaptedPlan` with `AdaptedStep` entries tagged by `AdaptationAction` (RETAINED, SUBSTITUTED, BOOSTED, SUPPRESSED, ADDED, REMOVED). `CbrPlanStep` records audit data with optional `variantId`.

`CbrCbrPlanEnsembleAnalyzer` SPI — cross-plan structural analysis. After per-plan adaptation, examines multiple adapted plans for consensus/divergence and synthesizes an `EnsemblePlan`. `StepConsensus` classifies agreement as UNANIMOUS, CONSENSUS, CONTESTED, MINORITY, or UNIQUE.

### Trust-Weighted Retrieval (memory)

`TrustWeightedCbrRecordStore` (Decorator Priority 60) — modulates retrieval scores by source trust authority + optional trust trajectory via `AgentTrustProvider` SPI. `TrustWeightingFunction` SPI for pluggable score modulation. Default: linear interpolation `score*(1-alpha+alpha*trustScore)` with declining trajectory penalty.

Config-gated: `casehub.cbr.trust-weighting.enabled`, `casehub.cbr.trust-weighting.influence` (default 0.3).

### PersonalityTransitionSchema (memory-api)

Built-in CBR schema for personality evolution memory. Records when an agent's cognitive function profile shifts (e.g. dominant Ti to Fe after JPAF reflection). Case type: `personality-transition`. Features: `agent_id`, `old_dominant`, `new_dominant`, `old_auxiliary`, `new_auxiliary`, `trigger_type`, `outcome`.

### Corpus Ingestion Bridge (rag)

Config-driven bridge that populates a RAG corpus from external sources. `CorpusIngestionService` orchestrates both event-driven ingestion (directory-watcher for filesystem corpora) and scheduled polling (for ZIP-based corpora). `MetadataExtractor` SPI extracts body + metadata from document content. `CursorStore` SPI provides pluggable cursor persistence for incremental polling.

### Thing — The Universal Entity Base (thing-api)

Every entity in the knowledge graph is a `Thing`. The interface provides identity, properties, traits, and a dynamic type system with `instanceof`-style checking and typed property access via JDK Proxy.

```java
Thing entity = store.getNode(nodeId, tenantId);
entity.id();               // unique identifier
entity.name();             // "Emily"
entity.type();             // "person" — derived from subgraph membership
entity.properties();       // {role: "mum", email: "emily@example.com"}
entity.traits();           // {"Personable"}
entity.is("person");       // true — creation type
entity.is("Personable");   // true — trait type
entity.is("project");      // false — neither
```

`thing-api` has zero dependencies. App builders depend on `thing-api` for entity access without pulling in MindMap internals. `MindMapNode extends Thing` — any MindMapNode can be used wherever a Thing is expected.

### is() / as() — Dynamic Type Checking and Typed Access (thing-api)

`is()` checks both the entity's creation type (from subgraph membership) and its trait set. `as()` creates a JDK Proxy that maps interface method names to `property(methodName)` calls:

```java
if (emily.is("Personable")) {
    Personable p = emily.as(Personable.class);
    p.role();     // Optional.of("mum")
    p.email();    // Optional.of("emily@example.com")
    p.birthday(); // Optional.empty() — not set yet
}
```

Return types are coerced automatically:

| Return type | Behaviour |
|-------------|----------|
| `Optional<String>` | `Optional.ofNullable(property value)` |
| `String` | value or `null` |
| `int`, `long`, `double`, `boolean` | parsed, or type default (0, 0L, 0.0, false) |
| `Integer`, `Long`, `Double`, `Boolean` | parsed, or `null` |

### Custom Trait Interfaces (thing-api)

`as()` works with any interface — no platform dependency required. Method names map to property keys:

```java
interface PartyGuest {
    Optional<String> dietary();
    Optional<String> rsvpStatus();
}

PartyGuest guest = john.as(PartyGuest.class);
guest.dietary();    // Optional.of("nut allergy")
guest.rsvpStatus(); // Optional.empty()
```

The platform provides `Personable`, `Projectlike`, `Organisational`, `Eventlike`, `Locatable`, `Reviewable`, and `Temporal` in `mindmap-intelligence`. These are conveniences — consumers define domain-specific traits the same way.

**Convention:** trait names are PascalCase (matching Java interface simple names). Type names are lowercase. Traits come from code; types come from data.

### Types and SubgraphTypes (mindmap-api)

Entity types are dynamic strings, not a fixed enum. A node's type comes from its subgraph membership — a node in a "person" subgraph has type `"person"`.

Well-known types are constants in `SubgraphTypes`:

| Constant | Value |
|----------|-------|
| `PERSON` | `"person"` |
| `PROJECT` | `"project"` |
| `RESEARCH_AREA` | `"research-area"` |
| `ORGANISATION` | `"organisation"` |
| `CONCEPT` | `"concept"` |
| `GENERAL` | `"general"` |
| `TYPE_SYSTEM` | `"type-system"` |
| `ACTIVITY` | `"activity"` |
| `PLACE` | `"place"` |

The LLM can discover new types at runtime without recompilation:

```java
// Well-known type
store.createSubgraph(new SubgraphInput("People", SubgraphTypes.PERSON, null), tenant);

// LLM-discovered type — works identically
store.createSubgraph(new SubgraphInput("Emily's Party", "birthday-party", null), tenant);
```

Type strings are lowercase-normalized in `SubgraphInput`'s constructor — `"Person"`, `"PERSON"`, and `"person"` all resolve to `"person"`.

### TypeRegistry (mindmap-intelligence)

Types are first-class data stored as nodes in a `TYPE_SYSTEM` subgraph. `TypeRegistry` mediates type operations:

```java
@Inject TypeRegistry registry;

registry.typeExists("person", tenant);          // true — core type
registry.javaClass("person", tenant);           // Optional.of(Personable.class)

registry.registerType("birthday-party", "general", tenant);
registry.subtypesOf("general", tenant);         // [..., "birthday-party"]
registry.javaClass("birthday-party", tenant);   // Optional.empty() — no Java interface yet

Map<String, SchemaField> schema = registry.schemaFor("person", tenant);
// {birthday: SchemaField(name=birthday, type=string, required=false),
//  role:     SchemaField(name=role, type=string, required=false), ...}
```

Core types derive their schema from Java trait interfaces via reflection. Dynamic types start with no schema — as the LLM discovers consistent property patterns, schema can be added to the type node. Schema validation is advisory — it documents expectations but doesn't reject novel properties.

The type hierarchy is graph-native. `subtype-of` edges between type nodes. Hierarchy queries are graph traversal. Adding a type is adding a node.

### MindMapNode — Cognitive Extension of Thing (mindmap-api)

`MindMapNode extends Thing`, adding cognitive features for reasoning:

| Field | Purpose |
|-------|---------|
| `confidence()` | Epistemic certainty — `Confidence(origin, value, decayReference)` with `ConfidenceOrigin` (STATED/INFERRED/SPECULATED/UNKNOWN) |
| `pleasure()`, `arousal()`, `dominance()` | PAD emotional dimensions — how the agent feels about this entity |
| `validFrom()`, `validUntil()` | Temporal validity — when this knowledge applies |
| `provenance()` | Where this knowledge came from |
| `refs()` | External references via `NodeRef(scheme, id, qualifier)` |
| `principalId()`, `sharedWith()` | Visibility controls — who can see this node |

Consumers who only need entity access depend on `thing-api`. The cognitive machinery lives in `mindmap-api` and is relevant when building reasoning or agent subsystems.

### Subject Bridge — Cross-Store Entity References (memory-api)

`Subject(String type, String id)` references a Thing by convention. Same type string, same id — no code dependency between modules:

```java
Subject ref = Subject.of("person", emilyId);

Thing resolved = store.getNode(ref.id(), tenantId);
assert resolved.type().equals(ref.type()); // both "person"
```

Memories stored via `CaseMemoryStore` can reference MindMap entities through `Subject` without coupling `memory-api` to `mindmap-api`. The Subject type is lowercase-normalized to match `SubgraphInput`'s convention.

### Knowledge Lifecycle

Knowledge evolves through phases — from raw conversation to structured, retrievable entities:

```
Notes ──→ Entities ──→ Traits ──→ Types
(prose)   (extracted)   (discovered) (named)
```

| Phase | What happens | Speed |
|-------|-------------|-------|
| **Notes** | Conversation captured as "general" nodes with freeform properties | Real-time (during conversation) |
| **Extraction** | Entities identified, typed, and connected with edges | Near-time (after conversation) |
| **Trait discovery** | `TraitRule` implementations evaluate nodes — matching properties/edges assign traits | Near-time |
| **Type registration** | Recurring entity patterns registered as named types via `TypeRegistry` | Background |

In production, the `ConversationBridge` handles real-time capture (creating initial "general" nodes), and the `ConsolidationScheduler` runs near-time and background phases automatically via a four-phase pipeline: access-frequency tracking, merge detection, community summaries, and curiosity refresh.

**The promotion path:** when a dynamic type crystallises — stable schema, frequently queried, consistent properties — a developer creates a Java trait interface for it. The type node gains a `java-class` property, and consumers get typed access via `as()`.

### CognitiveProfile — Entity Resolution and Multi-Agent Comparison (cognitive-index)

`CognitiveProfile` resolves a unified `EntityKnowledge` record for a single entity across MindMap + Memory stores. Configurable domain set, edge inclusion, memory limit.

**Single-entity resolution:**

```java
@Inject CognitiveProfile profile;

// Shared view (no perspective)
var query = CognitiveProfileQuery.byId(nodeId, tenantId);
Optional<EntityKnowledge> ek = profile.resolve(query);

// Perspectival view — applies agent's overlay before trajectory computation
var query = CognitiveProfileQuery.byId(nodeId, tenantId)
    .withAsSeenBy(PrincipalId.agent("alice"));
Optional<EntityKnowledge> ek = profile.resolve(query);
// ek.get().perceiver() == alice
// ek.get().node().pleasure() reflects alice's overlay PAD
// ek.get().trajectory() computed from alice's principal-scoped affect memories
```

When `asSeenBy` is set, perspective is applied before any derived computation — the overlay merges onto the shared node before trajectory analysis, and memory queries are scoped to the requesting agent via `withCallerPrincipalId`.

**Multi-agent comparison:**

```java
Map<PrincipalId, EntityKnowledge> views = profile.compare(
    CognitiveProfileQuery.byId(nodeId, tenantId),
    Set.of(PrincipalId.agent("alice"), PrincipalId.agent("bob")));
// Single overlay scan for all agents — each gets perspectival node + scoped memories
```

### SocialComparison — Perspectival Divergence Metrics (cognitive-index)

Pure static utility for computing divergence between agents' perspectives on the same entity. Takes the output of `CognitiveProfile.compare()`:

```java
Map<PrincipalId, EntityKnowledge> views = profile.compare(query, agents);
PerspectivalComparison result = SocialComparison.compare(views);

// PAD distance matrix — pairwise Euclidean distances
double dist = result.distances().distance(alice, bob);

// Per-dimension signed differences
double pleasureDiff = result.dimensionDifferences()
    .get(PadDimension.PLEASURE).difference(alice, bob);

// Trajectory alignment — 3D slope vector cosine similarity
TrendAgreement agreement = result.trajectoryAlignment()
    .agreements().get(AgentPair.of(alice, bob));
// ALIGNED, DIVERGENT, MIXED, or INSUFFICIENT
```

Agents with any null PAD dimension are excluded from distance/difference computations and listed in `unassessedAgents`. Trajectory alignment is computed independently — agents with trajectory data but null PAD still participate.

### DomainActivation — Cross-Domain Correlation (cognitive-index)

CDI bean for correlating affect signals across life domains (subgraphs). Uses Dynamic Time Warping on time-bucketed 3D PAD time series to detect cross-domain emotional patterns. Optionally correlates agent mood and experience events against per-subgraph affect trajectories.

```java
@Inject DomainActivation domainActivation;

// Affect-only correlation (existing)
var query = DomainActivationQuery.between(
    PrincipalId.agent("alice"), tenantId, workSubgraphId, familySubgraphId)
    .withFrom(windowStart)
    .withTo(windowEnd)
    .withBucketDuration(Duration.ofHours(24));

Optional<DomainActivationResult> result = domainActivation.correlate(query);
// result.get().correlations() — pairwise DTW similarity per DomainPair
// result.get().domains() — per-subgraph DomainSignal (trajectory, entity/memory counts)

// With mood + experience context correlation (opt-in)
var contextQuery = DomainActivationQuery.between(
    PrincipalId.agent("alice"), tenantId, workSubgraphId, familySubgraphId)
    .withFrom(windowStart).withTo(windowEnd)
    .withContextDomains(Set.of(MoodEvents.DOMAIN, ExperienceEvents.DOMAIN))
    .withEventWindow(Duration.ofDays(1));

Optional<DomainActivationResult> contextResult = domainActivation.correlate(contextQuery);
// contextResult.get().contextCorrelations() — mood ↔ affect DTW per subgraph (with pValue)
// contextResult.get().eventImpacts() — experience → affect Δ(PAD) per subgraph per event type
```

Privacy by construction — `PrincipalId` is required and non-nullable. Only the specified agent's affect memories are queried. Returns `Optional.empty()` when any subgraph has zero entities or zero affect memories. Context correlations degrade gracefully — empty maps when no mood/experience data exists.

---

## Relationship to LangChain4j

This module sits **below** LangChain4j for inference, and **above** LangChain4j for RAG:

| Capability | Where it lives |
|---|---|
| Dense float-vector embeddings | LangChain4j `OnnxEmbeddingModel` |
| RAG pipeline, chunking, vector stores | LangChain4j |
| Sparse embeddings (SPLADE) | `inference-splade` (this module) |
| Multi-modal embeddings (dense+sparse+ColBERT) | `inference-bge-m3` (this module) |
| NLI, classification, regression | `inference-tasks` (this module) |
| Tensor classification (softmax + labels) | `inference-tasks` (this module) |
| Cross-encoder reranking | `inference-tasks` + `rag-crossencoder` (this module) |
| Score fusion algorithms (RRF, CC) | `fusion-api` (this module) — pure Java, zero deps |
| BM25 text retrieval | `rag` (this module) — in-memory inverted index, third retrieval leg |
| casehub-specific RAG wiring + tenancy | `rag` / `rag-api` (this module) |
| Matryoshka dimension reduction + L2 renorm | `rag` (this module) — decorator above LangChain4j `EmbeddingModel` |
| Dense + ColBERT vector quantization + oversampling | `rag` (this module) — Qdrant collection config + search params |
| Pre-ingestion dedup gate | `rag` (this module) — cosine similarity check before indexing |
| Retrieval analytics (document stats, query clusters, correlation) | `rag-api` (this module) — pure computation over tracker data |
| CBR typed feature similarity (DTW, edit distance, decay) | `memory-api` (this module) |
| Retrieval tracking + feedback measurement | `rag-tracking` + `memory-cbr-tracking` (this module) |

---

## Shared with Hortora

`inference-api`, `inference-runtime`, `inference-tasks`, `inference-splade`, `inference-inmem` have zero casehub/Quarkus/LangChain4j dependencies. Hortora depends on these directly and wires them into their own stack.

`rag-api`, `rag`, and `rag-testing` are also consumed by Hortora — Hortora's garden retrieval engine uses these modules for Qdrant/ingestion. Tenancy enforcement is optional: active when `CurrentPrincipal` is on the classpath, no-ops when absent via `TenantGuard`.

ArchUnit enforced from day one: zero-domain-dep constraint on all `inference-*` modules.

---

## Native Image — JVM Mode by Design

The inference service is long-running — native image's fast startup provides no benefit, and HotSpot's JIT optimisation outperforms AOT for sustained workloads. `inference-*` modules operate in JVM mode.

The C2 native image gate passed (ONNX Runtime JNI + HuggingFace Tokenizers JNI both work in Quarkus native image on macOS ARM). Reachability metadata ships in `inference-quarkus` for downstream consumers that distribute as native binaries.

---

## Configuration

Properties marked with `†` are **build-time** (`@IfBuildProperty`) — they gate CDI bean activation and cannot be changed at runtime. All others are runtime properties. For the full reference including internal tuning parameters, see the [contributor guide](contributor-guide.md#configuration-reference).

### Inference

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.inference.models.<name>.model-path` | *(required)* | Path to ONNX model file |
| `casehub.inference.models.<name>.tokenizer-path` | *(required)* | Path to HuggingFace tokenizer JSON |
| `casehub.inference.models.<name>.max-sequence-length` | `512` | Maximum input token length |

### RAG — Connection and Storage

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.qdrant.host` | `localhost` | Qdrant gRPC host |
| `casehub.rag.qdrant.port` | `6334` | Qdrant gRPC port |
| `casehub.rag.qdrant.api-key` | — | Qdrant API key |
| `casehub.rag.qdrant.use-tls` | `false` | Enable TLS for Qdrant connection |
| `casehub.corpus.corpora.<name>.source` | *(required)* | Filesystem path to corpus source |
| `casehub.corpus.corpora.<name>.mode` | `FLAT` | Storage mode: `FLAT` or `ZIP` |

### RAG — Retrieval

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.retrieval.fusion-strategy` | `RRF` | Fusion strategy: `RRF`, `DBSF`, or `CC` |
| `casehub.rag.retrieval.weights.dense` | `1.0` | Dense leg weight for fusion |
| `casehub.rag.retrieval.weights.sparse` | `1.0` | Sparse leg weight for fusion |
| `casehub.rag.retrieval.weights.bm25` | `1.0` | BM25 leg weight for fusion |
| `casehub.rag.retrieval.weights.quality` | `0.0` | Quality/payload boost weight |
| `casehub.rag.bm25-enabled` | `true` | Enable BM25 as third retrieval leg |
| `casehub.rag.matryoshka.dimension` | — | Matryoshka truncation dimension (disabled if unset) |
| `casehub.rag.quantization.type` | `NONE` | Dense quantization: `NONE`, `BINARY`, `SCALAR` |

### RAG — Feature Toggles

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.crag.enabled` | `false` | `†` Enable corrective RAG quality-gating |
| `casehub.rag.reranking.enabled` | `false` | `†` Enable cross-encoder reranking |
| `casehub.rag.expansion.enabled` | `false` | `†` Enable query expansion |
| `casehub.rag.expansion.mode` | — | `†` Expansion mode: `llm`, `step-back`, or `template` |
| `casehub.rag.tracking.enabled` | — | `†` Enable retrieval tracking |
| `casehub.rag.tracking.retention.days` | `90` | Tracking trace retention period (days) |
| `casehub.rag.embedding-cache.enabled` | `false` | Enable embedding cache |
| `casehub.rag.ingestion.dedup.enabled` | `true` | Enable pre-ingestion dedup gate |
| `casehub.rag.ingestion.dedup.threshold` | `0.95` | Cosine similarity threshold for dedup |

### RAG — Ingestion

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.ingestion.interval` | `30s` | Polling interval for corpus ingestion |
| `casehub.rag.ingestion.corpora.<name>.tenant-id` | *(required)* | Tenant ID for corpus |
| `casehub.rag.ingestion.corpora.<name>.corpus-name` | *(required)* | Corpus name in Qdrant |
| `casehub.rag.ingestion.corpora.<name>.chunking` | `none` | Chunking strategy |
| `casehub.rag.tika.chunk-size` | `512` | Tika document chunk size in tokens |
| `casehub.rag.tika.chunk-overlap` | `64` | Tika chunk overlap in tokens |

### Agent Memory — Backend Selection

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.memory.sqlite.path` | *(required)* | SQLite backend: database path |
| `casehub.memory.sqlite.fts.enabled` | `true` | SQLite backend: enable FTS5 full-text search |
| `casehub.memory.jpa.fts.enabled` | `true` | JPA/PostgreSQL backend: enable full-text search |
| `casehub.memory.jpa.fts.language` | `english` | JPA/PostgreSQL backend: FTS language |
| `casehub.memory.mem0.api-key` | *(required)* | Mem0 backend: API bearer token |
| `casehub.memory.graphiti.api-key` | — | Graphiti backend: API bearer token |

### Agent Memory — Retention

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.memory.retention.enabled` | `false` | Enable scheduled confidence-based retention purge |
| `casehub.memory.retention.domain` | — | Memory domain to purge |
| `casehub.memory.retention.max-age-days` | — | Maximum age before purge eligibility |
| `casehub.memory.retention.min-confidence` | — | Minimum confidence to retain |

### CBR — Connection

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.memory.cbr.qdrant.host` | `localhost` | Qdrant gRPC host for CBR |
| `casehub.memory.cbr.qdrant.port` | `6334` | Qdrant gRPC port for CBR |
| `casehub.memory.cbr.qdrant.api-key` | — | Qdrant API key |
| `casehub.memory.cbr.qdrant.use-tls` | `false` | Enable TLS for Qdrant connection |
| `casehub.memory.cbr.qdrant.collection-prefix` | `cbr` | Qdrant collection name prefix |

### CBR — Feature Toggles

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.cbr.reranking.enabled` | `false` | `†` Enable cross-encoder reranking for CBR |
| `casehub.cbr.tracking.enabled` | — | `†` Enable CBR retrieval tracking |
| `casehub.cbr.tracking.retention-days` | `90` | CBR tracking trace retention period (days) |
| `casehub.cbr.adaptation-tracking.enabled` | — | `†` Enable plan adaptation tracking |
| `casehub.cbr.ensemble-tracking.enabled` | — | `†` Enable ensemble analysis tracking |
| `casehub.cbr.diversity.enabled` | `false` | `†` Enable MMR diversity injection |
| `casehub.cbr.outcome-weighting.enabled` | `false` | `†` Enable outcome-based score modulation |
| `casehub.cbr.trust-weighting.enabled` | `false` | `†` Enable trust-based score modulation |

### CBR — Retention

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.cbr.retention.enabled` | `false` | Enable scheduled CBR retention purge |
| `casehub.cbr.retention.domain` | — | CBR domain to purge |
| `casehub.cbr.retention.case-types` | — | Case types subject to retention |
| `casehub.cbr.retention.max-age-days` | — | Maximum case age before purge |
| `casehub.cbr.retention.max-cases-per-type` | — | Maximum cases per type per tenant |
| `casehub.cbr.trust-retention.enabled` | `false` | Enable trust-trajectory-based purge |
| `casehub.cbr.trust-retention.min-current-trust` | `0.3` | Minimum current trust score threshold |

### MindMap

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.mindmap.sqlite.path` | *(required)* | SQLite database path |
| `casehub.mindmap.confidence.half-life-days` | `30` | Confidence decay half-life (days) |

### Cognitive

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.cognitive.profiles-dir` | — | Directory to watch for cognitive profile YAML files |
| `casehub.cognitive.rules-dir` | — | Directory to watch for rule YAML files |
