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
| `rag-api/` | `casehub-neocortex-rag-api` | Pure Java | `EmbeddingIngestor` SPI, `CaseRetriever` SPI; `CaseContextRetriever` (multi-corpus retrieval utility — per-corpus error isolation, sourceDocumentId dedup keeping highest score, score-sorted truncation; static `toMap()` for serializable output); `RetrievalTracker` SPI (record, findRecords, findFeedback, purgeOlderThan); `RelevanceEvaluator` SPI (evaluateChunks); `ColBertRelevanceEvaluator` (pure Java score-threshold mapper with `calibrate()` factory); `QueryExpander` SPI; `RetrievalQuery` (text + expandedText + weightMultipliers); `RetrievalAnalyzer` (static utility: documentStats, unretrievedDocuments, qualitySignals, lowRelevanceQueries, zeroHitQueries, queryFrequency, correlationGraph, queryClusters with MinHash LSH, documentImpact); `CorrelationGraph`, `QueryCluster`, `DocumentImpact`, `EdgeStats`, `DocumentStats`, `QualitySignal`, `QualityThresholds` value types; `MetadataExtractor` SPI; `CursorStore` SPI; `PayloadFilter`; `ScoredGrade`; `RetrievalOutcome` enum; CDI events: `RetrievalRecorded` |
| `rag/` | `casehub-neocortex-rag` | Quarkus module | LangChain4j pipeline, Qdrant, three-leg hybrid search (dense + sparse + BM25) with configurable fusion; `FusionWeightsConfig` (unified per-leg weights: dense/sparse/bm25/quality, replacing CcWeightsConfig); `HybridCaseRetriever` with `effectiveWeight()` (global weight x per-query multiplier); `PayloadBoostCaseRetriever` (@Decorator @Priority(60) — post-fusion quality rescore for RRF/DBSF, CC integrates quality natively); `DedupEmbeddingIngestor` (@Decorator @Priority(50) — cosine similarity dedup gate before indexing); per-leg embedding separation (dense uses searchText(), sparse/ColBERT use text() via embedSeparate()); `SeparateModelEmbedder` (`EmbeddingModel` + optional `SparseEmbedder` to `MultiModalEmbedder`, @DefaultBean); `MultiModalEmbedderProducer` (@DefaultBean, @IfBuildProperty gated); `MatryoshkaMultiModalEmbedder.wrapIfNeeded()`; `DenseQuantization` (binary/scalar for dense vectors); `ColbertQuantizationConfig`; `BM25Index` + `BM25IndexRegistry` (in-memory, `CodeDomainTokenizer`); tenancy isolation via `TenantGuard`; `CorpusIngestionService` (event-driven directory-watcher + @Scheduled polling fallback) |
| `rag-testing/` | `casehub-neocortex-rag-testing` | Test library | `InMemoryCursorStore` @Alternative @Priority(1); `InMemoryRelevanceEvaluator` @Alternative @Priority(1) (fixed grade, NaN score); `InMemoryRetrievalTracker` @Alternative @Priority(1); `RetrievalTrackerContractTest` abstract base (20 tests); in-memory `EmbeddingIngestor` + `CaseRetriever` stubs |
| `rag-tika/` | `casehub-neocortex-rag-tika` | JVM library | Apache Tika document parser — `TikaDocumentParser` extracts text + metadata from binary documents (PDF, DOCX, etc.) |
| `rag-crossencoder/` | `casehub-neocortex-rag-crossencoder` | JVM library | Two CDI decorators: `CorrectiveCaseRetriever` (@Priority(100), grades chunks via `evaluateChunks()` polymorphically); `RerankingCaseRetriever` (@Priority(75), cross-encoder score re-ordering). `CrossEncoderRelevanceEvaluator` (ONNX reranker). `CrossEncoderBeanProducer` (cross-encoder when available, ColBERT fallback). `CragConfig` with ColBERT sub-group (separate thresholds). Config-gated: `casehub.rag.crag.enabled`, `casehub.rag.reranking.enabled` |
| `rag-expansion/` | `casehub-neocortex-rag-expansion` | JVM library | `QueryExpandingCaseRetriever` @Decorator. Expanders: `LlmQueryExpander` (HyDE), `TemplateQueryExpander`, `StepBackQueryExpander`. Multi-query fan-out with RRF fusion. `NoOpQueryExpander` @DefaultBean. `ExpansionConfigValidator` startup warning. `ExpansionConfig`. Config: `casehub.rag.expansion.mode=llm|step-back|template` |
| `rag-tracking/` | `casehub-neocortex-rag-tracking` | JVM library | `TrackingCaseRetriever` (@Decorator @Priority(40), stamps chunks with retrieval ID). `SqliteRetrievalTracker` (SQLite + HikariCP WAL + Flyway). `RetentionScheduler` (ScheduledExecutorService daemon, purge every 24h). CDI events: `RetrievalRecorded`. Config: `casehub.rag.tracking.enabled=true`, `casehub.rag.tracking.retention.days` (default 90) |

### Corpus Modules

| Module | artifactId | Type | Purpose |
|--------|-----------|------|---------|
| `corpus-api/` | `casehub-neocortex-corpus-api` | Pure Java | Corpus storage and change-tracking SPIs — `CorpusStore`, `CorpusReader`, `ChangeSource`, `WatchableChangeSource`, `ChangeListener`; `CorpusIntegrity` SPI for health checks |
| `corpus/` | `casehub-neocortex-corpus` | JVM library | `ZipCorpusStore` (rolling archives, chain manifest), `FlatCorpusStore`, `CompositeCorpusStore`; change tracking; `Compactor`; `CorpusMigrator`; `ZipIntegrityChecker` |

### Agent Memory Modules

| Module | artifactId | Type | Purpose |
|--------|-----------|------|---------|
| `memory-api/` | `casehub-neocortex-memory-api` | Pure Java | `CaseMemoryStore` SPI (store, query, erase, eraseEntity, eraseById, eraseEntityAcrossTenants, scan, purge, discoverTenants, storeAll); `DelegatingCaseMemoryStore` (forwarding base class for CaseMemoryStore decorators — parallels `DelegatingCbrRecordStore`); `GraphCaseMemoryStore` SPI; `MemoryInput` (with `Confidence` field — origin + value [0.0-1.0]); `Memory`; `MemoryQuery`; `MemoryOrder` (CHRONOLOGICAL, RELEVANCE, SALIENCE); `MemoryRetentionPolicy` (tenantId + domain + maxAgeDays + minConfidence); `MemoryScanRequest`; `MemoryCapability` enum (incl. DISCOVER_TENANTS, SCAN, PURGE); `CaseEnrichmentStep` SPI; `MemoryDomain`; `MemoryAttributeKeys`; `EraseRequest`; `StoreAllResult`; `StoreFailure` |
| `memory-core/` | `casehub-neocortex-memory-core` | Pure Java | Framework-neutral memory runtime POJOs — zero CDI. `EventRecorderCore<E, R, F, S>` (abstract base for typed event recording with shared `record()`/`recordAll()` batch logic); `ExperienceRecorderCore` (extends EventRecorderCore, implements ExperienceRecorder); `EngagementRecorderCore` (extends EventRecorderCore); `RelationshipProcessor`; `ReflectionOrchestratorCore`; `MemoryEmitterCore`; `CbrOutcomeProcessor` |
| `memory/` | `casehub-neocortex-memory` | CDI module | `MemoryEmitter` (@ApplicationScoped fire-and-forget wrapper — error isolation, SecurityException propagates); `NoOpCaseMemoryStore` @DefaultBean; `CaseEnrichmentDecorator` (@Decorator); `ErasureNotificationCaseMemoryStore` (@Decorator @Priority(45) extends `DelegatingCaseMemoryStore`); `MemoryRetentionScheduler` (scheduled confidence-based purge across discovered tenants) |
| `memory-inmem/` | `casehub-neocortex-memory-inmem` | Backend | @Alternative @Priority(10) volatile ConcurrentHashMap — test + ephemeral + discoverTenants + scan + purge |
| `memory-jpa/` | `casehub-neocortex-memory-jpa` | Backend | @ApplicationScoped JPA/PostgreSQL + Flyway + FTS via websearch_to_tsquery + discoverTenants |
| `memory-sqlite/` | `casehub-neocortex-memory-sqlite` | Backend | @Alternative @Priority(1) SQLite + HikariCP WAL + FTS5 + discoverTenants |
| `memory-mem0/` | `casehub-neocortex-memory-mem0` | Backend | @Alternative @Priority(1) Mem0 REST client adapter — blocking direct REST |
| `memory-graphiti/` | `casehub-neocortex-memory-graphiti` | Backend | @Alternative @Priority(2) Graphiti REST GraphCaseMemoryStore — blocking direct REST, incl. graphQuery() |
| `memory-testing/` | `casehub-neocortex-memory-testing` | Test library | `CbrRecordStoreContractTest` abstract base (164 tests); `CbrRetrievalTrackerContractTest` (10 tests); `CbrCbrPlanEnsembleAnalyzerContractTest` (7 tests); `InMemoryCbrRetrievalTracker`; test stubs for memory SPIs |

### CBR Memory Modules

| Module | artifactId | Type | Purpose |
|--------|-----------|------|---------|
| `memory-api/` | (same artifact) | Pure Java | `CbrRecordStore` SPI (composite extending CbrRecordOps + CbrRecordRetrieval + CbrRecordLifecycle + CbrRecordAdmin ISP sub-interfaces); `DelegatingCbrRecordStore` (forwarding base class for decorators); `CbrRecord` hierarchy (TextualCbrRecord, CbrFeatureRecord, PlanCbrRecord); `CbrGuidanceRecord` (with features `Map<String, FeatureValue>` + `List<CbrGuidanceStep>` structured steps); `CbrQuery` (CaseTypeScope sealed: Specific/AllInDomain for cross-type retrieval, weights, vectorWeight, RetrievalMode, FusionStrategy, filters, TemporalDecay, scope Path, ScopeDecay, withFeatures(), crossType() factory); `CbrFilter` sealed hierarchy (8 variants); `FeatureValue` sealed (7 types); `FeatureField` sealed (9 types); `SimilaritySpec` sealed (6 types); `CbrSimilarityScorer`; `CbrRecordValidator`; `CbrRecordSchema` (with optional learningRate); `DtwSimilarity` + `LbKeogh` (O(n) lower-bound pruning); `EditDistanceSimilarity`; `WarpingConstraint` sealed; `TrendAnalyzer`, `TrendSpec`, `TrendType`, `TrendProfile`, `TrendFieldNaming`; `TemporalDecay` sealed (3 types); `ScopeDecay` sealed (3 types); `CbrCbrPlanAdapter` SPI; `CbrCbrPlanEnsembleAnalyzer` SPI; `CbrOutcome`; `CbrRetentionPolicy` (with minTrustScore); `CbrScanRequest`; `CbrRecordSummary`; `SupersessionStatus`; `AgentTrustProvider` SPI; `TrustWeightingFunction` SPI; `OutcomeWeightingFunction` SPI; `ExplanationRenderer` SPI; `CbrRetrievalTracker` SPI (record, findTraces, purgeOlderThan, feedback with `CbrFeedbackOutcome` and `CbrRetrievalFeedback`); `PersonalityTransitionSchema`; `FeatureStatistics`; `CbrSuggestions`; CDI events: `CbrRetrievalRecorded`, `CbrAdaptationRecorded`, `CbrEnsembleRecorded`, `CbrRecordErased` (sealed: ByRequest, ByEntity, ByScope), `CbrRecordSuperseded` (sealed: ByCase, ByFilter, ByIds), `CbrRecordReinstated` (sealed: ByCase, ByFilter, ByIds) |
| `memory/` | (same artifact) | CDI module | CBR decorator chain (all @Decorator on CbrRecordStore): `TrendEnrichmentCbrRecordStore` (@Priority(90) — enriches TimeSeries features with derived trend metrics on store/retrieve); `ScopeDecayCbrRecordStore` (@Priority(85) — scope-distance score decay); `TemporalDecayCbrRecordStore` (@Priority(80) — temporal decay post-scoring); `OutcomeWeightingCbrRecordStore` (@Priority(65) — confidence-based score modulation, @IfBuildProperty); `TrustWeightedCbrRecordStore` (@Priority(60) — trust authority + trajectory scoring, @IfBuildProperty); `TrackingCbrRecordStore` in memory-cbr-tracking (@Priority(50) — retrieval tracking); `ErasureNotificationCbrRecordStore` (@Priority(45) — fires CbrRecordErased CDI events). Plus: `NoOpCbrRecordStore` @DefaultBean; `CbrOutcomeConsumer` (@ObservesAsync @CloudEventType — bridges CloudEvent to recordOutcome); `CbrRetentionScheduler` (scheduled age+count+trust purge); `TrustRetentionService` (trust-trajectory-based purge via AgentTrustProvider); `DefaultOutcomeWeightingFunction` (linear interpolation); `DefaultTrustWeightingFunction` (authority + trajectory); `DefaultExplanationRenderer`; `NoOpCbrCbrPlanAdapter` @DefaultBean; `NoOpCbrCbrPlanEnsembleAnalyzer` @DefaultBean |
| `memory-cbr-inmem/` | `casehub-neocortex-memory-cbr-inmem` | Backend | @Alternative @Priority(2) — in-memory stub for tests, clearCases() for isolation (clears cases, preserves schemas) |
| `memory-cbr-jpa/` | `casehub-neocortex-memory-cbr-jpa` | Backend | @Alternative @Priority(3) JPA/PostgreSQL. `CbrRecordEntity` with JSONB features (`Map<String, FeatureValue>`), plan traces, outcome tracking, supersession metadata. Flyway migrations |
| `memory-qdrant/` | `casehub-neocortex-memory-qdrant` | Backend | @ApplicationScoped gRPC client. `QdrantCbrRecordStore` — payload filters (categorical/numeric/text + structured: CategoricalList/NestedObject/ObjectList with dot-notation) + dense vector search + SPLADE sparse embeddings + BM25 server-side inference + dynamic 2-4 leg hybrid fusion (CC weight renormalization) + notBefore temporal filtering + per-inner-field payload indexes + dimension validation + collection schema evolution. `CbrReconciliationService` (three-phase: orphan cleanup + reindex + vector enrichment backfill, Micrometer metrics). `CbrPointBuilder` (structured value serialization). Two-pass retrieveSimilar() with batch precompute for semantic text fields |
| `memory-cbr-embedding/` | `casehub-neocortex-memory-cbr-embedding` | Backend | `EmbeddingTextSimilarity` — `LocalSimilarityFunction` for semantic text field cosine similarity, batch `precompute()` via `embedAll()`, cache-backed `compute()`. Depends on memory-api + langchain4j-core only |
| `memory-cbr-crossencoder/` | `casehub-neocortex-memory-cbr-crossencoder` | Backend | `RerankingCbrRecordStore` (@Decorator @Priority(75)). Sigmoid-normalized scores. Double-reranking guard via `CbrMatch.reranked()`. Config: `casehub.cbr.reranking.enabled` |
| `memory-cbr-tracking/` | `casehub-neocortex-memory-cbr-tracking` | Backend | `TrackingCbrRecordStore` (@Decorator @Priority(50) — records retrieval via CbrRetrievalTracker). `SqliteCbrRetrievalTracker` (SQLite + HikariCP WAL + Flyway). `TrackingCbrCbrPlanAdapter` (@Decorator @Priority(50) — fires CbrAdaptationRecorded after adaptation, `casehub.cbr.adaptation-tracking.enabled`). `TrackingCbrCbrPlanEnsembleAnalyzer` (@Decorator @Priority(50) — fires CbrEnsembleRecorded, `casehub.cbr.ensemble-tracking.enabled`). @Scheduled retention purge. Config: `casehub.cbr.tracking.enabled=true` |

### Knowledge Pipeline Modules

| Module | artifactId | Type | Purpose |
|--------|-----------|------|---------|
| `knowledge-pipeline-api/` | `casehub-neocortex-knowledge-pipeline-api` | Pure Java | `KnowledgePipelineService` SPI (search + promote + refreshStale), `SpatialCacheStore` SPI (set/get/nearby/within/remove/expire/findExpired/discoverTenants), `EntityMatcher<T>` SPI, `SubsumptionRule` SPI, `TermNormalizer` SPI, `QueryClassifier` SPI, `ResearchSessionService` SPI, `KnowledgeQuery` sealed hierarchy (TextSearch/NearbySearch/CategorySearch), `CachedEntity`, `NormalizedQuery`, `MatchResult`, `MatchTier`, `PromotionRequest`/`PromotionResult`, `ResearchSession`, `ResearchState`, `CacheFilter`, `SpatialBucket` (geohash), `BoundingBox` |
| `knowledge-pipeline/` | `casehub-neocortex-knowledge-pipeline` | JVM library | `KnowledgePipelineOrchestrator` (end-to-end search + promote), `SqliteSpatialCacheStore` (SQLite + R*Tree spatial index), `InMemorySpatialCacheStore` (test stub), `SpatialSubsumptionRule` (spatial circle containment + category narrowing), `CacheDecayPolicy` (field-type TTLs), `CacheEvictionScheduler` (session-aware eviction), `EntityResolutionEngine` (blocking + matching + three-tier decision), `PlaceMatcher` (place-specific matching signals), `EntityPromoter` (three-stage MindMap promotion), `ResearchOrchestrator` (lifecycle with MindMap RESEARCH_AREA subgraphs), `DedupIndexStore`/`EntityMetadataStore`/`QueryCacheStore`/`ResearchSessionStore` (all SQLite via sqlite-support), `CacheKeyGenerator`, `CacheEntityIdGenerator`, `Haversine`, `PhoneNormalizer`, `KnowledgePipelineMetrics` (Micrometer) |

### Entity Tokenisation Modules

| Module | artifactId | Type | Purpose |
|--------|-----------|------|---------|
| `entity-token/` | `casehub-neocortex-entity-token` | Pure Java, zero deps | `EntityIdentity` (type-extensible entity record — canonical name parts, pronouns, preferred forms, aliases → surface forms), `TokenFormat` (parse/emit/extractEntityIds/stripTokens for `{{entity_id:variant:surface_form}}` inline tokens), `SurfaceFormTrie` (Aho-Corasick multi-pattern scanner with word-boundary filtering), `EntityTokeniser` (text + trie → tokenised text), `EntityRenderer` (tokenised text + identity resolver → display text, `RenderMode` CANONICAL/SURFACE), `ParsedToken`, `SurfaceForm`, `TrieMatch`, `EntityReferenceIndex` SPI (entity→source reverse lookup), `InMemoryEntityReferenceIndex` |
| `entity-token-cdi/` | `casehub-neocortex-entity-token-cdi` | CDI module | `EntityIdentityCache` (MindMapStore → EntityIdentity per tenant), `SurfaceFormTrieManager` (debounced trie rebuild), `TokenisingEnrichmentStep` (CaseEnrichmentStep — tokenises memory text at store time via Aho-Corasick), `TokenisingBiographyPreprocessor` (BiographyProfilePreprocessor — strips bracket syntax, triggers trie rebuild after import), `EntityReferenceRecordingObserver` (CDI observer — records entity references from tokenised memories), `SqliteEntityReferenceIndex` (SQLite + HikariCP WAL + Flyway), `EntityMergeTokenHandler`, `EntityErasureTokenHandler`, `MemoryErasureReferenceHandler` |

### CAPS Modules

| Module | artifactId | Type | Purpose |
|--------|-----------|------|---------|
| `caps-api/` | `casehub-neocortex-caps-api` | Pure Java | `CapsTopology` (species-level network), `CapsNode` (INPUT/MEDIATING/OUTPUT with BIPOLAR/UNIPOLAR `NodeRange`), `CapsConnection`, `ConnectionWeight` (dual-weight: excitatory + inhibitory overlay + decay resistance + precision), `NodeState` (activation threshold + resting activation), `AgentCapsState`, `SettlingResult` (convergence/oscillation/max-iterations + `BehavioralAttractor` list), `SituationClassifier` SPI, `ClassificationResult`, `CapsEngine` SPI (topology + state management + settling + weight update), `DistortionDefinition` |
| `caps-engine/` | `casehub-neocortex-caps-engine` | JVM library | `CapsTopologyLoader` (YAML classpath resource), `CapsSettler` (synchronous parallel update, per-node-type activation: logistic for unipolar, tanh for bipolar, momentum damping, convergence/oscillation detection, peak-based attractor extraction with baseline-relative `attractorStrengthMargin`), `CapsWeightUpdater` (Rescorla-Wagner + Pearce-Hall), `DispositionWeightMapper`, `SqliteCapsEngine` (SQLite + HikariCP WAL + Flyway), `RuleBasedSituationClassifier` (keyword-matching, multi-label), `SubThoughtSituationDecorator` |
| `caps-testing/` | `casehub-neocortex-caps-testing` | Test library | `InMemoryCapsEngine` @Alternative, `CapsEngineContractTest` abstract base, `CognitiveEmergenceTest` (scenario builder for disposition seeding, experience application, CAPS settling, attractor assertions) |
| `caps-llm-classifier/` | `casehub-neocortex-caps-llm-classifier` | JVM library | `LlmSituationClassifier` (LLM-backed SituationClassifier via SessionPipeline; topology-aware system prompt with node IDs and keywords), `LlmSituationClassifierProducer`, eval test suite |
| `caps-situation-cbr/` | `casehub-neocortex-caps-situation-cbr` | JVM library | `CbrCachingSituationDecorator` (@Decorator @Priority(60) on SituationClassifier — checks CBR for cached classification via embedding similarity before delegating to LLM; SHA-256 deterministic caseId; model-version cache invalidation) |

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

`CbrRecordValidator` — consolidated store-time, query-time, and filter validation. Temporal field validation: ascending timestamps, inner field types.

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

`ScopeDecayCbrRecordStore` (@Decorator @Priority(85)) applies scope-distance score multiplier, re-sorts, and filters by `minSimilarity` after decay. Null scopeDecay = pass-through.

`eraseByScope(Path, tenantId)` — bulk scope-based erasure for operational cleanup (#158). Aggregate adjustment on entity erasure — recomputes higher-scope aggregates when source cases are erased (#159).

### CBR Plan Adaptation

`CbrCbrPlanAdapter` SPI — `adapt(caseType, CbrMatch<PlanCbrRecord>, features)` returns `AdaptedPlan` (wrapping `List<AdaptedStep>`). `caseType` is a first-class parameter for type-specific adaptation rules. `AdaptedStep` carries `bindingName`, nullable `capabilityName`, `workerName`, `stepOutcome`, `priority`, `parameters`, `AdaptationAction`, `reason`. `CbrCbrAdaptationTrace` for audit with `retrievalTraceId` link. `CbrPlanStep` record with optional `variantId` for variant tracking.

`NoOpCbrCbrPlanAdapter` @DefaultBean — returns all steps RETAINED, zero behavioral change.

### CBR Plan Ensemble Analysis

`CbrCbrPlanEnsembleAnalyzer` SPI — operates after per-plan `CbrCbrPlanAdapter` adaptation. `analyze(caseType, List<CbrMatch<PlanCbrRecord>>, List<AdaptedPlan>, features)` examines multiple adapted plans for consensus/divergence and synthesizes an `EnsemblePlan`. `StepConsensus` classifies per-step agreement as UNANIMOUS, CONSENSUS, CONTESTED, MINORITY, or UNIQUE — with worker/outcome/priority distributions. `EnsemblePlan` carries `ensembleConfidence` [0,1] and `inputPlanCount`.

`NoOpCbrCbrPlanEnsembleAnalyzer` @DefaultBean — picks best-scoring plan, reports inputPlanCount=1 with UNANIMOUS agreement.

`CbrCbrPlanEnsembleAnalyzerContractTest` abstract base (7 tests) in `memory-testing`.

### CBR Outcome Feedback and Weighting

`CbrOutcome` — Outcome enum with EMA `adjustConfidence()` and `DEFAULT_LEARNING_RATE`. `CbrRecordStore.recordOutcome()` for CBR Revise feedback loop. `CbrRecordSchema` supports optional `learningRate` (validated [0,1]) for per-caseType EMA speed.

`OutcomeWeightingCbrRecordStore` (@Decorator @Priority(65)) modulates retrieval scores by case confidence. `DefaultOutcomeWeightingFunction` — linear interpolation `score*(1-alpha+alpha*confidence)`. Config: `casehub.cbr.outcome-weighting.enabled`, `casehub.cbr.outcome-weighting.influence` (default 0.3).

`CbrOutcomeConsumer` — @ObservesAsync @CloudEventType(CbrEventTypes.CBR_OUTCOME). Deserializes CloudEvent data to `CbrOutcomeData`, bridges to `CbrRecordStore.recordOutcome()`. Depends on casehub-desiredstate-api.

### CBR Trust-Weighted Retrieval

`TrustWeightedCbrRecordStore` (@Decorator @Priority(60)) modulates retrieval scores by source trust authority + optional trust trajectory. Per-retrieval trajectory cache.

`AgentTrustProvider` (@FunctionalInterface SPI) — `OptionalDouble currentTrustScore(agentId)`. Implemented by engine bridge to TrustScoreSource.

`TrustWeightingFunction` SPI — `apply(similarity, trustScore, trustTrajectory)`. Default: authority `score*(1-alpha+alpha*trustScore)`, trajectory `max(0.5, 1+beta*delta)` for declining only. Config: `casehub.cbr.trust-weighting.influence` (default 0.3), `trajectorySensitivity` (default 0.5).

### CBR Retention and Trust Purge

`CbrRetentionPolicy` — record with `tenantId`, `domain`, `caseType`, `maxAgeDays`, `maxCasesPerType`, `minTrustScore`. `CbrRecordStore.purge(CbrRetentionPolicy)`.

`CbrRetentionScheduler` — @ApplicationScoped scheduled purge across discovered tenants and configured caseTypes.

`TrustRetentionService` — evaluates agent trust trajectories via `AgentTrustProvider`. Paginated scan (`CbrScanRequest` / `CbrRecordSummary`) identifies cases from agents below `minCurrentTrust`, erases them. Config: `casehub.cbr.trust-retention.enabled`, `casehub.cbr.trust-retention.min-current-trust`.

### CBR Active Memory Management

**Temporal decay:** `TemporalDecay` sealed interface with three implementations: `HalfLife(Duration)` (exponential), `Linear(Duration zeroAt)`, `Step(Duration cutoff, double afterCutoff)`. `TemporalDecayCbrRecordStore` (Decorator Priority 80) applies decay to retrieval scores based on case `storedAt`.

**Supersession:** `CbrRecordLifecycle` (sub-interface of `CbrRecordStore`) provides single-case and bulk supersession. Single: `boolean supersede(caseId, tenantId, supersedingCaseId, reason)` and `boolean reinstate(caseId, tenantId)` — return true only on state transition. Bulk: `supersedeMatching(tenantId, domain, caseType, filters, reason)`, `supersedeAll(caseIds, tenantId, reason)`, `reinstateMatching(tenantId, domain, caseType, filters)`, `reinstateAll(caseIds, tenantId)` — return count of affected cases. `findCaseIds(tenantId, domain, caseType, filters)` on `CbrRecordRetrieval` for filter-based case lookup (non-superseded only). `getSupersessionStatus()` returns `SupersessionStatus` with audit metadata (wasReinstated() convenience). `findSupersededCases()` for audit queries. CDI events: `CbrRecordSuperseded` (sealed: ByCase, ByFilter, ByIds) and `CbrRecordReinstated` (sealed: ByCase, ByFilter, ByIds) fired by `SupersessionNotificationCbrRecordStore` (@Decorator @Priority(44)).

### Trend Detection

`TrendSpec` — record holding `Set<TrendType>` and `ChronoUnit` (default HOURS), attached optionally to `FeatureField.TimeSeries`. `TrendType` enum (7 types): SLOPE, DELTA, VOLATILITY, ACCELERATION, CHANGE_POINTS, DURATION, OBSERVATION_COUNT. `isPerField()` discriminates per-inner-field vs per-TimeSeries.

`TrendAnalyzer` — static utility: `analyze()` computes trend metrics from observations (least-squares regression, Welford's stddev, half-split acceleration, CUSUM change-point detection — all O(n)). `enrichFeatures()` returns new map with derived Numeric values. `expandSchema()` idempotent expansion with heuristic ranges.

`TrendFieldNaming` — deterministic derived field naming: `{tsName}_{type}_{innerField}` for per-field, `{tsName}_{type}` for per-TimeSeries. Underscore separators avoid Qdrant dot-notation conflict.

`TrendEnrichmentCbrRecordStore` (Decorator Priority 90) — intercepts registerSchema (expandSchema), store (enrichFeatures on case), retrieveSimilar (enrichFeatures on query). Schema-driven activation via TrendSpec presence, no @IfBuildProperty gate.

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

`ErasureNotificationCbrRecordStore` (@Decorator @Priority(45)) fires `CbrRecordErased` CDI events after erasure. `CbrRecordErased` is a sealed interface with three variants: `ByRequest`, `ByEntity`, `ByScope`. Clock injection for testability.

### Inline Entity Tokenisation Pipeline

Entity references are embedded directly in stored text as inline tokens (`{{entity_id:variant:surface_form}}`), replacing the former standoff annotation approach. The pipeline operates at two points:

**Write time:** `TokenisingEnrichmentStep` (CaseEnrichmentStep, priority 100) runs during `CaseMemoryStore.store()` via `CaseEnrichmentDecorator`. Scans text against the `SurfaceFormTrie` (Aho-Corasick, built from `EntityIdentityCache`) and replaces matching names with inline tokens. Longest match wins for overlapping surface forms.

**Read time:** `EntityRenderer` resolves tokens to current canonical names using `EntityIdentity` records. `RenderMode.CANONICAL` uses the variant-resolved name (e.g., first name, title form); `RenderMode.SURFACE` uses the original surface form.

**Entity ID extraction:** `TokenFormat.extractEntityIds(text)` returns `Set<String>` — regex extraction of all entity IDs from tokenised text. Used by cognitive consumers (`EntityOverlapModulationFactor`, `EntityAwareSalienceStrategy`, `LlmReflectionSynthesizer`) instead of the former annotation index queries.

**Biography import:** `TokenisingBiographyPreprocessor` (BiographyProfilePreprocessor) strips bracket syntax (`word[alias]` → `word`) during `preprocess()` and triggers trie rebuild in `afterImport()` so `TokenisingEnrichmentStep` has fresh surface forms for subsequent memory stores.

**Reference tracking:** `EntityReferenceRecordingObserver` records entity→memory reverse lookups in `EntityReferenceIndex` from parsed tokens in stored memories.

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

**CbrRecordStore chain (memory):**
| Priority | Decorator | Module |
|----------|-----------|--------|
| 90 | `TrendEnrichmentCbrRecordStore` | memory |
| 85 | `ScopeDecayCbrRecordStore` | memory |
| 80 | `TemporalDecayCbrRecordStore` | memory |
| 75 | `RerankingCbrRecordStore` | memory-cbr-crossencoder |
| 65 | `OutcomeWeightingCbrRecordStore` | memory |
| 60 | `TrustWeightedCbrRecordStore` | memory |
| 50 | `TrackingCbrRecordStore` | memory-cbr-tracking |
| 45 | `ErasureNotificationCbrRecordStore` | memory |
| 44 | `SupersessionNotificationCbrRecordStore` | memory |

**MindMapStore chain (mindmap):**
| Priority | Decorator | Module |
|----------|-----------|--------|
| 80 | `DerivedEdgeDecorator` | mindmap |
| 70 | `TraitApplicationDecorator` | mindmap |
| 65 | `AffectTrajectoryDecorator` | mindmap |
| 30 | `MindMapStoreIdleTracker` | mindmap |

### Store SPI API Conventions

Verb vocabulary and parameter conventions for the three store SPIs. New SPIs and methods should follow these conventions.

**CRUD verb vocabulary:**

| Operation | CaseMemoryStore | MindMapStore | CbrRecordStore |
|-----------|----------------|--------------|----------------|
| Create | `store` | `addNode/addEdge/addAlias`, `createSubgraph` | `store` |
| Read (single) | — | `getNode/getEdge/getSubgraph` | `getSupersessionStatus` |
| Read (search) | `query` (semantic), `scan` (paginated) | `search` (FTS), `nodesIn/neighbors/bridgeEdges` (traversal) | `retrieveSimilar` (similarity), `scan` (paginated), `findCaseIds` (ID lookup) |
| Update | — (append-only) | `updateNode/updateSubgraph`, `mergeNodes` | `recordOutcome` |
| Delete (bulk) | `erase` | `eraseNode/eraseSubgraph/eraseEntity` | `erase/eraseEntity/eraseByScope` |
| Delete (single) | `eraseById` | `removeEdge/removeAlias` | — |
| Purge | `purge` (retention) | — | `purge` (retention) |
| Lifecycle | — | `supersede/reinstate` | `supersede/reinstate` |
| Admin | `discoverTenants` | `registerVocabulary` | `registerSchema`, `discoverTenants` |

**Verb conventions for new methods:**
- **store** for create operations in memory/CBR stores (not `add`, `create`, `insert`)
- **add** for graph entity creation in MindMap (node, edge, alias)
- **create** for container creation in MindMap (subgraph)
- **get** for single-entity retrieval by ID
- **erase** for bulk deletion; **remove** for single-entity removal in MindMap
- **purge** for retention-based bulk cleanup
- **supersede/reinstate** for soft lifecycle transitions
- **discover** for tenant enumeration; **register** for schema/vocabulary setup

**Parameter ordering:**
- `tenantId` is always the **last** explicit parameter, unless varargs occupies that slot
- For methods that take an encapsulating request object (e.g., `EraseRequest`, `CbrQuery`), tenantId is inside the object — no explicit parameter
- MindMapStore and CbrRecordStore both follow this convention; CaseMemoryStore embeds tenantId in input records

**Return types:**
- Bulk deletion/purge methods return primitive `int` (count of affected records), never boxed `Integer`
- Single-entity deletion returns `void` (e.g., `removeEdge`, `eraseById`)
- Lifecycle transitions return `boolean` (success) for single-case, `int` (count) for bulk

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
            || node.property("email").isPresent()
            || node.property("gender").isPresent()
            || node.property("pronounsSubject").isPresent()
            || node.property("canonicalFirst").isPresent();
        boolean hasEdges = edges.stream()
            .anyMatch(e -> "parent-of".equals(e.edgeType())
                || "works-at".equals(e.edgeType()));
        return hasProperties || hasEdges;
    }
}
```

The trait name must be PascalCase, matching the Java interface simple name.

**Declarative rules.** `DeclarativeTraitRule` wraps a `RuleCondition` tree — a sealed interface with 11 variants: `HasProperty`, `PropertyEquals`, `PropertyIn`, `NotHasProperty`, `HasEdgeType`, `HasEdgeTypes`, `HasAnyEdge`, `InSubgraphType`, `AnyOf`, `AllOf`, `Not`. Declared in YAML cognitive profiles, deserialized by `DeclarativeTraitRuleDeserializer`, loaded by `DeclarativeRuleRegistry` — global rules from `rules/*.yaml` + per-agent overrides (name-based merge: local rule with same name suppresses global).

**How traits get applied.** `TraitApplicationDecorator` (@Decorator @Priority(70)) intercepts `addNode`, `updateNode`, `addEdge`, and `removeEdge`. After the delegate operation completes, it evaluates all rules (programmatic + declarative) against the affected node. If any rule matches a trait not yet present, the trait is added via `NodeUpdate`. If no rule matches a previously present trait, it is removed. A `ThreadLocal` reentrancy guard prevents infinite recursion (trait update triggers `updateNode` which would re-evaluate). When a `PrincipalId` is available, per-agent declarative rules are resolved via `RuleResolver`; otherwise all rules fire.

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

### MindMap Decorators

The MindMapStore decorator chain applies cross-cutting concerns to all store operations. Decorators are CDI `@Decorator` beans ordered by `@Priority` — highest priority executes first (outermost). See the [CDI Decorator Priority Chain Summary](#cdi-decorator-priority-chain-summary) for the full chain.

#### DerivedEdgeDecorator (Priority 80)

Forward-chaining rule engine for automatic edge derivation. Intercepts `addEdge`, `removeEdge`, `eraseNode`, `eraseSubgraph`.

**On addEdge:** After the delegate stores the trigger edge, evaluates all `DerivedEdgeRule` implementations (programmatic CDI beans + declarative via `RuleResolver`). Each rule returns zero or more `EdgeInput`s. Derived edges carry provenance properties (`mindmap.derived=true`, `mindmap.derived.trigger-edge-id`, `mindmap.derived.rule-name`). Derived edges are added via recursive `this.addEdge()` — forward chaining through the full decorator chain.

**Cycle prevention:** `ThreadLocal<Integer>` derivation depth counter, stops at `DEFAULT_MAX_DEPTH = 3`.

**Truth maintenance on removeEdge:** `ConcurrentHashMap<String, Set<String>>` maps trigger edge IDs to derived edge IDs. When a trigger edge is removed, all derived edges are recursively removed first.

**Per-principal rule resolution:** With a `PrincipalId`, resolves per-agent declarative rules via `RuleResolver.derivedEdgeRules(principalId)`. Without, all rules fire.

**DerivedEdgeRule SPI:** Implement `name()` + `derive(sourceNode, trigger, store) → List<EdgeInput>`. Register as `@ApplicationScoped` CDI bean. `DeclarativeDerivedEdgeRule` provides a YAML-driven alternative with `triggerEdgeTypes` filter, optional `TraversalSpec` (follow edges with depth limit + cycle guard), and `EdgeDerivation` templates.

**Key interaction:** Recursive `this.addEdge()` traverses the full chain — derived edges also trigger trait evaluation (Priority 70) and idle tracking (Priority 30).

#### ConfidenceDecayCdiDecorator (Priority 55)

Read-side CDI decorator (`@Decorator @Priority(55)` in `mindmap/` module). Intercepts `getNode`, `nodesIn`, `search`, `neighbors`, `bridgeEdges`. Configurable half-life via `casehub.mindmap.confidence.half-life-days` (default 30).

**Decay formula:** `confidence × 2^(-hoursSince / halfLifeHours)`. Uses `Confidence.decayReference()` as the anchor timestamp. No-op when `decayReference` is null or elapsed time ≤ 0.

**Post-search filtering:** After decay, `search()` re-applies `MindMapQuery.minConfidence()` — nodes that decayed below threshold are removed from results. Returns `DecayedNode`/`DecayedEdge` wrapper records that delegate all methods except `confidence()`.

#### Vocabulary Normalization

Edge type alias resolution is handled by store implementations directly via `MindMapStore.registerVocabulary()`. `EdgeTypeDefinition` declares a canonical name, a set of aliases, and an optional decay half-life. Edges whose type matches a registered vocabulary entry receive `ValidationTier.REGISTERED`; others get `UNVALIDATED`. Normalization is implemented in `InMemoryMindMapStore` and `SqliteMindMapStore`.

#### AffectTrajectoryDecorator (Priority 65)

Intercepts `updateNode` only. Write-through — delegates to the underlying store, then records PAD changes. Compares before/after PAD values (pleasure/arousal/dominance). If any dimension changed, stores a `domain="affect"` memory via `CaseMemoryStore` and fires `AffectRecorded` CDI event.

**Graceful degradation:** `Instance<CaseMemoryStore>` — if no memory store on classpath, PAD changes silently skip. Memory store failures after successful graph update are caught and logged — never propagated to callers. Early-exit optimization: checks whether `NodeUpdate` contains PAD fields before reading the "before" node.

#### MindMapStoreIdleTracker (Priority 30)

Intercepts all 16 mutation operations (`addNode`, `updateNode`, `addEdge`, `removeEdge`, `mergeNodes`, `supersede`, `reinstate`, etc.). Each stamps the current time on `IdleTracker` (`@ApplicationScoped`, single `volatile Instant`), then delegates.

**Consumer:** `ConsolidationScheduler` checks `idleTracker.isIdle(Duration.ofMinutes(1))` before running consolidation phases — avoids competing with active user writes.

### MindMap Graph Operations

#### MindMapQuery

Record with 13 fields for store queries. Required: `tenantId`, `limit` (positive). Optional filters: `subgraphId`, `text` (FTS), `edgeType` (nodes having an edge of this type), `traits` (Set), `minConfidence`, `confidenceOrigin` (STATED/INFERRED/SPECULATED), `includeSuperseded` (default false), `validAfter`/`validBefore`/`updatedAfter` (temporal), `callerPrincipal` (visibility filtering). Factory `of(tenantId, limit)` + immutable `with*()` builder methods.

#### MindMapCapability

Enum with 12 values gating store operations: `TRAVERSAL`, `MERGE`, `VOCABULARY`, `ALIAS`, `SUBGRAPH`, `SEARCH`, `SUPERSESSION`, `ERASE_NODE`, `ERASE_SUBGRAPH`, `ERASE_ENTITY`, `CROSS_TENANT_ERASE`, `GRAPH_ANALYSIS`. Each store declares its supported set via `capabilities()`. `requireCapability()` throws `MindMapCapabilityException` if missing. Not all backends support all operations — capability checking enables safe feature detection.

#### Merge

`mergeNodes(keepNodeId, removeNodeId, tenantId)` returns `MergeResult` with audit metadata: `survivingNodeId`, `edgesRepointed`, `aliasesMerged`, `duplicateEdgesRemoved`, `traitsMerged`, and `List<MergeConflict>` (property key, kept value, discarded value). Requires `MERGE` capability. The "keep" node survives; the "remove" node's edges are repointed, aliases transferred, and the node erased.

#### Supersession and Reinstatement

`supersede(targetId, supersedingId, reason, tenantId)` / `reinstate(targetId, tenantId)` — return true only on state transition. `SupersessionStatus` record carries audit metadata: `supersededAt`, `supersedingId`, `reason`, `reinstatedAt`, with `wasReinstated()` convenience and `NOT_SUPERSEDED` constant.

Superseded nodes are excluded from `search()` by default; `MindMapQuery.withIncludeSuperseded(true)` re-includes them. Requires `SUPERSESSION` capability.

#### NodeRef and OverlayRef

`NodeRef` — `record(scheme, id, qualifier)` for external references. Scheme + id required, qualifier optional. Attached to nodes via `NodeInput.refs()`.

`OverlayRef` — convention for perspectival affect overlays built on NodeRef. `SCHEME = "overlay"`. `of(sharedNodeId)` creates a NodeRef linking an overlay to its shared counterpart. `sharedNodeId(MindMapNode)` extracts the link. Combined with the `"overlay"` trait and `agentId` property for per-agent identification. `PerspectivalResolver` uses this convention to find and merge overlays.

#### MindMapAnalyzer

Static utility in `mindmap/` — pure Java graph analysis. All methods require `GRAPH_ANALYSIS` capability.

| Category | Methods |
|----------|---------|
| Structural | `orphanNodes` (delegates to `MindMapStore.nodesWithoutEdges()`), `degreeCentrality` (sorted), `subgraphDensity` (edges / n(n-1)) |
| Quality | `unvalidatedEdgeRatio`, `contradictions` (multiple outgoing edges of same type), `lowConfidenceCluster` (fraction below threshold) |
| Temporal | `staleNodes` (decayReference/updatedAt exceeds threshold, sorted by age) |
| Centrality | `betweennessCentrality` (exact Brandes' BFS, O(V(V+E))), `approximateBetweennessCentrality(store, subgraphId, tenantId, k)` (sampled Brandes with k random sources, deterministic seed, O(k(V+E))) |
| Community | `kCores(k)` — O(V+E) iterative removal: prune nodes with degree < k, find connected components in survivors. Each `KCore` has `nodeIds` + `density`. Used by `CommunitySummaryPhase` in the consolidation pipeline |

### MindMap Intelligence

#### MindMapExtractor

`@Singleton` CDI bean in `mindmap-intelligence/`. LLM-driven entity and relationship extraction from conversation text. Depends on `MindMapStore` and `Instance<AgentProvider>` (graceful degradation — returns empty/null when no LLM available).

**Public API — three entry points:**
- `parse(text, tenantId, recentEntityNames)` → `ParsedExtraction` (nullable) — LLM extraction without persistence. Returns null on blank input, unavailable LLM, or malformed response.
- `apply(parsed, tenantId)` / `apply(parsed, tenantId, principalId)` → `ExtractionResult` — persists a `ParsedExtraction` to the store. Optional `PrincipalId` scopes created nodes/edges to a specific agent.
- `extract(text, tenantId, recentEntityNames)` → `ExtractionResult` — convenience composition of `parse()` + `apply()`. Returns `ExtractionResult.EMPTY` on failure.

`ParsedExtraction`, `ParsedEntity`, `ParsedRelationship`, `ParsedContradiction` are public records — callers of `parse()` work with these directly.

**Flow (parse phase):**
1. Gathers up to 20 existing graph nodes as context — resolves recent entity names via `store.resolveNode()`, then searches for capitalized multi-word terms via rule-based `extractCandidateTerms()` heuristic (joins consecutive capitalized words, filters common pronouns/articles)
2. Builds a structured prompt with conversation text + graph context (nodes with confidence, traits, edges) + recently mentioned entities
3. Parses JSON response (`ExtractionJsonParser`) for entities, relationships, and contradictions

**Flow (apply phase):**
4. For each entity: resolves via `store.resolveNode()` (updates existing or creates new), with `MindMapConfidenceDefaults.forOrigin()`, `"llm-extraction"` provenance, and optional `PrincipalId`

**Type normalization:** `normalizeType()` → `strip().toLowerCase()`, defaults to `SubgraphTypes.GENERAL`.

**Extension:** Swap the `AgentProvider` SPI to change the LLM backend. The system prompt and entity type vocabulary are hardcoded.

#### ConversationBridge and Async Enrichment Pipeline

`ConversationBridge` (`@ApplicationScoped`) provides immediate node availability from text, with deferred LLM enrichment.

**Pipeline:**
1. `process(cleanedText, tenantId, recentEntityNames, principalId, confidenceOrigin)` — segments text by paragraph boundaries (`\n\n+`). Each segment → one MindMapNode in the GENERAL subgraph with caller-specified confidence (`ConfidenceOrigin` param, defaults to STATED when null) and `"conversation-bridge"` provenance. `PrincipalId` (nullable) scopes created nodes to a specific agent.
2. Records access for created nodes via `RetrievalAccessTracker` (if available via `Instance<>`)
3. Fires `ExtractionRequested` CDI event asynchronously (`fireAsync`) — carries `principalId` for downstream propagation
4. `ExtractionRequestedObserver` (`@ApplicationScoped`, `@ObservesAsync`) receives the event, calls `MindMapExtractor.parse()` + `apply(principalId)` for agent-scoped extraction, records access for extracted entities, then supersedes each segment node with the first extracted entity — replacing raw text with structured knowledge

**Design:** ConversationBridge is fast (rule-based, no LLM). LLM enrichment is async — nodes are available immediately for retrieval, then upgraded when extraction completes.

#### CognitiveLoader

`@ApplicationScoped` with `@PostConstruct`. Bridges YAML cognitive profiles into the MindMap vocabulary system. Iterates all `CognitiveDefaults` from `CognitiveDefaultsRegistry`; for each with a non-null `vocabulary()`, calls `store.registerVocabulary()`. `Instance<MindMapStore>` + `Instance<CognitiveDefaultsRegistry>` for graceful degradation. Observes `CognitiveProfilesReloaded` CDI event for vocabulary re-registration on hot-reload, with per-profile `VocabularyConflictException` handling.

**Extension:** Add a YAML cognitive profile with a `vocabulary:` section — CognitiveLoader picks it up automatically via classpath scanning. For hot-reload, set `casehub.cognitive.profiles-dir` to a filesystem directory — `CognitiveProfileWatcher` monitors it and fires reload events on change.

#### CuriositySignalGenerator

`@ApplicationScoped`, implements `CuriositySignalProvider` SPI. Generates curiosity signals per subgraph from five categories:

| Category | Signals |
|----------|---------|
| STRUCTURAL | Orphan nodes, sparse subgraphs (density < 0.1) |
| QUALITY | Contradictions, low-confidence clusters (>50% below threshold), high unvalidated edge ratio (>30%) |
| TEMPORAL | Stale nodes (configurable threshold) |
| CENTRALITY | Top-N by betweenness + degree centrality |
| PROXIMITY | Approaching events (future `validFrom`), past events |

**Three post-processing stages:**
1. **Category weights** — per-category score multipliers from `CuriosityConfig.categoryWeight()`
2. **Affect dampening** — queries affect trajectory via `AffectTrajectoryAnalyzer`. WORSENING trends boost signals (capped by `maxBoostFactor`); IMPROVING trends dampen (capped by `improvingDampenCap`); high arousal volatility adds boost (capped by `volatilityBoostCap`). Falls back to `snapshotFactor(node)` when memory store unavailable or < 2 samples
3. **Topical distance** — BFS from signal target to recent entity IDs; score × `1/(1+distance)`, max depth from `CuriosityConfig.maxBfsDepth()`

#### RecurrenceRule and RecurrenceGenerator

`RecurrenceRule` — record in `mindmap-api`. RFC 5545 RRULE subset: `Frequency` enum (DAILY/WEEKLY/MONTHLY/YEARLY), interval (≥1), optional count, optional until, optional byDay. `parse(String)` / `toString()` for serialization. Compact constructor validates freq not null, interval ≥ 1.

`RecurrenceGenerator` — static utility in `mindmap-intelligence`. `generateInstances(template, rule, horizon)` advances from `template.validFrom()` by the rule's interval, producing `NodeInput` instances with template properties (minus `rrule`), plus `template-node-id`, `recurrence-index`, and `status=planned`.

### Cognitive Index Internals

The `cognitive-index` module provides cross-store aggregation — derived views over MindMap, Memory, and CBR stores. All beans use `Instance<T>` for graceful degradation when backing stores are absent.

#### TemporalIndex

`@ApplicationScoped`. Stateless derived view — re-queries underlying stores on every call, no persistent state. All three stores (MindMap, Memory, CBR) injected via `Instance<T>`; missing stores silently skipped.

**Query dispatch:** `TemporalQuery` selects which stores to include via `StoreKind` set (MINDMAP, MEMORY, CBR; defaults to all). Memory store requires non-empty `entityIds` — silently skipped when empty. MindMap supports two modes: historical (ordered by `updatedAt`) and upcoming (ordered by `validFrom` for future events).

**Merge:** All entries sorted chronologically via `TemporalEntry.compareTo()` (oldest first), truncated to `query.limit()`.

**Key types:** `TemporalEntry(timestamp, source, tenantId, confidence)`. `TemporalSource` sealed: `FromMindMap(MindMapNode)`, `FromMemory(Memory)`, `FromCbr(CbrMatch<?>)`. `TemporalQuery` with factory methods `since()`, `window()`, `upcoming()` + `withSources()`, `withEntityIds()`, `withCallerPrincipal()` wither methods.

**Extension:** `TemporalRanker` `@FunctionalInterface` — `double score(TemporalEntry, Instant)`. Static factory `recency()` provides inverse-seconds-elapsed scoring. `rank()` default method re-orders a list.

#### AffectTrajectoryAnalyzer

Pure static utility. Computes affect trajectory from `domain="affect"` memories sorted by `createdAt`.

**Algorithms:** Least-squares regression for pleasure slope and dominance slope (time in hours). Population standard deviation for arousal volatility.

**Output:** `AffectTrajectory` record — `pleasureSlope`, `arousalVolatility`, `arousalSlope`, `dominanceSlope`, `TrendDirection` (IMPROVING/WORSENING/STABLE based on pleasure slope vs threshold), `rateOfChange`, `sampleCount`. All three slopes computed via least-squares regression; arousalVolatility via population standard deviation. Edge cases: 0–1 samples → all-zero STABLE. Null PAD values treated as 0.0.

#### TemporalFocus

Pure static utility. Scores `TemporalEntry` items by proximity/recency plus affect trajectory modifiers.

**Scoring:** MindMap nodes with future `validFrom` get proximity scoring (inverse days-until × `proximityScale`); everything else gets recency scoring (inverse hours-since). Trajectory modifiers: WORSENING → boost (capped by `worseningBoostCap`), IMPROVING → dampen (fixed `improvingDampenFactor`), STABLE → 1.0. High arousal volatility (> 0.3) adds additional boost.

**Output:** `List<AttentionItem>` sorted by salience descending. `AttentionItem(entry, salience, reason)` with human-readable reason strings. `TemporalFocusConfig` with tunable thresholds and `subgraphProximityWeights` map. `ranker()` returns a `TemporalRanker` — composable with TemporalIndex.

#### CognitiveProfile

`@ApplicationScoped`. Cross-store entity resolution — resolves a unified `EntityKnowledge` record for a single entity across MindMap + Memory stores.

**Resolution:** `CognitiveProfileQuery` with `byId(nodeId, tenantId)` or `byName(entityName, tenantId)` factories (mutually exclusive). Configurable domain set (defaults: experience, relationship, reflection, mood, engagement, affect), edge inclusion toggle, memory limit. `withAsSeenBy(PrincipalId)` enables perspectival resolution.

**Entity ID collection:** Gathers IDs from node ID, node name, and `NodeRef` entries with `scheme="memory"` — enabling cross-reference following between stores.

**Perspective-aware resolve():** When `asSeenBy` is set, the resolution pipeline: (1) resolve shared node, (2) apply perspectival overlay via `PerspectivalResolver` (package-private, internalized) before any derived computation, (3) collect entity IDs from the merged node, (4) query memories scoped by `withCallerPrincipalId(asSeenBy)`, (5) compute trajectory from principal-scoped affect memories. Without `asSeenBy`, the pipeline is unchanged — steps 2 and 4's scoping are skipped.

**Multi-agent comparison:** `compare(CognitiveProfileQuery, Set<PrincipalId>)` returns `Map<PrincipalId, EntityKnowledge>`. Loads all overlay nodes once via `PerspectivalResolver.loadAllOverlays(tenantId)` (single `MindMapStore.search()` call), partitions by `agentId` property, then resolves per-agent: merge overlay → collect entity IDs → query principal-scoped memories → compute trajectory. Each entry's `perceiver` is set. Callers pass the result to `SocialComparison.compare()` for divergence metrics.

**Output:** `EntityKnowledge(node, edges, memories, trajectory, unresolvedRefs, tenantId, perceiver)`. `perceiver` is null for shared views, non-null for perspectival views. `unresolvedRefs` = NodeRefs with scheme ≠ "memory" (external references the profile couldn't follow). Affect trajectory computed via `AffectTrajectoryAnalyzer`.

#### PerspectivalMerge and PerspectivalResolver

**PerspectivalMerge** — public pure static utility. Merges a shared `MindMapNode` with a private overlay node. Overlay wins for PAD, confidence, and properties; shared wins for identity (id, name, subgraph, temporal bounds, traits, refs). Returns a `MergedNode` implementing `MindMapNode`.

**PerspectivalResolver** — package-private class (internalized inside `CognitiveProfile`). Finds overlay nodes by querying for the `"overlay"` trait in a tenant, filtering by `agentId` property matching the caller's `PrincipalId`. Maps overlays to shared nodes via `OverlayRef.sharedNodeId()`. `resolve(sharedNodes, principal, tenantId)` returns a new list with overlay merges applied where they exist; non-overlay nodes pass through unchanged. `loadAllOverlays(tenantId)` returns all overlay nodes for batched comparison — used by `CognitiveProfile.compare()` for single-scan multi-agent resolution.

#### SocialComparison

Pure static utility following the `AffectTrajectoryAnalyzer` pattern. `compare(Map<PrincipalId, EntityKnowledge>)` computes multi-agent divergence metrics from already-resolved perspectives (typically the output of `CognitiveProfile.compare()`).

**Output:** `PerspectivalComparison` record containing:
- `perspectives` — per-agent `AffectSnapshot` (PAD values from merged perspectival node + trajectory)
- `unassessedAgents` — agents with any null PAD dimension, excluded from distance/difference computations
- `distances` — `PadDistanceMatrix` with pairwise Euclidean distances in PAD space
- `dimensionDifferences` — per-`PadDimension` signed `PairwiseDifferences` (canonical pair ordering: `pair.a() - pair.b()`)
- `trajectoryAlignment` — pairwise 3D slope vector cosine similarity (`[pleasureSlope, arousalSlope, dominanceSlope]`) + `TrendAgreement` (ALIGNED, DIVERGENT, MIXED, INSUFFICIENT)

**Canonical ordering:** `AgentPair` enforces `a.value() < b.value()` lexicographically. Signed differences follow this ordering — `difference(alice, bob)` returns `alice - bob` when alice < bob, negated otherwise.

#### DomainActivation

`@ApplicationScoped` CDI bean. Cross-subgraph affect correlation via time-bucketed PAD aggregation and pairwise DTW similarity. `Instance<MindMapStore>` + `Instance<CaseMemoryStore>` for graceful degradation.

**Resolution flow:**
1. For each subgraph: `MindMapStore.search()` for member entities
2. For each entity: query `domain="affect"` memories with `withCallerPrincipalId(principal)` + `withSince(from)`, filter by `to` in Java (no `withUntil()` on MemoryQuery)
3. Time-bucket into `double[][]` arrays (rows = buckets, cols = pleasure/arousal/dominance). Empty buckets skipped — DTW handles unequal lengths natively
4. Compute `AffectTrajectory` per subgraph via `AffectTrajectoryAnalyzer.analyze()`
5. Pairwise 3D DTW via `PadDtw.compute()`. `CorrelationStrength` from similarity score
6. Optional mood correlation: context-partitioned mood PAD via `activeContextIds`, time-bucketed DTW with Sakoe-Chiba banding, circular shift significance testing
7. Optional experience correlation: event-triggered affect windows via `EventTriggeredAnalyzer`, Δ(affect) pre/post each event per PAD dimension with bootstrap 95% CI

**Privacy by construction:** `DomainActivationQuery.principal` is required, non-nullable. Cross-principal analysis not expressible. Returns `Optional.empty()` when any subgraph has zero entities or zero memories.

**Key types:** `DomainActivationQuery` (with `between()` factory, `bucketDuration` default 24h, `withContextDomains(Set<MemoryDomain>)` for mood/experience opt-in, `withEventWindow(Duration)`, ≥1 subgraph when contextDomains non-empty), `DomainActivationResult` (`contextCorrelations` Map<MemoryDomain, Map<String, DomainCorrelation>> for mood DTW, `eventImpacts` Map<MemoryDomain, Map<String, EventImpact>> for experience windows), `DomainSignal` (per-subgraph trajectory + counts), `DomainPair` (canonical ordering), `DomainCorrelation` (DTW similarity + alignment + CorrelationStrength + pValue + contextAttributedCount/totalMoodCount), `EventTriggeredAnalyzer` (static utility: Δ(affect) pre/post experience events per PAD dimension, bootstrap 95% CI), `EventImpact`, `EventTypeImpact`, `ConfidenceInterval`.

#### PadDtw

Package-private pure static utility. Lightweight Dynamic Time Warping for multi-dimensional `double[][]` time series (rows = time points, columns = PAD dimensions). Euclidean distance per time point, O(n×m) DP matrix, backtrace alignment path. `1.0 / (1.0 + normalizedCost)` scoring. `WarpingConstraint` overload supports all 3 sealed variants (Unconstrained, SakoeChibaBand, ItakuraParallelogram). `significanceTest()` uses circular shift surrogates (preserves autocorrelation) with deterministic seed, returns `SignificanceResult` (similarity + pValue + CorrelationStrength). Independent of CBR's `DtwSimilarity` — same algorithm, incompatible type interfaces (`FeatureValue`/`FeatureField` vs raw doubles).

#### CognitiveDefaults and CognitiveDefaultsRegistry

**CognitiveDefaults** — immutable config record (14 fields): agentId (required), personality (`PersonalityWeights`), moodBaseline, curiosity (`CuriosityConfig`), temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary (`MindMapVocabulary`), services map (for `@Named` SPI selection), traitRules, derivedEdgeRules. Extensive `with*()` methods for immutable updates. `empty(agentId)` factory.

**CognitiveDefaultsRegistry** — `@ApplicationScoped`. `@PostConstruct` classpath scan of `cognitive-profiles/*.yaml`. Custom Jackson `ObjectMapper` with YAML factory + four custom deserializers (PersonalityWeights, RuleCondition, DeclarativeTraitRule, DeclarativeDerivedEdgeRule). Lookup: `forAgent(agentId)` → Optional, `forAgentOrDefaults(agentId)` → defaults if absent. Duplicate agentId detection on load (throws `IllegalStateException`).

#### CognitiveDerivationEngine

Pure static utility. Derives `CognitiveDefaults` from `DescriptorView` (agentId, `DispositionAxes`, disposition profile as `List<WeightedTerm>`, goals, `FormationPadSummary`) via 10 derivation pathways:

| Pathway | Input | Output |
|---------|-------|--------|
| Personality | Jungian function weights | `PersonalityWeights` per memory domain |
| Mood baseline | Disposition axes | PAD resting point |
| Curiosity | Axes + goals | Category weights |
| Temporal focus | Goal keywords | Subgraph proximity weights |
| CBR strategy | Rule-following + risk | minSimilarity, decay, retrieval mode |
| Social cognition | Social orient + conflict | Trust rate, conflict interpretation |
| Graph structure | Disposition profile | CONNECTIVE/CATEGORICAL/BALANCED |
| Extraction bias | Function weight ratios | Relationship bias, affect sensitivity |
| Appraisal weights | Disposition profile + axes | urgencyWeight, relationshipWeight, fearOnsetThreshold (from JPAF profile), selfStandardsStrictness, otherStandardsStrictness (from DispositionAxes: ruleFollowing + socialOrient) |
| Personality dominance | FormationPadSummary | Dominance-weighted reward ratio → arousal thresholds for tier activation |

**`deriveAndMerge()`** overlays explicit `CognitiveDefaults` fields (from YAML profile) on the derived base. Explicit non-null fields win. Primary integration point — YAML profiles can override any derived value.

#### RuleResolver SPI and DeclarativeRuleRegistry

`RuleResolver` (in `mindmap-api`) — SPI for agent-scoped rule resolution. Four methods: `traitRules(agentId)`, `allTraitRules()`, `derivedEdgeRules(agentId)`, `allDerivedEdgeRules()`. Used by `TraitApplicationDecorator` and `DerivedEdgeDecorator` — decouples mindmap from cognitive-index.

`DeclarativeRuleRegistry` (`@ApplicationScoped` in `cognitive-index`) implements `RuleResolver`. Two-layer rule loading: global `rules/*.yaml` at `@PostConstruct` + per-agent overrides from `CognitiveDefaultsRegistry`.

**Merge semantics:** `LinkedHashMap` keyed by rule name. Global rules loaded first; per-agent rules overwrite by key — local rule with same name suppresses global. `traitRules(agentId)` and `derivedEdgeRules(agentId)` return merged lists. `allTraitRules()` / `allDerivedEdgeRules()` merge across all profiles.

#### Modulation Framework

**Foundation** (`cognitive-api`): `ModulationProfile<T>` maps items to confidence/PAD/timestamp via accessor functions. `ModulationFactor<T>` (`@FunctionalInterface`) — composable scoring multiplier. `RetrievalModulator` applies all factors via multiplication, sorts by composite score descending.

**Pre-built profiles** (`cognitive-index`): `ModulationProfiles.MEMORY` (wired to `Memory` accessors), `ModulationProfiles.NODE` (wired to `MindMapNode` accessors).

**Pre-built factors:** `recencyDecay(halfLife, now)` (exponential), `confidenceWeight()` (direct value, defaults 1.0 when null), `moodCongruence(mood, influence)` (PAD distance in 3D space, influence ∈ [0,1] scales effect), `domainWeight(PersonalityWeights)` (Memory-specific domain lookup).

`ModulationContext` — convenience record: `MoodState` + `PersonalityWeights` + `Instant now`. Factory `of(now)` + wither methods.

### Consolidation Pipeline

Background knowledge graph maintenance in `mindmap-intelligence/consolidation/`. Runs phases sequentially per tenant on a scheduled interval, gated by idle detection and concurrency control.

#### ConsolidationScheduler

`@ApplicationScoped`. Single `ScheduledExecutorService` daemon thread, default 5-minute interval (`casehub.mindmap.consolidation.interval-minutes`).

**Tick gates (all must pass):**
1. `ReentrantLock.tryLock()` — skips if previous tick still running
2. `idleTracker.isIdle(Duration.ofMinutes(1))` — won't run during active graph writes
3. `DISCOVER_TENANTS` capability check on memory store

**Per-tick flow:** Calls `beginTick()` on `AccessFrequencyPhase`, enumerates tenants via `memoryStore.discoverTenants()`, computes curiosity-driven `subgraphPriority` per tenant (subgraph IDs ordered by signal score via `CuriositySignalGenerator`), then runs each phase in `@Priority` order. Error isolation per phase per tenant — one failure doesn't stop others.

#### ConsolidationPhase SPI

```java
public interface ConsolidationPhase {
    String name();
    void run(String tenantId, List<String> subgraphPriority);
}
```

Ordering via `@Priority` annotation (lower = earlier). `subgraphPriority` is a hint — phases that iterate subgraphs should process priority subgraphs first. **To add a new phase:** create `@ApplicationScoped @Priority(N)` implementing `ConsolidationPhase` — auto-discovered by CDI.

#### AccessFrequencyPhase (Priority 10)

Flushes write-behind `storageStrength` counters from `RetrievalAccessTracker` to MindMap node properties.

**Tick-generation caching:** `beginTick()` increments a counter. `swapAndReset()` snapshot is taken once per tick (not per tenant) — ensures multi-tenant correctness: all tenants see the same snapshot, preventing double-counting.

For each accessed node: reads existing `storageStrength` property (default 0), adds the increment, writes back with `lastAccessed` timestamp.

#### BehavioralSynthesisPhase (Priority 19)

Bridges graduated experience nodes from the COGNITIVE subgraph to crystallised behavioral attractors in the BEHAVIORAL subgraph via CAPS settling.

**Pipeline:** Scans COGNITIVE subgraph for graduated experience nodes → classifies each into CAPS input activations via `SituationClassifier` (virtual thread per classification for LLM-backed classifiers) → settles the CAPS network → extracts behavioral attractors from settled output nodes → creates/strengthens `CapsGenerated` nodes in BEHAVIORAL subgraph with EMA strength updates.

**Drive intensity merging:** When multiple experiences activate the same CAPS input node, activations are merged via max (not sum) to prevent saturation from repeated similar experiences.

**CBR outcome recording:** Collects `caseId`s from `ClassificationResult`, evaluates settling quality via `ClassificationOutcomeEvaluator` (convergence × max attractor strength), and calls `recordOutcome()` for outcome-weighted retrieval. Convergence scores: CONVERGED=1.0, OSCILLATION=0.3, MAX_ITERATIONS=0.1.

**Graceful degradation:** When `CapsEngine`, `SituationClassifier`, or `CbrRecordStore` are not on the classpath, the phase is a no-op.

#### MergeDetectionPhase (Priority 20)

Two-layer similarity scoring for automatic node deduplication per subgraph. Uses prefix bucketing (first 3 chars of lowercase name) to reduce O(n²) pairwise comparison, and bulk pre-loads all neighbor sets upfront to amortize SQL queries.

**Layer 1 — Name similarity:** Jaro-Winkler (threshold 0.85) within same prefix bucket. Pairs below skip entirely.

**Layer 2 — Neighbor overlap:** Jaccard similarity on pre-loaded neighbor node ID sets (no per-pair SQL).

**Combined score:** `0.6 × nameSim + 0.4 × neighborOverlap`. Actions:
- ≥ 0.9 → auto-merge via `store.mergeNodes()`. Keep node chosen by higher `storageStrength` (depends on AccessFrequencyPhase running first)
- [0.7, 0.9) → flag via `mergeCandidate` property for human review

Nodes with `Summary` trait excluded. `maxPerPass = 10`.

#### CommunitySummaryPhase (Priority 30)

K-core clustering + summary generation with hash-based invalidation.

**Flow per subgraph:**
1. Compute k-cores via `MindMapAnalyzer.kCores()` (default k=3, `minClusterSize = 4`)
2. Filter `Summary`-trait nodes from core membership
3. Compute `coreHash` (SHA-256 of sorted node IDs) and `memberHash` (SHA-256 of sorted `id:name` pairs)
4. **Stale cleanup:** erase Summary nodes whose `coreHash` no longer matches any current core
5. **Existing check:** matching `coreHash` + `memberHash` → skip; matching `coreHash` but different `memberHash` → regenerate title
6. **New summary:** create node with `Summary` trait, `summarizes` edges to core members, properties: `coreHash`, `memberHash`, `memberCount`, `generatedAt`

`maxPerPass = 5`. `AgentProvider` injected for future LLM-based title generation.

#### GoalResolutionPhase (Priority 35)

Progressive resolution of the cognitive goal graph. Runs 5 steps in order:

1. **Prune:** Distant goals (aspirational/long horizon, urgency ≤0.7) with decomposed sub-goals → collapse sub-goals, drop resolution to "low"
2. **Expand:** Approaching goals (urgency >0.7, resolution != "high") → invoke `CognitiveGoalDecomposer` SPI, create sub-goal nodes with `decomposes-into` edges, update resolution to "high". No-op when decomposer is NoOp (standalone mode)
3. **Merge:** Similar sub-goals across different parents (Jaro-Winkler ≥0.85) → merge via `store.mergeNodes()`, create `contributes-to` edges to both parents
4. **Revise:** Blocked goals whose blockers are all completed/abandoned → unblock (status → active). Periodic cycle detection on blocks/requires/decomposes-into edges — removes one edge to break the cycle
5. **Sync:** Linked goals (with `eidos-goal-name` property) → query `GoalLifecycleProvider` SPI grouped by `principalId`, update MindMap `status` to match eidos, clear `decay-signal` properties

Always processes the GOAL subgraph regardless of `subgraphPriority`.

#### GoalAffectPhase (Priority 37)

Computes anticipated affect (PAD dimensions) on goal nodes. Two paths:

**OCC path** (when `GoalAppraisal` SPI is available): delegates to `HeuristicGoalAppraisal`, which produces typed OCC emotions (Hope, Fear, Satisfaction, Disappointment, Distress, Pity) from goal status, priority, urgency, feasibility, and relationship scores. The dominant emotion's PAD projection is written to the node. `AppraisalWeights` (personality-derived) modulate urgency sensitivity, relationship weight, and fear onset threshold.

**Legacy path** (fallback): direct status → PAD mapping:
- **Active:** arousal = urgency × 0.8, positive pleasure/dominance
- **Blocked + urgent:** negative pleasure (frustration), high arousal
- **Completed:** positive pleasure (satisfaction)
- **Dormant:** reduced arousal, neutral pleasure/dominance

Both paths use dynamic urgency from `target-date` property when present (via `GoalUrgency`), falling back to the static `urgency` property. Updates PAD via `store.updateNode()`. Emits `AFFECT_CHANGE` attention signals. `AffectTrajectoryDecorator` captures changes as domain="affect" memories.

See `docs/research/2026-09-28-occ-emotion-appraisal-architecture.md` for the full OCC model.

#### ActionAppraisalObserver (CDI real-time)

Not a consolidation phase — fires in real-time via `@Observes ExperienceRecorded`, filtering for `Outcome` events. Produces OCC agent-based emotions (Pride, Shame, Admiration, Reproach) and compound emotions (Gratification, Remorse, Gratitude, Anger).

**Dual-path logic:** Self-appraisal (own action → Pride/Shame) fires on every Outcome. Other-appraisal (another agent's action → Admiration/Reproach) fires when `TARGET_AGENT` metadata is present and differs from `agentId`.

**Scoring:** Praiseworthiness = `outcomePolarity × |goalRelevance|`. Asymmetric thresholds from `AppraisalWeights.selfStandardsStrictness` / `otherStandardsStrictness` (derived from DispositionAxes). Strict agents feel Shame easily but require high achievements for Pride.

**Compound detection (inline):** When base emotion + `|goalRelevance| > 0.3`, the compound is inferred from the same inputs without cross-phase coordination.

Delegates scoring to `ActionAppraisal` SPI (`HeuristicActionAppraisal` implementation). `NoOpActionAppraisal` `@DefaultBean` when no implementation on classpath. Graceful degradation via `Instance<T>` for MindMapStore, ActionAppraisal, CognitiveDefaultsRegistry.

#### GoalPrioritizationPhase (Priority 38)

Two steps execute in order: **Prioritize** then **Decay**.

**Prioritize** computes composite `priority` property (0.0–1.0) on active goal nodes:

```
priority = 0.3 × urgency + 0.2 × feasibility + 0.2 × affective_valence + 0.3 × importance
```

Where `affective_valence = (pleasure + dominance + 2) / 4` (neutral PAD → 0.5) and `importance` = inbound `contributes-to` + `enables` edges normalized by max across active goals. Uses dynamic urgency from `target-date` via `GoalUrgency` when present.

**Decay** detects inactive goals (confidence below threshold + non-positive pleasure) and transitions them:
- **Standalone goals** (no `eidos-goal-name`): status → `dormant` (confidence < 0.4) or `abandoned` with `abandonment-reason` (confidence < 0.2 and negative pleasure)
- **Linked goals** (have `eidos-goal-name`): `decay-signal` property set to `dormant` or `abandon` — does NOT write `status` directly (eidos is authoritative). GoalResolutionPhase's Sync step clears `decay-signal` after importing eidos state.

#### GoalUrgency (package-private utility)

Static utility used by both GoalAffectPhase and GoalPrioritizationPhase. When `target-date` property is present, computes `1.0 - (remaining_time / horizon_budget)` clamped to [0, 1]. Horizon budget mapped from the `horizon` property: immediate=4h, short=1d, medium=7d (default), long=30d, aspirational=365d. When `target-date` is absent, falls back to the static `urgency` property value. Parses both ISO-8601 date (`2026-12-31`) and datetime (`2026-12-31T23:59:59Z`) formats.

#### CuriosityRefreshPhase (Priority 40)

Thin delegator to `CuriositySignalGenerator.computeSignals(tenantId, emptySet)`. The full signal generation pipeline (structural, quality, temporal, centrality, proximity + category weights + affect dampening + topical distance dampening) runs inside the generator — see [CuriositySignalGenerator](#curiositysignalgenerator) above.

#### GoalRecognitionPhase (Priority 45)

Scans recent experience memories for goal-like content. Queries `CaseMemoryStore.scan()` for domain="experience", invokes `CognitiveGoalRecognizer` SPI with combined text. Creates goal nodes in GOAL subgraph, deduplicates by name against existing goals. NoOp recognizer returns empty list — standalone deployments get no automatic recognition.

#### GoalRelevanceModulationFactor

`ModulationFactor<Memory>` in `cognitive-index`. Weights retrieved memories by BFS graph proximity to active goal nodes:
- 1 edge: 1.0 (full relevance)
- 2 edges: 0.7
- 3 edges: 0.4
- 4+ edges: 0.0

Active goals cached, refreshed via `refreshGoals()` (called from `@Observes ConsolidationCompleted`). Finds entity nodes by name lookup across non-GOAL subgraphs. Returns 1.0 (neutral) when no active goals exist or entity has no MindMap node.

#### RetrievalAccessTracker

`@ApplicationScoped`. In-memory write-behind counters: two `volatile ConcurrentHashMap` fields for counts (nodeId → `AtomicLong`) and last access times. `recordAccess(nodeId)` — called at retrieval boundaries by `ConversationBridge` and `ExtractionRequestedObserver`. `swapAndReset()` — atomic snapshot swap: replaces both maps with fresh instances, returns frozen `AccessSnapshot`.

#### RetrievalStrength — Bjork's Dual-Strength Model

Pure static utility. `compute(lastAccessed, storageStrength, baseHalfLifeDays)` → [0.0, 1.0].

**Formula:** `2^(-hoursSince / effectiveHalfLifeHours)` where `effectiveHalfLifeHours = baseHalfLifeDays × 24 × (1 + log1p(storageStrength))`.

Storage strength (how deeply encoded) modulates retrieval strength (how easy to recall). Higher `storageStrength` → slower decay. `log1p` provides diminishing returns — first few accesses extend half-life dramatically, subsequent ones less so.

### Experience-to-Behaviour Catalogue

The experience-to-behaviour catalogue is a set of YAML data files at `docs/specs/experience-behaviour-catalogue/` containing clinically-grounded entries that map formative experiences to behavioural tendencies through the CAPS network. Each entry represents a recognised clinical pattern (e.g., "anxious attachment", "learned helplessness") grounded in published psychology research.

**Files:** `index.yaml` (metadata + shared-node map) + one YAML per psychological model (`attachment.yaml`, `bis_bas.yaml`, `cbt.yaml`, `trauma.yaml`, `operant.yaml`, `bandura.yaml`).

**Entry schema:** Each entry has: `id` (kebab-case unique), `model`, `clinical_name`, `description`, `triggers` (CAPS input node refs with intensity/repetition/schedule), `narratives` (optional prose templates), `expected_outcomes` (CAPS output/mediating node refs with direction/strength ranges), `modulating_axes` (disposition amplify/dampen), `related` (cross-references), `sources` (published research with provenance).

**Consumers:** #398 (memory seeding — translates entries into ExperienceEvents), #408 (graph engine — validates SituationClassifier covers trigger nodes), #402 (CognitiveEmergenceTest — uses expected_outcomes for validation targets).

**Validation:** Run `python3 scripts/validate_catalogue.py` to check node ID consistency with the CAPS topology, cross-reference resolution, required fields, and enum validity.

**Adding entries:** New entries must reference CAPS topology node IDs from `docs/specs/2026-10-02-caps-topology.yaml`, include published research citations, and pass the validation script. Run the script after any modification.

**Version coupling:** The catalogue's `index.yaml` declares the CAPS topology version it was validated against. When the topology changes (node add/rename/split), catalogue entries referencing affected nodes must be updated.

**Design spec:** `docs/specs/issue-407-psychology-cause-effect-models/` (CAPS architecture) and the spec workspace for #401 (catalogue design decisions and validation strategy).

### Agent Memory Event Streams

Five domain-specific event streams layer typed agent experience on top of `CaseMemoryStore`. Each converts typed events into `MemoryInput` with a domain tag, stores them, and fires CDI events.

#### ExperienceStream and ExperienceEvent

`ExperienceEvent` — sealed interface with three permits: `Observation` (required `subject`), `Action` (optional `capability`), `Outcome` (required `result`, optional `capability`). All share: `agentId`, `tenantId`, `caseId`, `turnId`, `timestamp`, `description`, `confidence`, `metadata`.

`ExperienceEvents` — static converter. `toMemoryInput(ExperienceEvent)` → `MemoryInput` with `domain="experience"`, `Subject.of("agent", agentId)`. Maps event-type-specific fields to attributes via `ExperienceAttributeKeys`. Reserved key collision throws `IllegalArgumentException`.

`ExperienceStream` — `@ApplicationScoped`. `record(ExperienceEvent)` → converts, stores, fires `ExperienceRecorded` CDI event synchronously, returns memoryId. `recordAll(List)` → batch via `store.storeAll()`, fires event per success, returns `ExperienceStoreResult` with index-correlated failures.

`ExperienceQuery` — factory helpers: `forAgent` (CHRONOLOGICAL, limit 50), `forAgentInCase`, `forAgents`, `search` (RELEVANCE, limit 20), `salient` (SALIENCE, limit 20).

#### RelationshipObserver

`@ApplicationScoped`. Observes `@Observes ExperienceRecorded` (synchronous). Checks `metadata.get("target-agent")` — if present and not self-referential, creates `RelationshipEvent` with `QualitySignal.NEUTRAL`, stores with `domain="relationship"`, fires `RelationshipRecorded`.

**Error isolation:** `SecurityException` propagates. All other store failures caught and logged — observer failure must not break the experience recording chain.

`RelationshipEvent` — record with `agentId`, `otherAgentId` (must differ — validates at construction), `sourceEventType`, `QualitySignal` (POSITIVE/NEGATIVE/NEUTRAL).

#### ReflectionService and ReflectionSynthesizer SPI

`ReflectionService` — `@ApplicationScoped`. `reflect(agentId, tenantId, since, maxSourceMemories)` → queries experiences, passes to `ReflectionSynthesizer.synthesize()` (level 1 only in v1), stores each `ReflectionEvent` with `domain="reflection"`, fires `ReflectionRecorded` per reflection.

`ReflectionSynthesizer` — `@FunctionalInterface` SPI: `synthesize(agentId, tenantId, sources, targetLevel) → List<ReflectionEvent>`. `NoOpReflectionSynthesizer` `@DefaultBean` returns empty — displaced by `@Alternative` LLM-backed implementation.

`ReflectionEvent` — record with `insight`, `level` (≥1), `sourceMemoryIds` (for traceability). Confidence defaults from level: `Math.min(0.3 + level × 0.2, 1.0)`.

#### MoodState, MoodBaseline, and MoodDecay

`MoodState` — record: `agentId`, `tenantId`, PAD axes (pleasure/arousal/dominance ∈ [-1,1]), `cause`, `turnId`, `activeContextIds` (optional Set<String>, null = agent-global fallback — subgraph IDs the agent was engaged with when mood was captured; used by `DomainActivation` for domain-partitioned correlation). Dynamic emotional state stored with `domain="mood"`. PAD values stored both as attributes and as first-class PAD fields on `MemoryInput`.

`MoodBaseline` — per-agent emotional resting point for decay.

`MoodDecay` — pure static utility. `decay(current, baseline, elapsed, timeConstant)` → new `MoodState`. Formula: `current + (baseline − current) × (1 − e^(-elapsed/τ))`. Returns current unchanged when elapsed=0 or τ=0. Preserves `activeContextIds` from input.

#### EngagementStream and EngagementEvent

`EngagementEvent` — per-interaction social outcome measurement: `agentId`, `otherAgentId` (must differ), `turnId` linking to evaluated action, nullable signals: `responded`, `responseTimeMs`, `responseLength`, `affectShift` ([-1,1]), `reactionCount`, `continued`. Self-referential rejection at construction.

`EngagementStream` — `@ApplicationScoped`. Same pattern as ExperienceStream: `record()`/`recordAll()`, converts via `EngagementEvents.toMemoryInput()` with `domain="engagement"`, fires `EngagementRecorded`. Only non-null signal fields added as attributes.

#### MemoryEmitter

`@ApplicationScoped`. Fire-and-forget `CaseMemoryStore` wrapper. `emit(MemoryInput)` catches all exceptions except `SecurityException` (tenant mismatch is a hard error), logs at WARN. `emitAll(List)` uses `store.storeAll()`, logs partial-failure count. This error isolation pattern is shared across MemoryEmitter, RelationshipObserver, and CDI observers that store memories.

#### CaseEnrichmentStep SPI

`CaseEnrichmentStep` — SPI: `appliesTo(MemoryInput)` + `enrich(MemoryInput)`. Optional `priority()` (default 0, lower runs first) and `required()` (default false).

`CaseEnrichmentDecorator` — `@Decorator` on `CaseMemoryStore`. Intercepts `store()` and `storeAll()`. Discovers steps via `Instance<CaseEnrichmentStep>`, sorts by priority. Progressive routing — each step receives the result of all prior steps. Required steps re-throw on failure; optional steps log and continue.

#### ErasureNotificationCaseMemoryStore

`@Decorator @Priority(45)` on `CaseMemoryStore`. Intercepts `erase()`, `eraseSubject()`, `eraseSubjectAcrossTenants()`. After delegate erasure, fires `MemoryEntityErased` CDI events when count > 0. Three sealed variants: `ByRequest` (subject + domain), `ByEntity` (subject only), `CrossTenant` (subject + tenant set). `Clock` injection for testability.

#### Event Flow

```
ExperienceStream.record(event)
  → store.store(MemoryInput)  →  fire ExperienceRecorded
                                       ↓ @Observes (sync)
                                  RelationshipObserver
                                    → if target-agent && != self
                                      → store relationship  →  fire RelationshipRecorded

ReflectionService.reflect(agentId, since)
  → query experiences  →  synthesizer.synthesize() [SPI]
  → store each reflection  →  fire ReflectionRecorded

EngagementStream.record(event)  →  store  →  fire EngagementRecorded
MemoryEmitter.emit(input)       →  store (fire-and-forget, swallow errors)
```

### Inference and Fusion Internals

#### BgeM3Embedder

`final class` in `inference-bge-m3/`. Implements `MultiModalEmbedder`. Single `InferenceModel.run()` produces all three embedding types from named output tensors:

- **Dense (1024-dim):** `output.vector("dense")` → L2-normalized
- **Sparse:** `output.vector("sparse")` → ReLU activation (`max(0, x)`) + threshold filter (`SPARSE_THRESHOLD = 0.01f`) → `Map<Integer, Float>`
- **ColBERT:** `output.output("colbert")` → per-row L2-normalization → `float[][]`

Batch support via `model.runBatch()`. Reports `supportedModes()` = all three, `denseDimension()` = 1024, `colbertDimension()` = 1024.

#### MultiModalEmbedder Interface

Core contract in `inference-api/`: `embed(String)`, `embedBatch(List)`, `supportedModes()`, `denseDimension()`, `colbertDimension()`, `maxSequenceLength()`.

**`embedSeparate(denseText, nonDenseText)`** — default method for per-leg embedding separation. Uses `embedBatch(List.of(denseText, nonDenseText))` (not individual `embed()` calls) to preserve ONNX batch composition — individual calls (batch=1) can produce different embeddings due to padding/attention mask differences. Short-circuits to single `embed()` when texts are equal.

`MultiModalEmbedding` — value type. Dense mandatory, sparse and ColBERT nullable. Deep-copies arrays on construction and access.

#### SeparateModelEmbedder and MultiModalEmbedderProducer

`SeparateModelEmbedder` — adapter in `rag/`: `EmbeddingModel` (LangChain4j, required) + optional `SparseEmbedder` → `MultiModalEmbedder`. Never produces ColBERT. `@DefaultBean` — displaced when BGE-M3 or other native multi-modal model is on classpath.

`MultiModalEmbedderProducer` — `@ApplicationScoped` CDI producer. `@IfBuildProperty(casehub.rag.embedder.enabled=true, enableIfMissing=true)`. Composes `Instance<EmbeddingModel>` (required) + `Instance<SparseEmbedder>` (optional).

`MatryoshkaMultiModalEmbedder.wrapIfNeeded(embedder, dimension)` — static factory that truncates dense vectors to target dimension + L2 re-normalization. Double-wrap prevention: returns original if already wrapped or dimension is empty.

#### inference-quarkus CDI Extension

`@Inference` — `@Qualifier` annotation with `@Nonbinding String value()`. Usage: `@Inject @Inference("nli") InferenceModel model`.

`InferenceModelProducer` — `@ApplicationScoped`. Uses `InjectionPoint` to read the qualifier's `value()` at injection time. Models cached in `ConcurrentHashMap` — `computeIfAbsent()` creates `OnnxInferenceModel` from config. Shuts down all models on `@Observes ShutdownEvent`.

Config: `casehub.inference.models.<name>.model-path`, `tokenizer-path`, `max-sequence-length` (default 512), `intra-op-threads`, `inter-op-threads`.

#### ScoreFusion

Pure static utility in `fusion-api/`. Two algorithms:

**Weighted RRF:** `rrf(legs, idExtractor, topK, k)`. Per leg: sorts by score descending, computes `leg.weight() / (k + rank + 1)` per item. `leg.weight()` directly scales rank contribution. Normalized: `maxScore = totalWeight / (k + 1)`, each result divided by maxScore → [0,1].

**Convex Combination:** `convexCombination(legs, idExtractor, topK)`. Per leg: min-max normalizes scores to [0,1], multiplies by `normalizedWeight = leg.weight() / totalWeight` (auto-normalized to sum to 1.0).

`ScoredLeg<T>(items, scoreExtractor, weight)` — generic, carries its own score extraction function. `FusedResult<T>(item, score)`.

#### CamelCaseExpander

Pure static utility. `expand(text)` splits tokens at camelCase/PascalCase/digit boundaries, appending expanded parts after originals. `XMLParser` → `XMLParser XML Parser`. Aids BM25 recall for code-domain text.

### RAG Gaps

#### CorpusIngestionService

`@ApplicationScoped` in `rag/`. Bridges corpus modules to the RAG pipeline — reads documents from `ChangeSource`, extracts metadata via `MetadataExtractor`, chunks, and pushes to Qdrant via `EmbeddingIngestor`.

**Two ingestion modes:**
- **Event-driven (filesystem):** On `@Observes StartupEvent`, starts `WatchableChangeSource.watch()` filesystem watchers with `ChangeListener` callback. Watcher failures fall back to polling
- **Scheduled polling (ZIP-based):** `@Scheduled` polls bindings where `ChangeSource` is not `WatchableChangeSource`. Separate cursor checkpoint every 5 minutes

**Binding discovery:** Merges `CorpusBindingProducer` (config-driven) + `Instance<CorpusIngestionBinding>` (custom CDI beans). Each binding provides `name()`, `changeSource()`, `corpusReader()`, `metadataExtractor()`.

**Cursor persistence:** `CursorStore` SPI tracks per-corpus position. On failure, cursor is NOT advanced — retried next poll. **Concurrency:** Per-corpus `ReentrantLock` via `ConcurrentHashMap.computeIfAbsent()`, `tryLock()` — concurrent ingestions silently skipped.

**Reconciliation:** `reconcile(corpusName)` — full-scan: indexes missing documents, deletes orphaned Qdrant entries, resets cursor.

#### Query Expansion Drift Detection

In `QueryExpandingCaseRetriever`. `filterByDrift()` detects expanded queries that semantically diverge from the original via `CosineSimilarity` with optional `Instance<EmbeddingModel>`.

**Config:** `ExpansionConfig.DriftConfig` — `enabled` (default false), `threshold` (default 0.7), `action` (`DriftAction`: OBSERVE logs warning and keeps query, DROP removes it).

**Micrometer metrics:** `casehub.rag.expansion.total`, `casehub.rag.expansion.drift` (similarity distribution), `casehub.rag.expansion.drift.fallback` (dropped queries). Error isolation: drift detection failure returns unfiltered list.

#### TenantGuard

`@FunctionalInterface` in `rag/`. `TenantGuard.of(CurrentPrincipal)` — returns no-op when principal is null (Hortora use case: no tenant enforcement), delegates to `MemoryPermissions.assertTenant()` otherwise. Async-safe via `RequestContextCheck.isActive()`.

#### ColbertQuantizationConfig

Nested interface in `RagConfig`. `type()` (NONE/BINARY/SCALAR, default NONE), `alwaysRam()` (default true). Applied to ColBERT multi-vector params in `ensureCollection()` — same quantization wiring as `DenseQuantization` but targeting the ColBERT vector specifically.

### Cognitive Exploration Subsystem

Three interconnected components model how an agent explores, attends to, and gets stuck on topics.

#### Search Budget (4D Exploration Parameters)

`SearchBudgetTickParticipant` (DERIVED-phase tick participant in `cognition/`) computes four exploration parameters per agent per tick:

| Parameter | What it models | Modulated by |
|-----------|---------------|--------------|
| Breadth | How widely to explore | Drive intensity, mood pleasure, gut valence |
| Laterality | How much to diverge from the main path | Drive intensity, threat intensity (collapse floor) |
| Depth | How deeply to investigate | Drive intensity, dominant drive axis |
| Convergence | How quickly to settle | Drive intensity, gut valence, pathological threshold |

Per-parameter logistic activation (`1/(1+exp(-k*(x-x0)))`) with configurable steepness and midpoint via `SearchBudgetConfig`. Laterality has a threat-collapse floor (Frijda approach/avoidance): when threat intensity exceeds `lateralityThreatFloor`, laterality drops to 0.

**Pathological threshold** (Davis & Nolen-Hoeksema, 1998): when `arousalScore + habituationFailure > pathologicalThreshold` (default 1.4), convergence collapses to 0.0. Arousal derived from intensity-weighted PAD arousal of appraisal emotions. Habituation failure detected when any observation count exceeds `HabituationConfig.repetitionTolerance` while novelty exceeds `noveltyThreshold`.

`SearchBudgetPromptSection` renders cognitive state descriptions. `SearchBudgetDescriptionGenerationPhase` (@Priority(28)) crystallises LLM prose at consolidation time.

#### Curiosity (Information Gap Detection + Four Channels)

`InformationGapDetector` (instance class in `mindmap-intelligence/`) scans MindMap nodes for partial-knowledge gaps via binary entropy H(confidence) × attention weight from edge connectivity. Four gap types: PARTIAL_KNOWLEDGE, TIP_OF_TONGUE, CONNECTED_UNKNOWN, GOAL_ADJACENT.

`CuriosityChannelComputer` (in `mindmap-intelligence/`) derives four curiosity channels from gap scores:

| Channel | Theory | What it measures |
|---------|--------|-----------------|
| Hedonic | Litman I/D (2005) | I-type (pleasurable exploration) vs D-type (aversive need-to-know) |
| Comprehension | Silvia (2006) | Are pieces connecting (interest) or overwhelming (confusion)? |
| Focus | Berlyne (1960) | SPECIFIC (locked on one question) vs DIVERSIVE (scanning broadly) |
| Gap proximity | Loewenstein (1994) | How close to knowing — tip of tongue, or vast unknown? |

`CuriosityDrive` is one of four `DriveSource` implementations feeding `DriveOrchestrator`. `CuriosityPromptSection` renders four-channel curiosity with proportional baseline deviation and drive rank context. `CuriosityDescriptionGenerationPhase` (@Priority(29)) generates LLM prose. `CuriositySignalGenerator` produces attention signals with affect dampening via `AffectTrajectoryAnalyzer`.

#### Recurring Thought Continuum

`RecurringThoughtScorer` (instance class in `mindmap-intelligence/`) computes a 7-symptom composite per topic:

1. **Thought recurrence** — experience memory count within recurrence window
2. **Habituation failure** — repeated observations without novelty decrease
3. **Gap closure resistance** — low MindMap node confidence despite attention
4. **Attentional capture** — proportion of attention signals targeting this topic
5. **Arousal trajectory** — PAD arousal level and rumination signal (low arousal + low pleasure)
6. **Valence** — inverted PAD pleasure (negative pleasure → higher score)
7. **Action tendency** — CAPS attractor category (approach/avoidance/withdrawal)

Weighted composite maps to `ContinuumPosition`: HEALTHY (< fixation threshold) → FIXATION → OBSESSION → RUMINATION.

`RecurringThoughtConfig` (in `cognitive-api/`) holds symptom weights and thresholds. Personality-derived via `CognitiveDerivationEngine.deriveRecurringThoughtConfig()` using PID-5 facets: high ANXIOUSNESS lowers fixation threshold, high conscientiousness (ruleFollowing + low IMPULSIVITY) raises obsession threshold, high WITHDRAWAL + INTIMACY_AVOIDANCE lowers rumination threshold.

`RecurringThoughtPhase` (@Priority(25) in `mindmap-intelligence/consolidation/`) runs at consolidation, scores topics per-agent, caches assessments, emits `THOUGHT_RECURRENCE` signals and `RecurringThoughtDetected` artifacts. `CognitionCore` drains these artifacts into a continuum map (`topicNodeId → ContinuumPosition`) and passes to prompt sections.

**Prompt modulation by continuum position:**

| Position | CuriosityPromptSection | SearchBudgetPromptSection |
|----------|----------------------|--------------------------|
| HEALTHY | Normal rendering | Normal rendering |
| FIXATION | Adds urgency framing | Prepends "circling back" |
| OBSESSION | Intrusive/unbidden language | Compulsive depth framing |
| RUMINATION | Withdrawal, flat affect | All output collapsed |

### Cognitive API Cross-Cutting Types

Shared types in `cognitive-api/` used by MindMap, Memory, and CBR subsystems.

#### Confidence and ConfidenceOrigin

`Confidence` — record: `ConfidenceOrigin origin`, `double value` [0,1], `Instant decayReference` (nullable). Factories: `stated(value, decayRef)`, `inferred(value, decayRef)`, `speculated(value, decayRef)` (all require non-null decayReference), `unknown(value)` (null decayReference). `decayReference` anchors `ConfidenceDecayDecorator`.

`ConfidenceOrigin` — enum: STATED (user asserted), INFERRED (derived by rules), SPECULATED (LLM/heuristic), UNKNOWN. Used for filtering via `MindMapQuery.confidenceOrigin()` and default confidence values via `MindMapConfidenceDefaults.forOrigin()`.

#### TemporalMark

Sealed interface with `resolveToInstant(Instant now)`. Three variants:
- `WallClock(Instant)` — absolute timestamp
- `Relative(Duration offset, Instant anchor)` — relative to anchor or `now`. Enables "3 hours from now" references
- `Ordinal(String turnId, Instant resolved)` — conversation-turn-based ordering with resolved timestamp

#### AffectType

Enum: `INHERENT` (emotion about the entity itself), `ANTICIPATORY` (emotion about a future event related to the entity). Distinguishes emotional dimensions in PAD tagging.

### Corpus Internals

#### Corpus SPIs

`CorpusStore` — write SPI: `append(path, byte[]|InputStream|Path)`, `delete(path)`. Append-only semantics (versioned in ZIP backend, overwrites in Flat backend).

`CorpusReader` — read SPI: `read(path)`, `readStream(path)`, `readVersion(path, version)`, `versions(path)` → `List<VersionInfo>`, `list()`, `list(prefix)`, `exists(path)`. Version-aware reads.

`ChangeSource` — `changesSince(cursor) → ChangeSet`, `fullScan() → ChangeSet`. Cursor-based change tracking. `ChangedEntry(path, ChangeType)`, `ChangeType`: ADDED/MODIFIED/DELETED.

`WatchableChangeSource` — extends `ChangeSource + AutoCloseable`: `watch(ChangeListener)`. `ChangeListener` is `@FunctionalInterface`: `onChange(List<ChangedEntry>)`. For push-based notification.

`CorpusIntegrity` — health SPI: `check()` (read-only), `checkAndRecover()` (detect + repair), `fullHashVerification()` (expensive SHA-256 of all closed ZIPs). Returns `IntegrityReport` with status (OK/DEGRADED/FAILED) and issue list.

#### ZipCorpusStore

Implements both `CorpusStore` and `CorpusReader`. Rolling ZIP archives managed via Zip4j.

**Entry naming:** `<version>/<path>` inside the ZIP. Deletions store empty tombstone markers at `_tombstones/<path>.deleted`. Paths starting with `_` are reserved and rejected.

**ChainManifest** (`chain.json`): Ordered list of `ChainEntry` records (uuid, file, sequence, status, predecessor, contentHash SHA-256). Statuses: `active` (current write target), `closed` (sealed), `compacted` (replaced). Atomic save via temp file + `ATOMIC_MOVE`. Custom recursive-descent JSON parser — zero library dependency.

**MasterIndex:** Rebuilt from ZIP central directories on startup. Maps logical paths → `EntryLocation(zipFile, version, timestamp)`. Tombstones remove paths. Processes chain entries in sequence order, skipping compacted entries.

**Rollover:** `checkRollover()` after each `append()`. When active ZIP exceeds `config.maxZipSize()`, seals it (writes internal meta, computes SHA-256), creates new active ZIP with incremented sequence.

#### FlatCorpusStore

Implements both `CorpusStore` and `CorpusReader`. Direct filesystem mapping — path maps to `rootDir.resolve(path)`. No versioning (only version 1). No tombstones (deletes remove file). Hidden files and `_`-prefixed paths skipped in `list()`.

#### CompositeCorpusStore

Wraps `ZipCorpusStore` + `FlatCorpusStore`. Writes go to both (ZIP is source of truth; flat provides filesystem access for external tools). Reads delegate exclusively to ZIP.

#### Compactor

Static utility. Two modes: `TOMBSTONES_ONLY` (removes tombstone markers, keeps all versions), `FULL` (keeps only latest per path, removes tombstoned paths entirely). Steps: verify closed + not compacted, filter entries, write temp ZIP, compute SHA-256, retire old manifest entry, add new closed entry, atomically replace.

#### ZipIntegrityChecker

Implements `CorpusIntegrity`. Three levels: `check()` validates chain + ZIP existence + entry counts, `checkAndRecover()` reconstructs missing manifests and internal metas, `fullHashVerification()` SHA-256 verifies all closed archives.

### Schema Generator

`CognitiveSchemaGenerator` — plain Java class (no CDI) in `schema-generator/`. Constructs a victools `SchemaGenerator` (Draft 2020-12) with four modules:

- **JacksonModule** — respects `@JsonPropertyOrder`
- **EnumInliningModule** — from `casehub-platform-schema-generator`. Intercepts enum types, produces `{type: "string", enum: [values]}` using `toString()` on each constant
- **SealedHierarchyModule** — from `casehub-platform-schema-generator`. Sealed interfaces → `oneOf` + `const` type discriminator. `DISCRIMINATOR_OVERRIDES` map overrides discriminator values per permit (e.g., `GaussianDecay → "gaussian"`, `ItakuraParallelogram → "itakura"`)
- **ShorthandModule** — from `casehub-platform-schema-generator`. Configured with `SHORTHAND_DEFINITIONS` map — scalar-or-object `oneOf` for three types: `Confidence` (number or `{origin, value, decayReference}`), `NodeRef` (string pattern or `{scheme, id, qualifier}`), `RecurrenceRule` (RRULE string or `{freq, interval, count, until, byDay}`)

Two public methods: `generate(Class<?>) → JsonNode`, `generateToYaml(Class<?>, Path)` (YAML output, minimize-quotes). **Extension:** add `ShorthandDefinition` registrations to `SHORTHAND_DEFINITIONS`, discriminator overrides to the `DISCRIMINATOR_OVERRIDES` map.

### CBR JPA Backend

`JpaCbrRecordStore` — `@Alternative @Priority(3) @ApplicationScoped` in `memory-cbr-jpa/`. PostgreSQL-backed via `EntityManager`.

`CbrRecordEntity` — JPA `@Entity` with 18 columns. Features stored as JSON string via Jackson (not native JSONB operators) — deserializes to Java, scores via `CbrSimilarityScorer` in-memory. FEATURE_ONLY retrieval mode only — SEMANTIC_ONLY returns empty, HYBRID degrades to FEATURE_ONLY with log warning.

`cbrType` discriminator on load: `"plan"` → `CbrPlanRecord`, `"feature-vector"` → `CbrFeatureRecord`, `"textual"` → `CbrGuidanceRecord`.

### CbrSuggestions and FeatureStatistics

`CbrSuggestions` — immutable record: `featureStats` (Map<String, FeatureStatistics>), `historicalSuccessRate`, `experienceCount`, `averageSimilarity`. `EMPTY` constant, `isEmpty()` checks `experienceCount == 0`.

`FeatureStatistics` — immutable record: `min`, `max`, `median`, `p75`, `sampleCount`. `compute(double[])` factory: clones + sorts array, nearest-rank percentile: `index = ceil(rank × n) − 1`, clamped to 0.

### EmbeddingTextSimilarity

`LocalSimilarityFunction` implementation in `memory-cbr-embedding/` for semantic text field cosine similarity. Plain Java (no CDI).

`precompute(List<String>)` — batch embedding via `model.embedAll()`. Filters uncached texts, deduplicates, stores in `HashMap` cache. Called by `QdrantCbrRecordStore`'s two-pass retrieval to pre-embed all candidate text values in one batch.

`compute(FeatureValue, FeatureValue)` — extracts strings from `StringVal` pairs, embeds each (cache-backed), returns `max(0.0, CosineSimilarity.between())`. `CbrSimilarityScorer` uses this as a `LocalSimilarityFunction` override for `Text(semantic=true)` fields.

---

## Configuration Conventions

### Naming

All config properties use the `casehub.<subsystem>.*` prefix, where `<subsystem>` is the owning module group: `rag`, `cbr`, `mindmap`, `memory`. Consolidation config lives under `casehub.mindmap.consolidation.*` because the consolidation pipeline is owned by `mindmap-intelligence`.

### Feature gating

| Pattern | When to use | Example |
|---|---|---|
| `@IfBuildProperty` | Classpath-activated features — decorators and beans that should not exist at all unless opted in | `casehub.rag.expansion.enabled`, `casehub.cbr.reranking.enabled` |
| `@ConfigMapping` with `enabled()` | Runtime toggles for features that are always on the classpath but may be disabled per environment | `casehub.rag.expansion.drift.enabled`, `casehub.memory.retention.enabled` |
| `@ConfigProperty` boolean | Simple one-off runtime flags that don't warrant a config group | `casehub.rag.reranking.colbert-fallback` |

Do not combine `@IfBuildProperty` and `@ConfigMapping enabled()` for the same feature — the build-time gate is sufficient.

### Fail-fast

When a required companion property is missing (e.g., `mode` when `enabled=true`), throw `IllegalStateException` at startup — do not log a warning and continue with no-op behavior.

### REST client timeouts

All MicroProfile REST Client interfaces must have `connect-timeout` and `read-timeout` defaults in their module's `application.properties`. Framework defaults may be infinite.

---

## Configuration Reference

Complete property reference for all neocortex modules. Properties marked with `†` are **build-time** (`@IfBuildProperty`). The [consumer guide](consumer-guide.md#configuration) has a curated subset for app builders.

### Inference (inference-quarkus)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.inference.models.<name>.model-path` | *(required)* | Path to ONNX model file |
| `casehub.inference.models.<name>.tokenizer-path` | *(required)* | Path to HuggingFace tokenizer JSON |
| `casehub.inference.models.<name>.max-sequence-length` | `512` | Maximum input token length |
| `casehub.inference.models.<name>.intra-op-threads` | `0` | ONNX Runtime intra-op parallelism (0 = auto) |
| `casehub.inference.models.<name>.inter-op-threads` | `0` | ONNX Runtime inter-op parallelism (0 = auto) |

### RAG — Qdrant Connection (rag)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.qdrant.host` | `localhost` | Qdrant gRPC host |
| `casehub.rag.qdrant.port` | `6334` | Qdrant gRPC port |
| `casehub.rag.qdrant.api-key` | — | Qdrant API key |
| `casehub.rag.qdrant.use-tls` | `false` | Enable TLS for Qdrant connection |

### RAG — Vector Configuration (rag)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.tenancy-strategy` | `SEPARATE_COLLECTIONS` | Tenant isolation strategy |
| `casehub.rag.dense-vector-name` | `dense` | Qdrant named vector space for dense embeddings |
| `casehub.rag.sparse-vector-name` | `sparse` | Qdrant named vector space for SPLADE embeddings |
| `casehub.rag.bm25-vector-name` | `bm25` | Qdrant named vector space for BM25 |
| `casehub.rag.colbert-vector-name` | `colbert` | Qdrant named vector space for ColBERT multi-vectors |
| `casehub.rag.bm25-enabled` | `true` | Enable BM25 as third retrieval leg |
| `casehub.rag.embedding-batch-size` | `100` | Batch size for embedding operations |
| `casehub.rag.max-multivector-floats` | `1000000` | Maximum floats for ColBERT multi-vector storage |
| `casehub.rag.max-sequence-length` | — | Maximum token length for embedders |
| `casehub.rag.matryoshka.dimension` | — | Matryoshka truncation dimension (disabled if unset) |
| `casehub.rag.quantization.type` | `NONE` | Dense quantization: `NONE`, `BINARY`, `SCALAR` |
| `casehub.rag.quantization.always-ram` | `true` | Keep quantized dense vectors in RAM |
| `casehub.rag.quantization.oversampling` | — | Oversampling factor for quantized dense search |
| `casehub.rag.colbert-quantization.type` | `NONE` | ColBERT quantization: `NONE`, `BINARY`, `SCALAR` |
| `casehub.rag.colbert-quantization.always-ram` | `true` | Keep quantized ColBERT vectors in RAM |
| `casehub.rag.embedder.enabled` | `true` | `†` Enable multi-modal embedder producer |

### RAG — Retrieval (rag)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.retrieval.fusion-strategy` | `RRF` | Fusion strategy: `RRF`, `DBSF`, or `CC` |
| `casehub.rag.retrieval.dense-top-k` | `40` | Dense leg candidate count |
| `casehub.rag.retrieval.sparse-top-k` | `40` | Sparse leg candidate count |
| `casehub.rag.retrieval.bm25-top-k` | `40` | BM25 leg candidate count |
| `casehub.rag.retrieval.rrf-k` | `60` | RRF smoothing constant |
| `casehub.rag.retrieval.rerank-enabled` | `true` | Enable internal reranking pass |
| `casehub.rag.retrieval.rerank-top-n` | `10` | Number of results after reranking |
| `casehub.rag.retrieval.quality-payload-field` | — | Qdrant payload field for quality boost |
| `casehub.rag.retrieval.quality-max` | `10.0` | Maximum quality payload value for normalization |
| `casehub.rag.retrieval.weights.dense` | `1.0` | Dense leg weight for fusion |
| `casehub.rag.retrieval.weights.sparse` | `1.0` | Sparse leg weight for fusion |
| `casehub.rag.retrieval.weights.bm25` | `1.0` | BM25 leg weight for fusion |
| `casehub.rag.retrieval.weights.quality` | `0.0` | Quality/payload boost weight |

### RAG — Ingestion (rag)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.ingestion.interval` | `30s` | Polling interval for corpus ingestion |
| `casehub.rag.ingestion.cursor-dir` | `${java.io.tmpdir}/casehub-ingestion-cursors` | Directory for ingestion cursor persistence |
| `casehub.rag.ingestion.cursor-checkpoint-interval` | `5m` | Cursor checkpoint flush interval |
| `casehub.rag.ingestion.corpora.<name>.mode` | `AUTO` | Ingestion mode per corpus |
| `casehub.rag.ingestion.corpora.<name>.tenant-id` | *(required)* | Tenant ID for corpus |
| `casehub.rag.ingestion.corpora.<name>.corpus-name` | *(required)* | Corpus name in Qdrant |
| `casehub.rag.ingestion.corpora.<name>.chunking` | `none` | Chunking strategy |
| `casehub.rag.ingestion.corpora.<name>.chunking-max-size` | — | Maximum chunk size in tokens |
| `casehub.rag.ingestion.corpora.<name>.chunking-overlap-size` | — | Chunk overlap in tokens |
| `casehub.rag.ingestion.dedup.enabled` | `true` | Enable pre-ingestion dedup gate |
| `casehub.rag.ingestion.dedup.threshold` | `0.95` | Cosine similarity threshold for dedup |

### RAG — Corpus Storage (rag)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.corpus.corpora.<name>.source` | *(required)* | Filesystem path to corpus source |
| `casehub.corpus.corpora.<name>.mode` | `FLAT` | Storage mode: `FLAT` or `ZIP` |
| `casehub.corpus.corpora.<name>.max-zip-size` | `104857600` | Maximum ZIP archive size (100 MB) |

### RAG — Corrective RAG (rag-crossencoder)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.crag.enabled` | `false` | `†` Enable corrective RAG quality-gating |
| `casehub.rag.crag.correct-threshold` | `0.7` | Cross-encoder score threshold for CORRECT grade |
| `casehub.rag.crag.incorrect-threshold` | `0.3` | Cross-encoder score threshold for INCORRECT grade |
| `casehub.rag.crag.expansion-multiplier` | `3` | Candidate expansion factor for CRAG |
| `casehub.rag.crag.colbert.correct-threshold` | `0.55` | ColBERT fallback CORRECT threshold |
| `casehub.rag.crag.colbert.incorrect-threshold` | `0.35` | ColBERT fallback INCORRECT threshold |
| `casehub.rag.reranking.colbert-fallback` | `false` | Use ColBERT evaluator when cross-encoder unavailable |

### RAG — Cross-Encoder Reranking (rag-crossencoder)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.reranking.enabled` | `false` | `†` Enable cross-encoder reranking decorator |
| `casehub.rag.reranking.rerank-pool-size` | `30` | Candidate pool size for reranking |

### RAG — Query Expansion (rag-expansion)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.expansion.enabled` | `false` | `†` Enable query expansion decorator |
| `casehub.rag.expansion.mode` | — | `†` Expansion mode: `llm`, `step-back`, or `template` |
| `casehub.rag.expansion.hypothetical-count` | `1` | Number of hypothetical documents (HyDE) |
| `casehub.rag.expansion.prompt-template` | — | Custom prompt template for LLM expansion |
| `casehub.rag.expansion.template` | — | Template string for template-mode expansion |
| `casehub.rag.expansion.step-back-prompt-template` | — | Custom prompt for step-back reformulation |
| `casehub.rag.expansion.drift.enabled` | `false` | Enable semantic drift detection |
| `casehub.rag.expansion.drift.threshold` | `0.7` | Cosine similarity threshold for drift |
| `casehub.rag.expansion.drift.action` | `OBSERVE` | Drift action: `OBSERVE` or `DROP` |

### RAG — Retrieval Tracking (rag-tracking)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.tracking.enabled` | — | `†` Enable retrieval tracking decorator |
| `casehub.rag.tracking.retention.days` | `90` | Tracking trace retention period (days) |
| `casehub.rag.tracking.sqlite.path` | *(required)* | SQLite database path for tracking |
| `casehub.rag.tracking.sqlite.pool.max-size` | `5` | HikariCP maximum pool size |
| `casehub.rag.tracking.sqlite.busy-timeout-ms` | `5000` | SQLite busy timeout (ms) |

### RAG — Tika Document Parsing (rag-tika)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.tika.chunk-size` | `512` | Chunk size in tokens for Tika-parsed documents |
| `casehub.rag.tika.chunk-overlap` | `64` | Chunk overlap in tokens |

### RAG — Embedding Cache (rag-cache)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.rag.embedding-cache.enabled` | `false` | Enable embedding cache |
| `casehub.rag.embedding-cache.path` | — | Cache storage directory |
| `casehub.rag.embedding-cache.version-suffix` | — | Cache version suffix for invalidation |

### Agent Memory — SQLite Backend (memory-sqlite)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.memory.sqlite.path` | *(required)* | SQLite database path |
| `casehub.memory.sqlite.pool.max-size` | `5` | HikariCP maximum pool size |
| `casehub.memory.sqlite.busy-timeout-ms` | `5000` | SQLite busy timeout (ms) |
| `casehub.memory.sqlite.fts.enabled` | `true` | Enable FTS5 full-text search |

### Agent Memory — JPA/PostgreSQL Backend (memory-jpa)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.memory.jpa.fts.enabled` | `true` | Enable PostgreSQL full-text search |
| `casehub.memory.jpa.fts.language` | `english` | PostgreSQL text search configuration |

### Agent Memory — Mem0 Backend (memory-mem0)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.memory.mem0.api-key` | *(required)* | Mem0 API bearer token |
| `casehub.memory.mem0.infer` | `false` | Enable Mem0 LLM extraction mode |
| `casehub.memory.mem0.since-search-top-k` | `500` | Top-K for time-bounded search |
| `casehub.memory.mem0.search-threshold` | `0.1` | Minimum similarity threshold |
| `casehub.memory.mem0.store-all-concurrency` | `4` | Concurrent store operations |

### Agent Memory — Graphiti Backend (memory-graphiti)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.memory.graphiti.api-key` | — | Graphiti API bearer token |
| `casehub.memory.graphiti.known-domains` | — | Known memory domains (required for `eraseEntity()`) |

### Agent Memory — Retention (memory)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.memory.retention.enabled` | `false` | Enable scheduled confidence-based retention purge |
| `casehub.memory.retention.domain` | — | Memory domain to purge |
| `casehub.memory.retention.max-age-days` | — | Maximum age before purge eligibility |
| `casehub.memory.retention.min-confidence` | — | Minimum confidence to retain |

### CBR — Qdrant Backend (memory-qdrant)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.memory.cbr.qdrant.host` | `localhost` | Qdrant gRPC host |
| `casehub.memory.cbr.qdrant.port` | `6334` | Qdrant gRPC port |
| `casehub.memory.cbr.qdrant.api-key` | — | Qdrant API key |
| `casehub.memory.cbr.qdrant.use-tls` | `false` | Enable TLS for Qdrant connection |
| `casehub.memory.cbr.qdrant.collection-prefix` | `cbr` | Qdrant collection name prefix |
| `casehub.memory.cbr.qdrant.dense-vector-name` | `dense` | Dense vector space name |
| `casehub.memory.cbr.qdrant.max-retries` | `3` | gRPC call retry count |
| `casehub.memory.cbr.qdrant.allow-dimension-migration` | `false` | Allow collection recreation on dimension change |
| `casehub.memory.cbr.qdrant.allow-sparse-vector-migration` | `false` | Allow collection recreation for sparse vectors |
| `casehub.memory.cbr.qdrant.oversample-factor` | `3` | Quantized search oversampling factor |
| `casehub.memory.cbr.qdrant.over-fetch-limit` | `200` | Maximum candidates before client-side scoring |
| `casehub.memory.cbr.qdrant.splade-enabled` | `false` | Enable SPLADE sparse embeddings for CBR |
| `casehub.memory.cbr.qdrant.splade-vector-name` | `sparse` | SPLADE vector space name |
| `casehub.memory.cbr.qdrant.splade-top-k` | `0` | SPLADE candidate count (0 = disabled) |
| `casehub.memory.cbr.qdrant.bm25-enabled` | `false` | Enable BM25 server-side inference for CBR |
| `casehub.memory.cbr.qdrant.bm25-vector-name` | `bm25` | BM25 vector space name |
| `casehub.memory.cbr.qdrant.bm25-model` | `Qdrant/bm25` | Qdrant BM25 inference model |
| `casehub.memory.cbr.qdrant.bm25-top-k` | `0` | BM25 candidate count (0 = disabled) |
| `casehub.memory.cbr.qdrant.cc-weights.dense` | `0.6` | Convex combination weight for dense leg |
| `casehub.memory.cbr.qdrant.cc-weights.sparse` | `0.2` | Convex combination weight for sparse leg |
| `casehub.memory.cbr.qdrant.cc-weights.bm25` | `0.2` | Convex combination weight for BM25 leg |

### CBR — Cross-Encoder Reranking (memory-cbr-crossencoder)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.cbr.reranking.enabled` | `false` | `†` Enable cross-encoder reranking for CBR retrieval |
| `casehub.cbr.reranking.rerank-pool-size` | `30` | Candidate pool size for reranking |

### CBR — Retrieval Tracking (memory-cbr-tracking)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.cbr.tracking.enabled` | — | `†` Enable CBR retrieval tracking |
| `casehub.cbr.tracking.retention-days` | `90` | CBR tracking trace retention period (days) |
| `casehub.cbr.tracking.sqlite.path` | *(required)* | SQLite database path for CBR tracking |
| `casehub.cbr.tracking.sqlite.pool-max-size` | `5` | HikariCP maximum pool size |
| `casehub.cbr.tracking.sqlite.busy-timeout-ms` | `5000` | SQLite busy timeout (ms) |
| `casehub.cbr.adaptation-tracking.enabled` | — | `†` Enable plan adaptation tracking |
| `casehub.cbr.ensemble-tracking.enabled` | — | `†` Enable ensemble analysis tracking |

### CBR — Diversity (memory)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.cbr.diversity.enabled` | `false` | `†` Enable MMR diversity injection |
| `casehub.cbr.diversity.lambda` | `0.7` | MMR lambda (1.0 = pure relevance, 0.0 = pure diversity) |
| `casehub.cbr.diversity.over-fetch-factor` | `3.0` | Over-fetch multiplier for diversity candidate pool |

### CBR — Outcome Weighting (memory)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.cbr.outcome-weighting.enabled` | `false` | `†` Enable outcome-based score modulation |
| `casehub.cbr.outcome-weighting.influence` | `0.3` | Outcome weighting influence factor (0.0–1.0) |

### CBR — Trust Weighting (memory)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.cbr.trust-weighting.enabled` | `false` | `†` Enable trust-based score modulation |
| `casehub.cbr.trust-weighting.influence` | `0.3` | Trust weighting influence factor (0.0–1.0) |
| `casehub.cbr.trust-weighting.trajectory-sensitivity` | `0.5` | Declining trust trajectory penalty sensitivity |

### CBR — Retention (memory)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.cbr.retention.enabled` | `false` | Enable scheduled CBR retention purge |
| `casehub.cbr.retention.interval` | `24h` | Purge scheduling interval |
| `casehub.cbr.retention.domain` | — | CBR domain to purge |
| `casehub.cbr.retention.case-types` | — | Case types subject to retention |
| `casehub.cbr.retention.max-age-days` | — | Maximum case age before purge |
| `casehub.cbr.retention.max-cases-per-type` | — | Maximum cases per type per tenant |
| `casehub.cbr.retention.min-trust-score` | — | Minimum trust score to retain |

### CBR — Trust-Based Retention (memory)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.cbr.trust-retention.enabled` | `false` | Enable trust-trajectory-based purge |
| `casehub.cbr.trust-retention.interval` | `24h` | Purge scheduling interval |
| `casehub.cbr.trust-retention.min-current-trust` | `0.3` | Minimum current trust score threshold |
| `casehub.cbr.trust-retention.domain` | — | CBR domain scope |
| `casehub.cbr.trust-retention.case-types` | — | Case types scope |

### MindMap — SQLite Backend (mindmap-sqlite)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.mindmap.sqlite.path` | *(required)* | SQLite database path |
| `casehub.mindmap.sqlite.pool.max-size` | `5` | HikariCP maximum pool size |
| `casehub.mindmap.sqlite.busy-timeout-ms` | `5000` | SQLite busy timeout (ms) |

### MindMap — Confidence Decay (mindmap)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.mindmap.confidence.half-life-days` | `30` | Confidence decay half-life (days) |

### MindMap — Consolidation (mindmap-intelligence)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.mindmap.consolidation.interval-minutes` | `5` | Consolidation scheduler interval (minutes) |
| `casehub.mindmap.consolidation.significance-threshold` | `10.0` | Event significance threshold for triggering consolidation |

### MindMap — Experience Graduation (mindmap-intelligence)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.mindmap.consolidation.graduation.threshold` | `0.5` | Graduation worthiness score threshold |
| `casehub.mindmap.consolidation.graduation.max-per-pass` | `20` | Maximum graduations per consolidation pass |
| `casehub.mindmap.consolidation.graduation.min-corroboration` | `3` | Minimum corroborating observations for graduation |
| `casehub.mindmap.consolidation.graduation.text-similarity.enabled` | `false` | Enable text-similarity as second corroboration signal |
| `casehub.mindmap.consolidation.graduation.text-similarity.embedding-threshold` | `0.8` | Embedding cosine similarity threshold |
| `casehub.mindmap.consolidation.graduation.text-similarity.keyword-threshold` | `0.5` | Jaccard keyword overlap threshold (fallback) |

### MindMap — Schema Discovery (mindmap-intelligence)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.mindmap.schema-discovery.threshold` | `0.8` | Property occurrence threshold for schema inclusion |
| `casehub.mindmap.schema-discovery.min-samples` | `5` | Minimum entity samples for schema inference |
| `casehub.mindmap.schema-discovery.required-threshold` | `0.95` | Threshold for marking a schema field as required |

### Cognitive Index (cognitive-index)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.cognitive.profiles-dir` | — | Directory to watch for cognitive profile YAML files |
| `casehub.cognitive.rules-dir` | — | Directory to watch for rule YAML files |

### Cognition — Drive Adaptation (cognition)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.drive-adaptation.learning-rate` | `0.05` | EMA learning rate for drive intensity adaptation |
| `casehub.drive-adaptation.arousal-weight` | `0.3` | Arousal contribution to drive updates |
| `casehub.drive-adaptation.min-intensity` | `0.1` | Minimum drive intensity floor |
| `casehub.drive-adaptation.max-intensity` | `1.0` | Maximum drive intensity ceiling |
| `casehub.drive-adaptation.max-per-pass` | `20` | Maximum drive updates per adaptation pass |

### Cognition — Needs Pyramid (cognition)

| Property | Default | Description |
|----------|---------|-------------|
| `casehub.needs-pyramid.satisfaction-increment` | `0.05` | Per-interaction satisfaction increase |
| `casehub.needs-pyramid.dissatisfaction-increment` | `0.05` | Per-interaction dissatisfaction increase |
| `casehub.needs-pyramid.initial-satisfaction` | `0.5` | Initial satisfaction level for all needs |
| `casehub.needs-pyramid.decay.safety` | `0.15` | Safety need decay rate per tick |
| `casehub.needs-pyramid.decay.tasks` | `0.10` | Task need decay rate per tick |
| `casehub.needs-pyramid.decay.social` | `0.08` | Social need decay rate per tick |
| `casehub.needs-pyramid.decay.self-expression` | `0.05` | Self-expression need decay rate per tick |
| `casehub.needs-pyramid.decay.understanding` | `0.03` | Understanding need decay rate per tick |
| `casehub.needs-pyramid.resting.safety` | `0.6` | Safety need resting satisfaction level |
| `casehub.needs-pyramid.resting.tasks` | `0.3` | Task need resting satisfaction level |
| `casehub.needs-pyramid.resting.social` | `0.4` | Social need resting satisfaction level |
| `casehub.needs-pyramid.resting.self-expression` | `0.4` | Self-expression need resting satisfaction level |
| `casehub.needs-pyramid.resting.understanding` | `0.4` | Understanding need resting satisfaction level |

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

All inference, RAG, CBR, agent memory, MindMap, and cognitive subsystem modules shipped. Active development on knowledge consolidation, cognitive profiles, and agent memory event streams.

| Area | What shipped |
|------|-------------|
| Inference Foundation | `InferenceModel` SPI with sealed `InferenceInput` (Text + Tensor), ONNX runtime, task adapters (NLI, classification, tensor classification, regression, reranking), SPLADE sparse embeddings, BGE-M3 multi-modal embeddings, Quarkus CDI extension |
| RAG Pipeline | Three-leg hybrid search (dense + sparse + BM25) with configurable fusion (RRF/DBSF/CC); `FusionWeightsConfig` with per-leg weights; per-query weight multipliers + effectiveWeight(); `PayloadBoostCaseRetriever` quality rescore; `MatryoshkaEmbeddingModel`; `DenseQuantization`; ColBERT multi-vector scalar quantization; per-leg embedding separation; corrective RAG + cross-encoder reranking; query expansion (HyDE, template, step-back) with drift detection; retrieval tracking; pre-ingestion dedup gate; `RetrievalAnalyzer` (document stats, query clusters with MinHash, correlation graph, document impact); `CorpusIngestionService` with event-driven + polling modes |
| CBR | Typed feature values (9 field types, 7 value types); `SimilaritySpec` sealed (6 similarity functions incl. DTW + edit distance); weighted per-field scoring; plan adaptation SPI (caseType-aware, variantId tracking); plan ensemble analysis SPI; temporal decay (3 strategies); hierarchical scoping with ScopeDecay; supersession + reinstate + audit; trend detection + enrichment; cross-encoder reranking; embedding-based text similarity; trust-weighted retrieval; outcome-weighted retrieval + CloudEvent feedback; CBR retention (age + count + trust purge); trust trajectory purge; reconciliation with Qdrant; JPA/PostgreSQL backend; retrieval tracking (retrieval + adaptation + ensemble); erasure notification; personality transition schema; scan/discoverTenants admin operations; CbrSuggestions/FeatureStatistics |
| Agent Memory | Five backends (in-memory, JPA, SQLite, Mem0, Graphiti); `MemoryEmitter` fire-and-forget wrapper; `MemoryOrder.SALIENCE` (recency x confidence); unified `Confidence` record (origin + value); confidence-based retention purge; five event streams (experience, relationship, reflection, mood, engagement); `CaseEnrichmentStep` SPI; erasure notification |
| MindMap | Thing/MindMapNode hierarchy; TypeRegistry with lazy per-tenant bootstrap; trait system (programmatic + declarative rules); 5-deep CDI decorator chain (DerivedEdge @80, TraitApplication @70, AffectTrajectory @65, ConfidenceDecay @55, IdleTracker @30); graph analysis (MindMapAnalyzer — orphans, centrality, k-cores, contradictions); merge with conflict reporting; supersession/reinstatement; capability-gated operations |
| MindMap Intelligence | MindMapExtractor (LLM entity/relationship extraction — parse/apply decomposition, public ParsedExtraction types, optional PrincipalId); ConversationBridge (fast segmentation + async enrichment pipeline, PrincipalId + ConfidenceOrigin params); CognitiveLoader (vocabulary from YAML profiles); CuriositySignalGenerator (5-category signals with affect dampening); RecurrenceRule/Generator |
| Cognitive Index | TemporalIndex (cross-store chronological aggregation); AffectTrajectoryAnalyzer (3-axis slope + volatility); TemporalFocus (proximity/recency + affect modifiers); CognitiveProfile (cross-store entity resolution + perspective-aware resolve + multi-agent compare); SocialComparison (PAD distance matrix, signed pairwise differences, 3D trajectory alignment); DomainActivation (cross-domain DTW correlation with time-bucketed 3D PAD); PerspectivalMerge (public static utility) + PerspectivalResolver (package-private, internalized in CognitiveProfile); CognitiveDefaults/Registry (YAML per-agent config); CognitiveDerivationEngine (9 derivation pathways from eidos identity); DeclarativeRuleRegistry; Modulation framework (profiles + factors + retrieval modulator) |
| Consolidation | ConsolidationScheduler (idle-gated, curiosity-driven priority); 4-phase pipeline (AccessFrequency, MergeDetection, CommunitySummary, CuriosityRefresh); RetrievalAccessTracker + Bjork's dual-strength model |
| Corpus | Append-only zip archives, flat filesystem, composite multi-backend; chain manifest; change tracking; compaction; integrity checks with recovery |
| Score Fusion | `fusion-api` tier-1 module — weighted RRF + CC algorithms, `CamelCaseExpander` for BM25 preprocessing. Shared by RAG and CBR |
| Schema Generator | JSON Schema generation (Draft 2020-12) for cognitive types — sealed hierarchy oneOf, enum inlining, shorthand scalar-or-object patterns, YAML output |
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
