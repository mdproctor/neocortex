# casehub-neocortex — Contributor Guide

> Internals, architecture, and extension points for neural text inference, RAG, CBR memory, and agent memory.

**GitHub:** [casehubio/neocortex](https://github.com/casehubio/neocortex)

---

## Module Structure

### Inference Modules

| Module | artifactId | Type | Purpose |
|--------|-----------|------|---------|
| `inference-api/` | `casehub-neocortex-inference-api` | Pure Java, zero deps | `InferenceModel` SPI; `InferenceInput` sealed interface (Text + Tensor variants); `InferenceOutput`; `InferenceException`; `MultiModalEmbedder` interface (dense+sparse+ColBERT); `MultiModalEmbedding` value type; `EmbeddingMode` enum (DENSE, SPARSE, COLBERT) |
| `inference-runtime/` | `casehub-neocortex-inference-runtime` | JVM library | ONNX Runtime JVM + HuggingFace Tokenizers JNI; `OnnxInferenceModel`, `ModelConfig`, `ModelLoadException`; session management, tokenization, input name alias resolution |
| `inference-tasks/` | `casehub-neocortex-inference-tasks` | JVM library | `NliClassifier`, `TextClassifier`, `TensorClassifier`, `ScalarRegressor`, `CrossEncoderReranker`, `Softmax` utility |
| `inference-splade/` | `casehub-neocortex-inference-splade` | JVM library | SPLADE sparse embeddings (`Map<Integer, Float>`); log-saturation + threshold; rank-3 max-pool reduction |
| `inference-bge-m3/` | `casehub-neocortex-inference-bge-m3` | JVM library | `BgeM3Embedder` implements `MultiModalEmbedder` — produces dense (1024-dim), sparse (ReLU+threshold), and ColBERT multi-vector embeddings from a single ONNX `InferenceModel` run. Depends only on inference-api |
| `inference-inmem/` | `casehub-neocortex-inference-inmem` | Test library | Deterministic `InferenceModel` stubs; no JNI; safe in all test contexts |
| `inference-quarkus/` | `casehub-neocortex-inference-quarkus` | Quarkus extension | CDI wiring, `@InferenceModel` qualifier, Dev Services, `@QuarkusTest` support, native image reachability metadata |

### Fusion Module

| Module | artifactId | Type | Purpose |
|--------|-----------|------|---------|
| `fusion-api/` | `casehub-neocortex-fusion-api` | Pure Java, zero deps | `FusionStrategy` enum (RRF, DBSF, CC); `ScoreFusion` utility with `ScoredLeg`, `FusedResult` records — weighted RRF + Convex Combination algorithms; `CamelCaseExpander` for BM25 text preprocessing. Shared by RAG and CBR |

### RAG Modules

| Module | artifactId | Type | Purpose |
|--------|-----------|------|---------|
| `rag-api/` | `casehub-neocortex-rag-api` | Pure Java | `EmbeddingIngestor` SPI, `CaseRetriever` SPI; `RetrievalTracker` SPI (record, findRecords, findFeedback, purgeOlderThan); `RelevanceEvaluator` SPI (evaluateChunks); `ColBertRelevanceEvaluator` (pure Java score-threshold mapper with `calibrate()` factory); `QueryExpander` SPI; `RetrievalQuery` (text + expandedText + weightMultipliers); `RetrievalAnalyzer` (static utility: documentStats, unretrievedDocuments, qualitySignals, lowRelevanceQueries, zeroHitQueries, queryFrequency, correlationGraph, queryClusters with MinHash LSH, documentImpact); `CorrelationGraph`, `QueryCluster`, `DocumentImpact`, `EdgeStats`, `DocumentStats`, `QualitySignal`, `QualityThresholds` value types; `MetadataExtractor` SPI; `CursorStore` SPI; `PayloadFilter`; `ScoredGrade`; `RetrievalOutcome` enum; CDI events: `RetrievalRecorded` |
| `rag/` | `casehub-neocortex-rag` | Quarkus module | LangChain4j pipeline, Qdrant, three-leg hybrid search (dense + sparse + BM25) with configurable fusion; `FusionWeightsConfig` (unified per-leg weights: dense/sparse/bm25/quality, replacing CcWeightsConfig); `HybridCaseRetriever` with `effectiveWeight()` (global weight x per-query multiplier); `PayloadBoostCaseRetriever` (@Decorator @Priority(60) — post-fusion quality rescore for RRF/DBSF, CC integrates quality natively); `DedupEmbeddingIngestor` (@Decorator @Priority(50) — cosine similarity dedup gate before indexing); per-leg embedding separation (dense uses searchText(), sparse/ColBERT use text() via embedSeparate()); `SeparateModelEmbedder` (`EmbeddingModel` + optional `SparseEmbedder` to `MultiModalEmbedder`, @DefaultBean); `MultiModalEmbedderProducer` (@DefaultBean, @IfBuildProperty gated); `MatryoshkaMultiModalEmbedder.wrapIfNeeded()`; `DenseQuantization` (binary/scalar for dense vectors); `ColbertQuantizationConfig`; `BM25Index` + `BM25IndexRegistry` (in-memory, `CodeDomainTokenizer`); tenancy isolation via `TenantGuard`; `CorpusIngestionService` (event-driven directory-watcher + @Scheduled polling fallback) |
| `rag-testing/` | `casehub-neocortex-rag-testing` | Test library | `InMemoryCursorStore` @Alternative @Priority(1); `InMemoryRelevanceEvaluator` @Alternative @Priority(1) (fixed grade, NaN score); `InMemoryRetrievalTracker` @Alternative @Priority(1); `RetrievalTrackerContractTest` abstract base (20 tests); in-memory `EmbeddingIngestor` + `CaseRetriever` stubs |
| `rag-tika/` | `casehub-neocortex-rag-tika` | JVM library | Apache Tika document parser — `TikaDocumentParser` extracts text + metadata from binary documents (PDF, DOCX, etc.) |
| `rag-crossencoder/` | `casehub-neocortex-rag-crossencoder` | JVM library | Two CDI decorators: `CorrectiveCaseRetriever` (@Priority(100), grades chunks via `evaluateChunks()` polymorphically); `RerankingCaseRetriever` (@Priority(75), cross-encoder score re-ordering). `CrossEncoderRelevanceEvaluator` (ONNX reranker). `CrossEncoderBeanProducer` (cross-encoder when available, ColBERT fallback). `CragConfig` with ColBERT sub-group (separate thresholds). Config-gated: `casehub.rag.crag.enabled`, `casehub.rag.reranking.enabled` |
| `rag-expansion/` | `casehub-neocortex-rag-expansion` | JVM library | `QueryExpandingCaseRetriever` @Decorator. Expanders: `LlmQueryExpander` (HyDE), `TemplateQueryExpander`, `StepBackQueryExpander`. Multi-query fan-out with RRF fusion. `NoOpQueryExpander` @DefaultBean. `ExpansionConfigValidator` startup warning. `ExpansionConfig`. Config: `casehub.rag.expansion.mode=llm|step-back|template` |
| `rag-tracking/` | `casehub-neocortex-rag-tracking` | JVM library | `TrackingCaseRetriever` (@Decorator @Priority(50), stamps chunks with retrieval ID). `SqliteRetrievalTracker` (SQLite + HikariCP WAL + Flyway). `RetentionScheduler` (ScheduledExecutorService daemon, purge every 24h). CDI events: `RetrievalRecorded`. Config: `casehub.rag.tracking.enabled=true`, `casehub.rag.tracking.retention.days` (default 90) |

### Corpus Modules

| Module | artifactId | Type | Purpose |
|--------|-----------|------|---------|
| `corpus-api/` | `casehub-neocortex-corpus-api` | Pure Java | Corpus storage and change-tracking SPIs — `CorpusStore`, `CorpusReader`, `ChangeSource`, `WatchableChangeSource`, `ChangeListener`; `CorpusIntegrity` SPI for health checks |
| `corpus/` | `casehub-neocortex-corpus` | JVM library | `ZipCorpusStore` (rolling archives, chain manifest), `FlatCorpusStore`, `CompositeCorpusStore`; change tracking; `Compactor`; `CorpusMigrator`; `ZipIntegrityChecker` |

### Agent Memory Modules

| Module | artifactId | Type | Purpose |
|--------|-----------|------|---------|
| `memory-api/` | `casehub-neocortex-memory-api` | Pure Java | `CaseMemoryStore` SPI (store, query, erase, eraseEntity, eraseById, eraseEntityAcrossTenants, scan, purge, discoverTenants, storeAll); `GraphCaseMemoryStore` SPI; `MemoryInput` (with `Confidence` field — origin + value [0.0-1.0]); `Memory`; `MemoryQuery`; `MemoryOrder` (CHRONOLOGICAL, RELEVANCE, SALIENCE); `MemoryRetentionPolicy` (tenantId + domain + maxAgeDays + minConfidence); `MemoryScanRequest`; `MemoryCapability` enum (incl. DISCOVER_TENANTS, SCAN, PURGE); `CaseEnrichmentStep` SPI; `MemoryDomain`; `MemoryAttributeKeys`; `EraseRequest`; `StoreAllResult`; `StoreFailure` |
| `memory/` | `casehub-neocortex-memory` | CDI module | `MemoryEmitter` (@ApplicationScoped fire-and-forget wrapper — error isolation, SecurityException propagates); `NoOpCaseMemoryStore` @DefaultBean; `CaseEnrichmentDecorator` (@Decorator); `MemoryRetentionScheduler` (scheduled confidence-based purge across discovered tenants) |
| `memory-inmem/` | `casehub-neocortex-memory-inmem` | Backend | @Alternative @Priority(10) volatile ConcurrentHashMap — test + ephemeral + discoverTenants + scan + purge |
| `memory-jpa/` | `casehub-neocortex-memory-jpa` | Backend | @ApplicationScoped JPA/PostgreSQL + Flyway + FTS via websearch_to_tsquery + discoverTenants |
| `memory-sqlite/` | `casehub-neocortex-memory-sqlite` | Backend | @Alternative @Priority(1) SQLite + HikariCP WAL + FTS5 + discoverTenants |
| `memory-mem0/` | `casehub-neocortex-memory-mem0` | Backend | @Alternative @Priority(1) Mem0 REST client adapter — blocking direct REST |
| `memory-graphiti/` | `casehub-neocortex-memory-graphiti` | Backend | @Alternative @Priority(2) Graphiti REST GraphCaseMemoryStore — blocking direct REST, incl. graphQuery() |
| `memory-testing/` | `casehub-neocortex-memory-testing` | Test library | `CbrCaseMemoryStoreContractTest` abstract base (164 tests); `CbrRetrievalTrackerContractTest` (10 tests); `PlanEnsembleAnalyzerContractTest` (7 tests); `InMemoryCbrRetrievalTracker`; test stubs for memory SPIs |

### CBR Memory Modules

| Module | artifactId | Type | Purpose |
|--------|-----------|------|---------|
| `memory-api/` | (same artifact) | Pure Java | `CbrCaseMemoryStore` SPI (composite extending CbrCaseStore + CbrCaseRetriever + CbrCaseLifecycle + CbrCaseAdmin ISP sub-interfaces); `DelegatingCbrCaseMemoryStore` (forwarding base class for decorators); `CbrCase` hierarchy (TextualCbrCase, FeatureVectorCbrCase, PlanCbrCase); `CbrQuery` (CaseTypeScope sealed: Specific/AllInDomain for cross-type retrieval, weights, vectorWeight, RetrievalMode, FusionStrategy, filters, TemporalDecay, scope Path, ScopeDecay, withFeatures(), crossType() factory); `CbrFilter` sealed hierarchy (8 variants); `FeatureValue` sealed (7 types); `FeatureField` sealed (9 types); `SimilaritySpec` sealed (6 types); `CbrSimilarityScorer`; `CbrFeatureValidator`; `CbrFeatureSchema` (with optional learningRate); `DtwSimilarity` + `LbKeogh` (O(n) lower-bound pruning); `EditDistanceSimilarity`; `WarpingConstraint` sealed; `TrendAnalyzer`, `TrendSpec`, `TrendType`, `TrendProfile`, `TrendFieldNaming`; `TemporalDecay` sealed (3 types); `ScopeDecay` sealed (3 types); `PlanAdapter` SPI; `PlanEnsembleAnalyzer` SPI; `CbrOutcome`; `CbrRetentionPolicy` (with minTrustScore); `CbrScanRequest`; `CbrCaseSummary`; `SupersessionStatus`; `AgentTrustProvider` SPI; `TrustWeightingFunction` SPI; `OutcomeWeightingFunction` SPI; `ExplanationRenderer` SPI; `CbrRetrievalTracker` SPI; `PersonalityTransitionSchema`; `FeatureStatistics`; `CbrSuggestions`; CDI events: `CbrRetrievalRecorded`, `CbrAdaptationRecorded`, `CbrEnsembleRecorded`, `CbrCasesErased` (sealed: ByRequest, ByEntity, ByScope), `CbrCasesSuperseded` (sealed: ByCase, ByFilter, ByIds), `CbrCasesReinstated` (sealed: ByCase, ByFilter, ByIds) |
| `memory/` | (same artifact) | CDI module | CBR decorator chain (all @Decorator on CbrCaseMemoryStore): `TrendEnrichmentCbrCaseMemoryStore` (@Priority(90) — enriches TimeSeries features with derived trend metrics on store/retrieve); `ScopeDecayCbrCaseMemoryStore` (@Priority(85) — scope-distance score decay); `TemporalDecayCbrCaseMemoryStore` (@Priority(80) — temporal decay post-scoring); `OutcomeWeightingCbrCaseMemoryStore` (@Priority(65) — confidence-based score modulation, @IfBuildProperty); `TrustWeightedCbrCaseMemoryStore` (@Priority(60) — trust authority + trajectory scoring, @IfBuildProperty); `TrackingCbrCaseMemoryStore` in memory-cbr-tracking (@Priority(50) — retrieval tracking); `ErasureNotificationCbrCaseMemoryStore` (@Priority(45) — fires CbrCasesErased CDI events). Plus: `NoOpCbrCaseMemoryStore` @DefaultBean; `CbrOutcomeConsumer` (@ObservesAsync @CloudEventType — bridges CloudEvent to recordOutcome); `CbrRetentionScheduler` (scheduled age+count+trust purge); `TrustRetentionService` (trust-trajectory-based purge via AgentTrustProvider); `DefaultOutcomeWeightingFunction` (linear interpolation); `DefaultTrustWeightingFunction` (authority + trajectory); `DefaultExplanationRenderer`; `NoOpPlanAdapter` @DefaultBean; `NoOpPlanEnsembleAnalyzer` @DefaultBean |
| `memory-cbr-inmem/` | `casehub-neocortex-memory-cbr-inmem` | Backend | @Alternative @Priority(2) — in-memory stub for tests, clearCases() for isolation (clears cases, preserves schemas) |
| `memory-cbr-jpa/` | `casehub-neocortex-memory-cbr-jpa` | Backend | @Alternative @Priority(3) JPA/PostgreSQL. `CbrCaseEntity` with JSONB features (`Map<String, FeatureValue>`), plan traces, outcome tracking, supersession metadata. Flyway migrations |
| `memory-qdrant/` | `casehub-neocortex-memory-qdrant` | Backend | @ApplicationScoped gRPC client. `QdrantCbrCaseMemoryStore` — payload filters (categorical/numeric/text + structured: CategoricalList/NestedObject/ObjectList with dot-notation) + dense vector search + SPLADE sparse embeddings + BM25 server-side inference + dynamic 2-4 leg hybrid fusion (CC weight renormalization) + notBefore temporal filtering + per-inner-field payload indexes + dimension validation + collection schema evolution. `CbrReconciliationService` (three-phase: orphan cleanup + reindex + vector enrichment backfill, Micrometer metrics). `CbrPointBuilder` (structured value serialization). Two-pass retrieveSimilar() with batch precompute for semantic text fields |
| `memory-cbr-embedding/` | `casehub-neocortex-memory-cbr-embedding` | Backend | `EmbeddingTextSimilarity` — `LocalSimilarityFunction` for semantic text field cosine similarity, batch `precompute()` via `embedAll()`, cache-backed `compute()`. Depends on memory-api + langchain4j-core only |
| `memory-cbr-crossencoder/` | `casehub-neocortex-memory-cbr-crossencoder` | Backend | `RerankingCbrCaseMemoryStore` (@Decorator @Priority(75)). Sigmoid-normalized scores. Double-reranking guard via `ScoredCbrCase.reranked()`. Config: `casehub.cbr.reranking.enabled` |
| `memory-cbr-tracking/` | `casehub-neocortex-memory-cbr-tracking` | Backend | `TrackingCbrCaseMemoryStore` (@Decorator @Priority(50) — records retrieval via CbrRetrievalTracker). `SqliteCbrRetrievalTracker` (SQLite + HikariCP WAL + Flyway). `TrackingPlanAdapter` (@Decorator @Priority(50) — fires CbrAdaptationRecorded after adaptation, `casehub.cbr.adaptation-tracking.enabled`). `TrackingPlanEnsembleAnalyzer` (@Decorator @Priority(50) — fires CbrEnsembleRecorded, `casehub.cbr.ensemble-tracking.enabled`). @Scheduled retention purge. Config: `casehub.cbr.tracking.enabled=true` |

### Examples and Evaluation

| Module | Type | Purpose |
|--------|------|---------|
| `examples/example-text-analysis` | Standalone Java | NLI, zero-shot classification, scoring, reranking, SPLADE demos (no Quarkus) |
| `examples/example-rag-pipeline` | Quarkus demos | Corpus ingestion, hybrid search with RRF fusion, cross-encoder reranking. Maven profiles: `-Pexamples-smoke` (in-memory stubs), `-Pexamples` (real ONNX models + Testcontainers Qdrant) |
| `examples/example-cbr` | Quarkus demos | Six-domain CBR demo: AML investigation, clinical adverse events, PR code review, life insurance contractor assessment, IoT situations, game battle strategy |
| `evaluation/code_domain_embeddings/` | Python | Tokenizer analysis, embedding discrimination, benchmark runner, deployment check. Requires own venv. Run: `python3 -m evaluation.code_domain_embeddings.<script>` |
| `evaluation/strategy_classifier/` | Python | MSC data pipeline (download, fog-of-war simulation, hybrid labelling), CNN-Attention model training with focal loss, ONNX export with temperature baking, evaluation harness. Requires own venv. Run: `python3 -m evaluation.strategy_classifier.<script>` |

---

## Internal Architecture

### InferenceInput Sealed Hierarchy

`InferenceInput` is a sealed interface with two variants:
- `InferenceInput.Text` — tokenized text input. Single text (`InferenceInput.of(text)`) or text pair (`InferenceInput.pair(first, second)`). Validated: at most 2 texts.
- `InferenceInput.Tensor` — raw named float tensors (`InferenceInput.tensor(Map<String, float[][]>)`). Bypasses tokenization entirely. Used by `TensorClassifier` for multi-dimensional input (e.g. strategy classifier game state arrays).

### TensorClassifier (inference-tasks)

`TensorClassifier` — classification adapter for tensor inputs. Accepts `Map<String, float[][]>` (named tensor inputs), runs through `InferenceModel`, applies `Softmax`, maps to configurable labels. Validates label count matches model `outputSize()` at construction. Returns `ClassificationResult`. Used by the strategy classifier evaluation pipeline (#75, #76).

### OnnxInferenceModel Input Name Alias Resolution

Static alias table + `ModelConfig` overrides for input tensor names in `inference-runtime/`. Handles models with non-standard input names transparently. Introduced in neocortex#104.

### SparseEmbedder Rank-3 Max-Pool Reduction

Rank-3 output tensors from SPLADE models are reduced via max-pool across the sequence dimension before log-saturation in `inference-splade/`. Handles models that output per-token weights instead of per-vocab weights. Introduced in neocortex#104.

### DenseQuantization

Enum in `rag/` with values `NONE`, `BINARY`, `SCALAR`. Configures Qdrant quantization on the **dense vector params** at collection creation time — applied to `denseParamsBuilder` specifically, not to the entire collection (sparse vectors are not quantized). `BINARY` applies `BinaryQuantization`; `SCALAR` applies `ScalarQuantization` with `Int8` type. Both respect `casehub.rag.quantization.always-ram` (default `true`). Config: `casehub.rag.quantization.type` (default `NONE`).

Named `DenseQuantization` rather than `QuantizationType` because the Qdrant client already defines `io.qdrant.client.grpc.Collections.QuantizationType` — both enums appear in `ensureCollection()` / `buildCreateRequest()` and sharing the name would create ambiguous unqualified usage.

### HybridCaseRetriever — Weighted Fusion and Per-Query Multipliers

`HybridCaseRetriever` implements three-leg hybrid search (dense + sparse + BM25) with `effectiveWeight()` — combines global `FusionWeightsConfig` with per-query `weightMultipliers` from `RetrievalQuery`. This enables dynamic weight boosting (e.g. boosting BM25 when keywords are detected).

When quantization is active (`DenseQuantization != NONE`) and oversampling is set, the dense prefetch leg applies `QuantizationSearchParams` with the configured oversampling factor + `rescore=true`. Sparse prefetch is unaffected.

Weighted RRF auto-falls back to client-side computation when leg weights are non-equal. CC always uses client-side `ConvexCombinationFusion`.

### Per-Leg Embedding Separation

Dense leg uses `RetrievalQuery.searchText()` (optimized for search), sparse and ColBERT legs use `text()` (full original query). Enables query reformulation for dense retrieval while preserving term-level signals for sparse matching. `embedSeparate()` is unconditional and batch-composition safe. Introduced in neocortex#113.

### PayloadBoostCaseRetriever

`PayloadBoostCaseRetriever` (@Decorator @Priority(60)) applies post-fusion quality rescore for RRF and DBSF strategies using the `quality` weight from `FusionWeightsConfig`. No-op for CC — CC integrates quality as a fourth fusion leg natively via `executeConvexCombinationFusion()`. Introduced in neocortex#180.

### Pre-Ingestion Dedup Gate

`DedupEmbeddingIngestor` (@Decorator @Priority(50) on `EmbeddingIngestor`). Before indexing each chunk, embeds it and queries the Qdrant collection for the nearest existing vector. If cosine similarity exceeds the threshold (default 0.95), the chunk is skipped. Graceful degradation: interrupted or failed dedup checks proceed with ingestion (fail-open). Config: `casehub.rag.ingestion.dedup.enabled` (default true), `casehub.rag.ingestion.dedup.threshold`. Introduced in neocortex#195.

### BM25 as Third Retrieval Leg

`BM25Index` — thread-safe in-memory inverted index with `CodeDomainTokenizer` for camelCase/code-aware tokenization. Standard BM25 scoring (k1=1.2, b=0.75). `BM25IndexRegistry` manages per-corpus indexes. Three-leg hybrid search: dense + sparse + BM25, fused via configurable `FusionStrategy`. Config: `casehub.rag.bm25.enabled` (default true).

### Cross-Encoder Reranking and Corrective RAG

Two CDI decorator chains in `rag-crossencoder/`:
- **Corrective retrieval** (`CorrectiveCaseRetriever`, Priority 100) — calls `evaluateChunks()` polymorphically (no instanceof check — dispatches to CrossEncoder or ColBERT evaluator). Grades chunks as correct/ambiguous/incorrect, filters before LLM injection
- **Reranking** (`RerankingCaseRetriever`, Priority 75) — cross-encoder score re-ordering

`CrossEncoderBeanProducer` — single producer: cross-encoder `RelevanceEvaluator` when ONNX reranker available, `ColBertRelevanceEvaluator` fallback when `reranking.enabled=true`, startup failure otherwise.

`CragConfig` extended with `ColBertConfig` sub-group for separate thresholds (cross-encoder 0.7/0.3, ColBERT 0.55/0.35).

### Query Expansion

`QueryExpandingCaseRetriever` — @Decorator on `CaseRetriever`. Single-retrieval HyDE (original query no longer prepended — clean separation). Expanders: `LlmQueryExpander`, `TemplateQueryExpander`, `StepBackQueryExpander`. Multi-query fan-out with RRF fusion. `NoOpQueryExpander` @DefaultBean (pass-through). `ExpansionConfigValidator` emits startup warning when expansion enabled without mode. Explicit mode selection required.

### RetrievalAnalyzer

Static utility in `rag-api` — pure computation over `RetrievalTracker` data, no I/O:

**Document-level:** `documentStats()` aggregates per-document retrieval count, average score, outcome distribution from feedback data. `unretrievedDocuments()` identifies corpus documents never retrieved in a time window. `qualitySignals()` flags underperforming documents via configurable `QualityThresholds`.

**Query-level:** `lowRelevanceQueries()` identifies queries where all results scored below threshold. `zeroHitQueries()` finds queries with empty results. `queryFrequency()` returns `QueryFrequencyStats`.

**Correlation:** `correlationGraph()` builds bipartite query-to-document graph with `EdgeStats` (co-occurrence count, average score, outcome distribution per edge). `queryClusters()` performs single-linkage Jaccard clustering — MinHash LSH for n > 50 queries (threshold constant `MINHASH_THRESHOLD`), brute-force below. `documentImpact()` computes centrality ranking with outcome aggregation.

### Retrieval Tracking

SQLite-backed retrieval tracking in `rag-tracking/`. `TrackingCaseRetriever` (Decorator Priority 50) stamps chunks with retrieval ID. `SqliteRetrievalTracker` with HikariCP + Flyway migrations. `RetentionScheduler` for trace purging (ScheduledExecutorService daemon thread, 24h interval). CDI events: `RetrievalRecorded`.

### MemoryOrder.SALIENCE and Confidence

`MemoryOrder` enum with three values: `CHRONOLOGICAL` (all adapters), `RELEVANCE` (semantic adapters — JPA FTS, Mem0, Graphiti), `SALIENCE` (recency x confidence). SALIENCE is a non-semantic ranking strategy: non-semantic adapters compute it from `Memory.createdAt()` and `Memory.confidence()` (null treated as value 1.0); semantic adapters fall back to RELEVANCE.

`MemoryInput.confidence` — optional `Confidence` record (origin + value [0.0, 1.0]) on store input. Enables salience-based ranking and confidence-based retention purge.

### Memory Retention

`MemoryRetentionPolicy` — record with `tenantId`, `domain`, `maxAgeDays`, `minConfidence` (at least one of the latter two required). `CaseMemoryStore.purge(MemoryRetentionPolicy)` deletes memories below confidence or older than threshold.

`MemoryRetentionScheduler` — @ApplicationScoped scheduled purge. Iterates `discoverTenants()`, creates per-tenant `MemoryRetentionPolicy`, calls `purge()`. Capability-gated: requires both DISCOVER_TENANTS and PURGE. Config-driven.

### CBR Typed Feature Values and Similarity

**Feature values:** `FeatureValue` sealed interface with seven value types: `StringVal`, `NumberVal`, `RangeVal`, `StringListVal`, `NumberListVal`, `StructVal`, `StructListVal`. Booleans coerced to `StringVal` via `FeatureValue.of(Object)`.

**Feature field schema:** `FeatureField` sealed interface with nine permits: `Categorical`, `Numeric`, `Text` (with `semantic` flag and `semanticText()` factory), `CategoricalList`, `NumericList` (with min/max bounds), `NestedObject`, `ObjectList`, `TimeSeries` (compound with inner fields, timestamp, optional `DtwSpec` + `TrendSpec`), `DiscreteSequence` (ordered categorical sequences with `EditDistanceSpec`).

**Similarity specs:** `SimilaritySpec` sealed interface:
- `CategoricalTable` — lookup table with auto-mirroring and `CategoricalTableBuilder`
- `GaussianDecay(sigma)`, `StepDecay(tolerance)`, `ExponentialDecay(decayRate)` — for Numeric fields
- `DtwSpec(WarpingConstraint)` — Dynamic Time Warping for TimeSeries fields
- `EditDistanceSpec(substitutions, insertCost, deleteCost)` — for DiscreteSequence fields

`CbrSimilarityScorer` — pure-Java per-field similarity with three-level precedence: caller override > field SimilaritySpec > type default. Centralized `NumericRange` via `computeNormalizedDistance`. Exhaustive switches at all dispatch sites. Structured fields participate via `LocalSimilarityFunction` overrides.

`CbrFeatureValidator` — consolidated store-time, query-time, and filter validation. Temporal field validation: ascending timestamps, inner field types.

### CBR Filters

`CbrFilter` sealed interface with eight variants:
- `Contains`, `ContainsAll`, `ContainsAny` — positive match
- `NotContains`, `NotContainsAny` — negation for CategoricalList
- `ContainsRange` — NumericList range matching
- `HasMatch` — nested/dot-notation matching
- `AllOf` — compound same-field filter (wraps 2+ filters with polarity-preserving dispatch)

### CBR Hierarchical Scoping

`CbrQuery.scope` (required `Path` from casehub-platform-api) for hierarchical visibility. `ScopeDecay` sealed interface:
- `Exponential(double base)` — exponential decay by scope depth distance
- `Linear(int maxDepth)` — linear decay to zero at maxDepth
- `Step(double beyondExact)` — flat penalty beyond exact scope match

`ScopeDecayCbrCaseMemoryStore` (@Decorator @Priority(85)) applies scope-distance score multiplier, re-sorts, and filters by `minSimilarity` after decay. Null scopeDecay = pass-through.

`eraseByScope(Path, tenantId)` — bulk scope-based erasure for operational cleanup (#158). Aggregate adjustment on entity erasure — recomputes higher-scope aggregates when source cases are erased (#159).

### CBR Plan Adaptation

`PlanAdapter` SPI — `adapt(caseType, ScoredCbrCase<PlanCbrCase>, features)` returns `AdaptedPlan` (wrapping `List<AdaptedStep>`). `caseType` is a first-class parameter for type-specific adaptation rules. `AdaptedStep` carries `bindingName`, nullable `capabilityName`, `workerName`, `stepOutcome`, `priority`, `parameters`, `AdaptationAction`, `reason`. `AdaptationTrace` for audit with `retrievalTraceId` link. `PlanTrace` record with optional `variantId` for variant tracking.

`NoOpPlanAdapter` @DefaultBean — returns all steps RETAINED, zero behavioral change.

### CBR Plan Ensemble Analysis

`PlanEnsembleAnalyzer` SPI — operates after per-plan `PlanAdapter` adaptation. `analyze(caseType, List<ScoredCbrCase<PlanCbrCase>>, List<AdaptedPlan>, features)` examines multiple adapted plans for consensus/divergence and synthesizes an `EnsemblePlan`. `StepConsensus` classifies per-step agreement as UNANIMOUS, CONSENSUS, CONTESTED, MINORITY, or UNIQUE — with worker/outcome/priority distributions. `EnsemblePlan` carries `ensembleConfidence` [0,1] and `inputPlanCount`.

`NoOpPlanEnsembleAnalyzer` @DefaultBean — picks best-scoring plan, reports inputPlanCount=1 with UNANIMOUS agreement.

`PlanEnsembleAnalyzerContractTest` abstract base (7 tests) in `memory-testing`.

### CBR Outcome Feedback and Weighting

`CbrOutcome` — Outcome enum with EMA `adjustConfidence()` and `DEFAULT_LEARNING_RATE`. `CbrCaseMemoryStore.recordOutcome()` for CBR Revise feedback loop. `CbrFeatureSchema` supports optional `learningRate` (validated [0,1]) for per-caseType EMA speed.

`OutcomeWeightingCbrCaseMemoryStore` (@Decorator @Priority(65)) modulates retrieval scores by case confidence. `DefaultOutcomeWeightingFunction` — linear interpolation `score*(1-alpha+alpha*confidence)`. Config: `casehub.cbr.outcome-weighting.enabled`, `casehub.cbr.outcome-weighting.influence` (default 0.3).

`CbrOutcomeConsumer` — @ObservesAsync @CloudEventType(CbrEventTypes.CBR_OUTCOME). Deserializes CloudEvent data to `CbrOutcomeData`, bridges to `CbrCaseMemoryStore.recordOutcome()`. Depends on casehub-desiredstate-api.

### CBR Trust-Weighted Retrieval

`TrustWeightedCbrCaseMemoryStore` (@Decorator @Priority(60)) modulates retrieval scores by source trust authority + optional trust trajectory. Per-retrieval trajectory cache.

`AgentTrustProvider` (@FunctionalInterface SPI) — `OptionalDouble currentTrustScore(agentId)`. Implemented by engine bridge to TrustScoreSource.

`TrustWeightingFunction` SPI — `apply(similarity, trustScore, trustTrajectory)`. Default: authority `score*(1-alpha+alpha*trustScore)`, trajectory `max(0.5, 1+beta*delta)` for declining only. Config: `casehub.cbr.trust-weighting.influence` (default 0.3), `trajectorySensitivity` (default 0.5).

### CBR Retention and Trust Purge

`CbrRetentionPolicy` — record with `tenantId`, `domain`, `caseType`, `maxAgeDays`, `maxCasesPerType`, `minTrustScore`. `CbrCaseMemoryStore.purge(CbrRetentionPolicy)`.

`CbrRetentionScheduler` — @ApplicationScoped scheduled purge across discovered tenants and configured caseTypes.

`TrustRetentionService` — evaluates agent trust trajectories via `AgentTrustProvider`. Paginated scan (`CbrScanRequest` / `CbrCaseSummary`) identifies cases from agents below `minCurrentTrust`, erases them. Config: `casehub.cbr.trust-retention.enabled`, `casehub.cbr.trust-retention.min-current-trust`.

### CBR Active Memory Management

**Temporal decay:** `TemporalDecay` sealed interface with three implementations: `HalfLife(Duration)` (exponential), `Linear(Duration zeroAt)`, `Step(Duration cutoff, double afterCutoff)`. `TemporalDecayCbrCaseMemoryStore` (Decorator Priority 80) applies decay to retrieval scores based on case `storedAt`.

**Supersession:** `CbrCaseLifecycle` (sub-interface of `CbrCaseMemoryStore`) provides single-case and bulk supersession. Single: `boolean supersede(caseId, tenantId, supersedingCaseId, reason)` and `boolean reinstate(caseId, tenantId)` — return true only on state transition. Bulk: `supersedeMatching(tenantId, domain, caseType, filters, reason)`, `supersedeAll(caseIds, tenantId, reason)`, `reinstateMatching(tenantId, domain, caseType, filters)`, `reinstateAll(caseIds, tenantId)` — return count of affected cases. `findCaseIds(tenantId, domain, caseType, filters)` on `CbrCaseRetriever` for filter-based case lookup (non-superseded only). `getSupersessionStatus()` returns `SupersessionStatus` with audit metadata (wasReinstated() convenience). `findSupersededCases()` for audit queries. CDI events: `CbrCasesSuperseded` (sealed: ByCase, ByFilter, ByIds) and `CbrCasesReinstated` (sealed: ByCase, ByFilter, ByIds) fired by `SupersessionNotificationCbrCaseMemoryStore` (@Decorator @Priority(44)).

### Trend Detection

`TrendSpec` — record holding `Set<TrendType>` and `ChronoUnit` (default HOURS), attached optionally to `FeatureField.TimeSeries`. `TrendType` enum (7 types): SLOPE, DELTA, VOLATILITY, ACCELERATION, CHANGE_POINTS, DURATION, OBSERVATION_COUNT. `isPerField()` discriminates per-inner-field vs per-TimeSeries.

`TrendAnalyzer` — static utility: `analyze()` computes trend metrics from observations (least-squares regression, Welford's stddev, half-split acceleration, CUSUM change-point detection — all O(n)). `enrichFeatures()` returns new map with derived Numeric values. `expandSchema()` idempotent expansion with heuristic ranges.

`TrendFieldNaming` — deterministic derived field naming: `{tsName}_{type}_{innerField}` for per-field, `{tsName}_{type}` for per-TimeSeries. Underscore separators avoid Qdrant dot-notation conflict.

`TrendEnrichmentCbrCaseMemoryStore` (Decorator Priority 90) — intercepts registerSchema (expandSchema), store (enrichFeatures on case), retrieveSimilar (enrichFeatures on query). Schema-driven activation via TrendSpec presence, no @IfBuildProperty gate.

### PersonalityTransitionSchema

Built-in CBR schema convention in `memory-api/`. Records personality evolution events — when an agent's cognitive function profile shifts (e.g. dominant Ti to Fe after JPAF reflection). Case type: `personality-transition`. Seven categorical features: agent_id, old_dominant, new_dominant, old_auxiliary, new_auxiliary, trigger_type, outcome.

Consumers: engine personality-adaptive routing. Producers: engine JPAF reflection mechanism. Data model: eidos weighted disposition profiles.

### CBR Reconciliation

`CbrReconciliationService` in `memory-qdrant/` — @ApplicationScoped three-phase reconciliation:
1. Paginated SCAN of delegate store
2. Orphan cleanup + consistency marking in Qdrant
3. Batch reindex of missing entries (pages of 100) + vector enrichment backfill (SPLADE/BM25 vectors on existing points)

Supports `reconcile(caseType, tenantId)`, `reconcileAll(caseType)`, `discoverTenants(caseType)`. Micrometer metrics for orphans/reindexed/enriched/errors.

### Erasure Notification

`ErasureNotificationCbrCaseMemoryStore` (@Decorator @Priority(45)) fires `CbrCasesErased` CDI events after erasure. `CbrCasesErased` is a sealed interface with three variants: `ByRequest`, `ByEntity`, `ByScope`. Clock injection for testability.

### CDI Decorator Priority Chain Summary

**CaseRetriever chain (rag):**
| Priority | Decorator | Module |
|----------|-----------|--------|
| 100 | `CorrectiveCaseRetriever` | rag-crossencoder |
| 75 | `RerankingCaseRetriever` | rag-crossencoder |
| 60 | `PayloadBoostCaseRetriever` | rag |
| 50 | `TrackingCaseRetriever` | rag-tracking |
| (expansion) | `QueryExpandingCaseRetriever` | rag-expansion |

**EmbeddingIngestor chain (rag):**
| Priority | Decorator | Module |
|----------|-----------|--------|
| 50 | `DedupEmbeddingIngestor` | rag |

**CbrCaseMemoryStore chain (memory):**
| Priority | Decorator | Module |
|----------|-----------|--------|
| 90 | `TrendEnrichmentCbrCaseMemoryStore` | memory |
| 85 | `ScopeDecayCbrCaseMemoryStore` | memory |
| 80 | `TemporalDecayCbrCaseMemoryStore` | memory |
| 75 | `RerankingCbrCaseMemoryStore` | memory-cbr-crossencoder |
| 65 | `OutcomeWeightingCbrCaseMemoryStore` | memory |
| 60 | `TrustWeightedCbrCaseMemoryStore` | memory |
| 50 | `TrackingCbrCaseMemoryStore` | memory-cbr-tracking |
| 45 | `ErasureNotificationCbrCaseMemoryStore` | memory |
| 44 | `SupersessionNotificationCbrCaseMemoryStore` | memory |

**MindMapStore chain (mindmap):**
| Priority | Decorator | Module |
|----------|-----------|--------|
| 80 | `DerivedEdgeDecorator` | mindmap |
| 70 | `TraitApplicationDecorator` | mindmap |
| 65 | `AffectTrajectoryDecorator` | mindmap |
| 30 | `MindMapStoreIdleTracker` | mindmap |

### Knowledge Model Internals

#### Thing / MindMapNode Hierarchy

`Thing` (in `thing-api`) is the semantic knowledge representation base — identity, properties, traits, and a dynamic type system. `MindMapNode` (in `mindmap-api`) extends `Thing` with cognitive features — what the agent *believes* about the entity.

```
Thing (thing-api, zero deps)
  — id(), name(), type(), property(), properties(), traits()
  — is(), as()  [default methods]
    └── MindMapNode (mindmap-api)
        — confidence(), PAD, temporal bounds, provenance, refs()
        — subgraphType()  ← type() delegates here
```

**Dependency direction:** `thing-api` ← `mindmap-api` ← `mindmap-intelligence` ← `cognitive-index`. Consumers who need entity access depend on `thing-api` (zero deps). Cognitive subsystem components depend on `mindmap-api`.

**Key invariant:** `MindMapNode.type()` is a default method that delegates to `subgraphType()`. This ensures every MindMapNode's type comes from its subgraph membership — no implementor can diverge. Thing is a read-only projection; mutations go through `MindMapStore`.

See the consumer guide for `is()`/`as()` usage patterns, `SubgraphTypes` constants, and the knowledge lifecycle.

#### Adding subgraphType() to Store Implementations

Every `MindMapNode` returned by a store must have `subgraphType()` resolved at construction time. Two patterns exist:

**SQLite — JOIN pattern.** All node-returning queries JOIN with `mindmap_subgraph` on `subgraph_id`. Cost is negligible — `subgraph_id` is the primary key, so each resolution is a single B-tree lookup:

```sql
SELECT n.*, sg.type AS sg_type
FROM mindmap_node n
JOIN mindmap_subgraph sg ON n.subgraph_id = sg.subgraph_id
WHERE n.node_id = ? AND n.tenant_id = ?
```

The `toNode(ResultSet)` method reads `rs.getString("sg_type")` into the `SqliteNode` record.

**InMemory — cache lookup pattern.** `addNode()` resolves the subgraph type at creation from the in-memory `subgraphs` map:

```java
MindMapSubgraph sg = subgraphs.get(input.subgraphId());
String sgType = sg != null ? sg.type() : "";
StoredNode node = new StoredNode(id, input.name(), input.subgraphId(), sgType, ...);
```

**The rule:** never defer type resolution to a property or lazy lookup. `subgraphType()` must be available on every node the store returns. `MindMapStoreContractTest` validates this.

#### TypeRegistry Internals

`TypeRegistry` is an `@ApplicationScoped` CDI bean in `mindmap-intelligence` that mediates all type operations. It uses `Instance<MindMapStore>` for graceful degradation — when no store is on the classpath, all operations return empty results.

**Lazy bootstrap.** Types are bootstrapped per tenant on first access via `ConcurrentHashMap.computeIfAbsent`. No eager `@PostConstruct` scan — this avoids discovering which tenants exist at startup and handles new tenants provisioned after the application starts. The bootstrap sequence:

1. Find the `TYPE_SYSTEM` subgraph by type (iterates `listSubgraphs`)
2. If absent, create it — catch `IllegalStateException` on the unique constraint `(tenant_id, type)` if another JVM instance wins the race, then re-query
3. Load all existing type nodes into the cache
4. Create core type nodes if absent (general, person, project, organisation, concept, research-area)

**Core types.** `CORE_TYPES` maps well-known types to their Java interfaces: `PERSON → Personable.class`, `PROJECT → Projectlike.class`, `ORGANISATION → Organisational.class`. Core type nodes carry a `java-class` property and schema properties derived from the interface via reflection.

**Schema derivation.** `deriveSchemaFromInterface()` reflects declared methods (excluding defaults and Object methods), mapping return types: `String`/`Optional<String>` → `"string"`, `int`/`Integer`/`long`/`Long`/`double`/`Double` → `"number"`, `boolean`/`Boolean` → `"boolean"`. All reflected fields default to `required = false`.

**Dynamic type registration.** `registerType(typeName, parentType, tenantId)` creates a node in TYPE_SYSTEM with an optional `subtype-of` edge to the parent. Type names are normalized with `strip().toLowerCase()`. If the type already exists, the call is a no-op.

**Cache structure.** `BootstrappedTenant` holds `typeSystemSubgraphId` + a `ConcurrentHashMap<String, String>` mapping type name → node ID. `resolveTypeNode()` does a `store.getNode()` call — the cache maps names to IDs, not to node objects, since node state may change.

#### Writing TraitRules

TraitRules evaluate nodes for trait assignment. Two approaches:

**Programmatic rules.** Implement the `TraitRule` interface and register as an `@ApplicationScoped` CDI bean:

```java
@ApplicationScoped
public class PersonableTraitRule implements TraitRule {
    @Override
    public String traitName() { return "Personable"; }

    @Override
    public boolean matches(MindMapNode node, List<MindMapEdge> edges) {
        boolean hasProperties = node.property("birthday").isPresent()
            || node.property("role").isPresent()
            || node.property("email").isPresent();
        boolean hasEdges = edges.stream()
            .anyMatch(e -> "parent-of".equals(e.edgeType())
                || "works-at".equals(e.edgeType()));
        return hasProperties || hasEdges;
    }
}
```

The trait name must be PascalCase, matching the Java interface simple name.

**Declarative rules.** `DeclarativeTraitRule` wraps a `RuleCondition` tree — a sealed interface with 11 variants: `HasProperty`, `PropertyEquals`, `PropertyIn`, `NotHasProperty`, `HasEdgeType`, `HasEdgeTypes`, `HasAnyEdge`, `InSubgraphType`, `AnyOf`, `AllOf`, `Not`. Declared in YAML cognitive profiles, deserialized by `DeclarativeTraitRuleDeserializer`, loaded by `DeclarativeRuleRegistry` — global rules from `rules/*.yaml` + per-agent overrides (name-based merge: local rule with same name suppresses global).

**How traits get applied.** `TraitApplicationDecorator` (@Decorator @Priority(70)) intercepts `addNode`, `updateNode`, `addEdge`, and `removeEdge`. After the delegate operation completes, it evaluates all rules (programmatic + declarative) against the affected node. If any rule matches a trait not yet present, the trait is added via `NodeUpdate`. If no rule matches a previously present trait, it is removed. A `ThreadLocal` reentrancy guard prevents infinite recursion (trait update triggers `updateNode` which would re-evaluate). When a `PrincipalId` is available, per-agent declarative rules are resolved; otherwise all rules fire.

#### ThingProxyHandler — Adding Return Type Coercions

`ThingProxyHandler` is a package-private class in `thing-api` (67 lines). It creates JDK `Proxy` instances that map interface method names to `thing.property(methodName)` calls with return type coercion:

| Return type | Present value | Missing value |
|-------------|--------------|---------------|
| `String` | value as-is | `null` |
| `Optional<String>` | `Optional.of(value)` | `Optional.empty()` |
| `Integer` / `int` | `Integer.parseInt(value)` | `null` / `0` |
| `Long` / `long` | `Long.parseLong(value)` | `null` / `0L` |
| `Double` / `double` | `Double.parseDouble(value)` | `null` / `0.0` |
| `Boolean` / `boolean` | `Boolean.parseBoolean(value)` | `null` / `false` |

Special methods: `toString` → `"InterfaceName[nodeName]"`, `hashCode` → `hash(id, traitInterface)`, `equals` → same node ID + same trait interface.

**To add a new coercion:**

1. Add a case to `coerce(String value, Class<?> returnType)` in `ThingProxyHandler`
2. If primitive: add the default to `primitiveDefault(Class<?> returnType)`
3. Update `TypeRegistry.mapReturnType()` in `mindmap-intelligence` to map the Java return type to a schema type string
4. Add a test in `thing-api`'s `ThingTest`

**Constraint:** `thing-api` has zero dependencies — coercions cannot use external libraries.

#### Creating Trait Interfaces

**Placement.** Platform-provided traits live in `mindmap-intelligence` (`Personable`, `Projectlike`, `Organisational`, `Eventlike`). Consumer-defined traits live in any module — `as()` works with any interface.

**Convention.** Trait names are PascalCase (matching the Java interface simple name). `is("Personable")` is case-sensitive. Method names map to property keys — `birthday()` reads `property("birthday")`. Methods should return `Optional<String>` (preferred), `String`, or primitive wrappers.

**Checklist for a new platform trait:**

1. Create the interface in `mindmap-intelligence` (e.g., `Eventlike.java`) — methods return `Optional<String>`
2. Create a `TraitRule` implementation (e.g., `EventlikeTraitRule.java`) — `@ApplicationScoped`, define the matching criteria
3. Add the mapping to `TypeRegistry.CORE_TYPES` if this trait corresponds to a core subgraph type
4. Add tests in `StandardTraitRulesTest`

#### Flyway Migration Pattern — V4 Reference

`V4__subgraph_type_string.sql` in `mindmap-sqlite` is a reference pattern for data conversion migrations:

```sql
-- Convert SubgraphType enum names to lowercase strings
UPDATE mindmap_subgraph SET type = LOWER(type);

-- Add unique constraint on (tenant_id, type) to prevent duplicate type names per tenant
CREATE UNIQUE INDEX IF NOT EXISTS mindmap_subgraph_tenant_type_idx
    ON mindmap_subgraph (tenant_id, type);
```

**Pattern elements:** data conversion (`LOWER`) + structural constraint (unique index). The index is created with `IF NOT EXISTS` for idempotency. The unique constraint on `(tenant_id, type)` enables `TypeRegistry`'s race-condition recovery — when two JVM instances try to create the TYPE_SYSTEM subgraph simultaneously, the loser hits the constraint, catches the exception, and re-queries.

**File location convention:** `<module>/src/main/resources/db/<module-slug>/migration/V<N>__<description>.sql`.

---

## Dependencies

### Depends On

| Repo / Library | Module | How |
|---|---|---|
| `casehub-platform-api` | `rag`, `memory-api` | `CurrentPrincipal`, `TenancyConstants` (tenant isolation), `Path` (CBR hierarchical scoping) |
| `casehub-desiredstate-api` | `memory` | `@CloudEventType` for `CbrOutcomeConsumer` |
| LangChain4j | `rag`, `memory-cbr-embedding` | RAG pipeline, `OnnxEmbeddingModel`, Qdrant `EmbeddingStore`, `EmbeddingModel` for CBR semantic text similarity |
| `io.qdrant:client` | `rag`, `memory-qdrant` | Qdrant gRPC client for hybrid search + CBR reconciliation |
| `quarkus-scheduler` | `rag`, `rag-tracking`, `memory-cbr-tracking` | `@Scheduled` polling and retention scheduling |
| HikariCP | `rag-tracking`, `memory-cbr-tracking`, `memory-sqlite` | SQLite connection pooling |
| Flyway | `memory-jpa`, `memory-cbr-jpa`, `rag-tracking`, `memory-cbr-tracking` | Schema migrations |
| ONNX Runtime JVM | `inference-runtime` | Model session management |
| HuggingFace Tokenizers JNI | `inference-runtime` | Tokenization |
| Apache Tika | `rag-tika` | Binary document parsing |

### Depended On By

| Repo | Module | How |
|---|---|---|
| `casehub-eidos` | `runtime` | `ScalarRegressor` for dynamic epistemic confidence |
| `casehub-openclaw` | `casehub` | `TextClassifier` for `ActionRiskClassifier` SPI |
| `casehub-engine` | `runtime` | `NliClassifier` for hallucination detection; `CaseRetriever` for fact space prompt compilation; `AgentTrustProvider` bridge |
| Hortora | various | `inference-*` modules (SPLADE, reranking); `rag-*` modules (corpus retrieval engine) |

---

## Current State

All inference, RAG, CBR, and agent memory modules shipped. Active development on agent memory patterns (experience stream, relationships, reflection, personality-aware retrieval) and retrieval model quality (embedding evaluation, BGE-M3, ColBERT, RelevanceEvaluator).

| Area | What shipped |
|------|-------------|
| Inference Foundation | `InferenceModel` SPI with sealed `InferenceInput` (Text + Tensor), ONNX runtime, task adapters (NLI, classification, tensor classification, regression, reranking), SPLADE sparse embeddings, BGE-M3 multi-modal embeddings, Quarkus CDI extension |
| RAG Pipeline | Three-leg hybrid search (dense + sparse + BM25) with configurable fusion (RRF/DBSF/CC); `FusionWeightsConfig` with per-leg weights; per-query weight multipliers + effectiveWeight(); `PayloadBoostCaseRetriever` quality rescore; `MatryoshkaEmbeddingModel`; `DenseQuantization`; ColBERT multi-vector scalar quantization; per-leg embedding separation; corrective RAG + cross-encoder reranking; query expansion (HyDE, template, step-back); retrieval tracking; pre-ingestion dedup gate; `RetrievalAnalyzer` (document stats, query clusters with MinHash, correlation graph, document impact) |
| CBR | Typed feature values (9 field types, 7 value types); `SimilaritySpec` sealed (6 similarity functions incl. DTW + edit distance); weighted per-field scoring; plan adaptation SPI (caseType-aware, variantId tracking); plan ensemble analysis SPI; temporal decay (3 strategies); hierarchical scoping with ScopeDecay; supersession + reinstate + audit; trend detection + enrichment; cross-encoder reranking; embedding-based text similarity; trust-weighted retrieval; outcome-weighted retrieval + CloudEvent feedback; CBR retention (age + count + trust purge); trust trajectory purge; reconciliation with Qdrant; JPA/PostgreSQL backend; retrieval tracking (retrieval + adaptation + ensemble); erasure notification; personality transition schema; scan/discoverTenants admin operations; CbrSuggestions/FeatureStatistics |
| Agent Memory | Five backends (in-memory, JPA, SQLite, Mem0, Graphiti — all blocking, reactive tier removed); `MemoryEmitter` fire-and-forget wrapper; `MemoryOrder.SALIENCE` (recency x confidence); unified `Confidence` record (origin + value); confidence-based retention purge with MemoryRetentionScheduler; permission-aware queries; paginated scan; cross-tenant erasure |
| Corpus | Append-only zip archives, flat filesystem, composite multi-backend; change tracking; compaction; integrity checks |
| Score Fusion | `fusion-api` tier-1 module — weighted RRF + CC algorithms, `CamelCaseExpander` for BM25 preprocessing. Shared by RAG and CBR |
| Evaluation | Python ML pipelines: code-domain embedding evaluation (#49), strategy classifier with CNN-Attention + ONNX export (#75, #76) |

Native image gate passed. Service deploys in JVM mode by design. Reachability metadata retained for downstream native consumers.

---

## Design Documents

- [casehubio/parent#158](https://github.com/casehubio/parent/issues/158) — casehubio/neocortex tracking issue
- [casehubio/parent#164](https://github.com/casehubio/parent/issues/164) — casehub-neocortex-rag tracking issue
- [Hortora/spec#15](https://github.com/Hortora/spec/issues/15) — Hortora alignment
- [casehubio/neocortex ARC42STORIES.MD](https://github.com/casehubio/neocortex/blob/main/ARC42STORIES.MD) — authoritative architecture record (Matryoshka section 4, oversampling section 6, dimension consistency section 7, naming section 8)
- Design specs: `docs/specs/` (65+ design documents covering all modules)
- Authoritative inference design: `Hortora/spec: docs/superpowers/specs/2026-06-03-onnx-inference-module-design.md`
