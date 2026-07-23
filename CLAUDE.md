# neocortex Workspace
**Name:** casehub-neocortex

**Physical path:** `/Users/mdproctor/claude/casehub/neocortex/CLAUDE.md`
**Project repo:** `/Users/mdproctor/claude/casehub/neocortex`
**Workspace:** `/Users/mdproctor/claude/public/casehub/neocortex`
**Workspace type:** public

## Session Start

Run `add-dir /Users/mdproctor/claude/casehub/neocortex` before any other work.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `specs/` (workspace staging) |
| writing-plans (plans) | `plans/` |
| handover | `HANDOFF.md` |
| idea-log | `IDEAS.md` |
| design-snapshot | `snapshots/` |
| java-update-design / update-primary-doc | `design/JOURNAL.md` (created by `epic`) |
| adr | `adr/` (workspace staging) |
| write-blog | `blog/` |

## Structure

- `HANDOFF.md` — session handover (single file, overwritten each session)
- `IDEAS.md` — idea log (single file)
- `specs/` — brainstorming / design specs (staging; promoted to project `docs/specs/` at epic close)
- `plans/` — implementation plans (ephemeral; stay in workspace only)
- `snapshots/` — design snapshots with INDEX.md (auto-pruned, max 10)
- `adr/` — architecture decision records (staging; promoted to project `docs/adr/` at epic close)
- `blog/` — project diary entries with INDEX.md
- `design/` — epic journal (created by `epic` at branch start)

## Git Discipline

Two git repositories are active in every session:
- **Workspace** (`/Users/mdproctor/claude/public/casehub/neocortex`) — staging for specs/ADRs; permanent home for blog, handover, plans, snapshots
- **Project repo** (`/Users/mdproctor/claude/casehub/neocortex`) — source code + promoted specs (`docs/specs/`) + promoted ADRs (`docs/adr/`)

Never rely on CWD for git operations. Always use explicit paths:
```bash
git -C /Users/mdproctor/claude/public/casehub/neocortex ...  # workspace artifacts
git -C /Users/mdproctor/claude/casehub/neocortex ...         # project artifacts
```

Source code commits → project repo (`origin` = mdproctor/neocortex, `upstream` = casehubio/neocortex)

## Rules

- All methodology artifacts go to workspace first
- Promotion to project repo is always explicit — never automatic
- Workspace branches mirror project branches — switch both together

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `docs/adr/` — promoted at epic close |
| specs      | project     | lands in `docs/specs/` — promoted at epic close |
| blog       | workspace   | staged here; published to mdproctor.github.io via publish-blog |
| plans      | workspace   | stay in workspace permanently |
| design     | workspace   | epic journal stays in workspace |
| design     | project     | journal in workspace `design/`; merge target is project `ARC42STORIES.MD` |
| snapshots  | workspace   | stay in workspace permanently |
| handover   | workspace   | |

Living docs — check for drift after significant changes:
- `ARC42STORIES.MD` — primary architecture record; check §9–10 after module, SPI, or structural changes

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.

## Peer Repos — Hard Boundary

**Never commit to these repos from a neocortex session.** Each has its own Claude session. For cross-repo fixes, create a GitHub issue on the target repo instead.

Peer repos: platform, ledger, connectors, work, qhorus, eidos, engine, claudony, openclaw, devtown, aml, clinical, life, drafthouse, quarkmind, flow

---

# CaseHub Neural-Text — Claude Code Project Guide

## Platform Context

This repo is one component of the casehubio multi-repo platform. **Before implementing anything — any feature, SPI, data model, or abstraction — run the Platform Coherence Protocol.**

**Platform architecture:**
```
https://raw.githubusercontent.com/casehubio/parent/main/docs/PLATFORM.md
```

**This repo's deep-dive:**
```
https://raw.githubusercontent.com/casehubio/parent/main/docs/repos/casehub-neocortex.md
```

**Related specs:**
- AI Fusion brief: `https://raw.githubusercontent.com/casehubio/parent/main/docs/specs/2026-06-03-ai-fusion-hybrid-fact-space.md`
- ONNX inference brief: `https://raw.githubusercontent.com/casehubio/parent/main/docs/specs/2026-06-03-standalone-rag-retrieval-brief.md`

**Upstream work in progress:**
- quarkus-langchain4j #2572: `@RagPipeline`, `@HybridSearch`, `@DocumentIngestion` composition annotations — simplifies RAG wiring. Also `@RegisterAiService` supplier→bean migration. When shipped, adopt these in `rag/` module to replace manual CDI wiring. Track at `https://github.com/quarkiverse/quarkus-langchain4j/issues/2572`

---

## Reference Documents

| Document | What it covers |
|----------|---------------|
| `../garden/docs/protocols/universal/INDEX.md` | Universal Java/Quarkus protocols |

---

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2, LangChain4j 1.14.1, ONNX Runtime JVM

---

## What This Project Is

`casehub-neocortex` provides two related capabilities for the casehubio platform:

### 1. Neural Text Inference (`inference-*` modules)

A standalone, general-purpose ONNX inference layer for JVM projects. Zero casehub domain dependencies. Shared with Hortora. Fills the gap LangChain4j leaves: running arbitrary ONNX models for NLI, classification, regression, sparse embeddings (SPLADE), and cross-encoder reranking.

**LangChain4j covers:** dense embeddings (`OnnxEmbeddingModel`), RAG pipeline, vector stores.
**This covers:** everything else — NLI, classification, regression, SPLADE, cross-encoder.

Tracks `casehubio/parent#158`. Authoritative design: `Hortora/spec: docs/superpowers/specs/2026-06-03-onnx-inference-module-design.md`

### 2. RAG Integration (`rag-*` modules)

casehub-specific LangChain4j RAG pipeline wiring. Exposes `EmbeddingIngestor` SPI (ingest documents) and `CaseRetriever` SPI (retrieve context for case steps), with reactive variants (`ReactiveEmbeddingIngestor`, `ReactiveCaseRetriever`) for consumers on the Vert.x event loop. Tenancy-isolated Qdrant collections. Hybrid dense (LangChain4j) + sparse (inference-splade) + BM25 (server-side Qdrant inference) search via RRF fusion. `CorpusIngestionService` bridges corpus modules to RAG — polls `ChangeSource`, reads via `CorpusReader`, extracts metadata (`MetadataExtractor` SPI), chunks, and pushes to Qdrant via `EmbeddingIngestor`. Config-driven with cursor persistence (`CursorStore` SPI) and admin-triggered reconciliation.

Tracks `casehubio/parent#164`.

### 3. CBR Memory (`memory-*` modules)

Agent memory SPI and all backend implementations. `CaseMemoryStore` SPI (queryable, permission-aware, persistent memory — migrated from platform in #56) + `ReactiveCaseMemoryStore` (full parity: scan, capabilities, requireCapability, discoverTenants) + `GraphCaseMemoryStore`. `CaseEnrichmentStep` SPI enables pre-store transformation pipelines via `CaseEnrichmentDecorator` (@Decorator). `discoverTenants()` capability-gated cross-tenant admin operation for programmatic tenant discovery. Backends: in-memory, JPA (PostgreSQL + FTS), SQLite (FTS5), Mem0 (vector embeddings), Graphiti (temporal knowledge graph). `CbrCaseMemoryStore` (standalone SPI) provides structured feature-vector similarity search over past cases via CBR. Open `CbrCase` type hierarchy with `cbrType()` discriminator supports Textual, Feature-Vector, and Plan-Based CBR paradigms. Qdrant-backed CBR implementation uses payload filters + optional dense vector + `CbrReconciliationService` (@ApplicationScoped, @Timed, batch upserts, discoverTenants + reconcileAll for post-dimension-change recovery). All backends coexist via three-tier CDI priority ladder.

Tracks `casehubio/neocortex#20`, `casehubio/neocortex#56`, `casehubio/parent#227`.

---

## Module Structure

```
inference-api/      — zero deps: InferenceModel SPI, InferenceInput, InferenceOutput, InferenceException
inference-runtime/  — ONNX Runtime JVM + HuggingFace Tokenizers JNI; OnnxInferenceModel, ModelConfig
inference-tasks/    — NliClassifier, TextClassifier, ScalarRegressor, CrossEncoderReranker
inference-splade/   — sparse SPLADE embeddings (Map<Integer, Float>)
inference-inmem/    — deterministic stubs; no JNI; safe in all test contexts
inference-quarkus/  — CDI wiring, @InferenceModel qualifier, Dev Services, @QuarkusTest
rag-api/            — EmbeddingIngestor + ReactiveEmbeddingIngestor SPIs, CaseRetriever + ReactiveCaseRetriever SPIs, QueryExpander SPI, RetrievalTracker + ReactiveRetrievalTracker SPIs (incl. purgeOlderThan(Instant cutoff)), RetrievalQuery, MetadataExtractor + CursorStore SPIs, RetrievalOutcome enum, RetrievedDocumentRef, RetrievalRecord, RetrievalFeedback, RetrievalRecorded (CDI event), RetrievalAnalyzer (static utility: documentStats, unretrievedDocuments, qualitySignals — pure computation over tracker data), DocumentStats, DocumentQualitySignal, QualitySignal enum, QualityThresholds, value types — Mutiny provided. FusionStrategy and ScoreFusion moved to fusion-api.
fusion-api/         — ScoreFusion (RRF + CC algorithms), FusionStrategy enum, CamelCaseExpander (BM25 token pre-processing). Tier 1 pure Java, zero deps. Shared by RAG and CBR.
rag/                — LangChain4j wiring, Qdrant, configurable hybrid fusion (RRF/DBSF server-side, CC client-side), per-leg embedding separation (dense uses searchText(), sparse/ColBERT use text() via embedBatch() — unconditional when expansion active), SeparateModelEmbedder (EmbeddingModel + optional SparseEmbedder → MultiModalEmbedder adapter, @DefaultBean displaced by BgeM3), MultiModalEmbedderProducer (@DefaultBean CDI, @IfBuildProperty gated), MatryoshkaMultiModalEmbedder.wrapIfNeeded() (consolidates double-wrap prevention), DenseQuantization (binary/scalar), ColbertQuantizationConfig (per-vector quantization for ColBERT multi-vectors), search-time oversampling, @DefaultBean blocking-to-reactive bridges, CorpusIngestionService (event-driven via directory-watcher for filesystem corpora, @Scheduled polling fallback for ZIP-based corpora)
rag-tika/           — optional Apache Tika document parsing → chunked ChunkInput
rag-testing/        — in-memory stubs for both blocking and reactive SPIs + InMemoryCursorStore + InMemoryRelevanceEvaluator (@Alternative @Priority(1) @ApplicationScoped) + InMemoryRetrievalTracker (@Alternative @Priority(1)) + RetrievalTrackerContractTest abstract base (20 tests)
rag-crossencoder/   — Cross-encoder features: Corrective RAG (CRAG) quality-gating + cross-encoder reranking decorator. @Decorator @Priority(100) CRAG, @Priority(75) reranking. Score propagation eliminates double inference when both enabled. evaluateBatchWithScores() on CrossEncoderRelevanceEvaluator, RerankingLogic, ScoredGrade. Classpath + config activated (casehub.rag.crag.enabled, casehub.rag.reranking.enabled)
rag-expansion/      — Query expansion: HyDE (hypothetical documents), step-back prompting (abstract reformulation), multi-query fan-out with RRF fusion; @Decorator on CaseRetriever + ReactiveCaseRetriever, classpath + config activated, original query always prepended to expanded set (record equality via contains()), NoOpQueryExpander @DefaultBean (pass-through when no mode set), ExpansionConfigValidator (startup warning when expansion enabled without mode), explicit mode selection required (`casehub.rag.expansion.mode=llm|step-back|template`)
rag-tracking/       — Retrieval tracking: CDI @Decorator @Priority(50) on CaseRetriever + ReactiveCaseRetriever — records retrieval events via RetrievalTracker SPI, fires RetrievalRecorded CDI events, isAlreadyTracked guard prevents double-recording through bridge. SqliteRetrievalTracker (SQLite + HikariCP WAL + Flyway), BlockingToReactiveRetrievalTracker @DefaultBean bridge, RetentionScheduler (@ApplicationScoped, ScheduledExecutorService daemon thread, casehub.rag.tracking.retention.days default 90, purge every 24h). Classpath + config activated (`casehub.rag.tracking.enabled=true`)
corpus-api/         — CorpusStore + CorpusReader + ChangeSource + WatchableChangeSource + CorpusIntegrity SPIs, reactive variants, value types — zero deps, Hortora-eligible
corpus/             — Zip4j implementation: ZipCorpusStore (rolling archives, chain manifest), FlatCorpusStore, CompositeCorpusStore, compaction, migration — Hortora-eligible
memory-api/         — CaseMemoryStore + ReactiveCaseMemoryStore + ReactiveGraphCaseMemoryStore (extends ReactiveCaseMemoryStore, default graphQuery()) + CbrCaseMemoryStore + ReactiveCbrCaseMemoryStore SPIs, CaseEnrichmentStep SPI, MemoryCapability enum (incl. DISCOVER_TENANTS), CbrCase hierarchy (withFeatures default-throws, overridden in FeatureVectorCbrCase + PlanCbrCase for immutable feature enrichment), CbrQuery (weights + vectorWeight for per-field weighted similarity, RetrievalMode FEATURE_ONLY/SEMANTIC_ONLY/HYBRID, FusionStrategy from fusion-api, Map<String, CbrFilter> filters for structural field predicates, TemporalDecay temporalDecay nullable for smooth recency decay, Path scope required for hierarchical visibility (platform Path from casehub-platform-api), ScopeDecay scopeDecay nullable for scope-distance score decay, withFeatures() for immutable query enrichment), CbrFilter (sealed: Contains, ContainsAll, ContainsAny, NotContains, NotContainsAny, ContainsRange, HasMatch, AllOf — filter-only predicates for structured fields; NotContains/NotContainsAny for CategoricalList negation; ContainsRange for NumericList range matching; AllOf wraps ≥2 filters on same field with polarity-preserving dispatch), CbrFeatureValidator (consolidated store-time, query-time, and filter validation; temporal field validation: ascending timestamps, inner field types), CbrSimilarityScorer (pure-Java weighted composite scoring — three-level precedence: caller override → field SimilaritySpec → type default; categorical table lookup, numeric Gaussian/step/exponential decay, centralized NumericRange via computeNormalizedDistance; temporal fields: DTW for TimeSeries, edit distance for DiscreteSequence; structured fields participate via LocalSimilarityFunction overrides, skipped without override), FeatureValue (sealed: StringVal, NumberVal, RangeVal, StringListVal, NumberListVal, StructVal, StructListVal — typed feature values replacing Map&lt;String, Object&gt;; static factories string/number/range/stringList/numberList/struct/structList; of(Object) handles Boolean → StringVal), DtwSimilarity (O(n×m) dynamic time warping — multi-dimensional Euclidean distance, timestamp field excluded, max(n,m) normalization; returns DtwResult with AlignmentPair alignment path; WarpingConstraint dispatch: Unconstrained, SakoeChibaBand, ItakuraParallelogram with infeasibility detection; 5-arg overload with abandonCostThreshold for early abandonment via row-minimum tracking), LbKeogh (O(n) Sakoe-Chiba envelope computation + lower-bound pruning — computeEnvelope returns Envelope record with upper/lower arrays per dimension, lowerBound computes admissible DTW cost lower bound for pre-filtering candidates before full O(n×m) DP), WarpingConstraint (sealed: Unconstrained, SakoeChibaBand(int windowSize), ItakuraParallelogram(double maxSlope) — slope-bounded warping with ceil/floor rounding-aware infeasibility check inside DP loop), EditDistanceSimilarity (Levenshtein with optional weighted substitution costs + configurable insert/delete costs; returns EditDistanceResult with EditStep alignment path tagged by EditOp MATCH/SUBSTITUTE/INSERT/DELETE; always double[][] DP table; variable-cost normalization: sub-preferred vs del+ins-preferred paths), AlignmentPair (queryIndex, caseIndex — DTW alignment step), DtwResult (score + alignment path), EditOp (MATCH, SUBSTITUTE, INSERT, DELETE), EditStep (queryIndex, caseIndex, EditOp — edit distance step with -1 sentinel for uninvolved index), EditDistanceResult (score + alignment path), LocalSimilarityFunction (@FunctionalInterface, EXACT_MATCH constant), SimilaritySpec (sealed: CategoricalTable, GaussianDecay, StepDecay, ExponentialDecay, DtwSpec, EditDistanceSpec — pure data, schema-attached; DtwSpec(WarpingConstraint constraint) non-null — Unconstrained/SakoeChibaBand/ItakuraParallelogram; EditDistanceSpec(Map substitutionSimilarities, Double insertCost, Double deleteCost) for weighted substitution + variable indel costs; shared validateAndMirrorSimilarityMap with NaN rejection), CbrFeatureSchema (field name uniqueness enforced, optional Double learningRate validated [0,1] for per-caseType EMA speed), FeatureField (sealed: Categorical, Numeric, Text, CategoricalList, NumericList, NestedObject, ObjectList, TimeSeries, DiscreteSequence; optional SimilaritySpec on Categorical/Numeric/TimeSeries/DiscreteSequence, Text.semantic flag, semanticText() factory; NumericList(name, min, max) filter-only List<Number> with per-element range validation; structured variants enforce one-level nesting via validateFlatFields whitelist; TimeSeries: ordered observations with timestamp field, ≥1 non-timestamp Numeric required for DTW, optional TrendSpec for trend feature derivation; DiscreteSequence: ordered List<String> labels), TrendType (SLOPE, DELTA, VOLATILITY, ACCELERATION, CHANGE_POINTS, DURATION, OBSERVATION_COUNT — isPerField() discriminates per-inner-field vs per-TimeSeries), TrendSpec (Set<TrendType> types + ChronoUnit timeUnit — schema-level declaration on TimeSeries, default timeUnit HOURS), TrendProfile (Map<String, Double> metrics + toFeatures()), TrendFieldNaming (deterministic derived field naming: {tsName}_{type}_{innerField} for per-field, {tsName}_{type} for per-TimeSeries; underscore separators avoid Qdrant dot-notation), TrendAnalyzer (pure-Java utility: analyze() computes trend metrics from observations, enrichFeatures() returns new map with derived Numeric values, expandSchema() idempotent schema expansion with heuristic ranges — SLOPE/DELTA/ACCELERATION ±(max-min), VOLATILITY [0,max-min], DURATION durationMax(timeUnit), CHANGE_POINTS/OBSERVATION_COUNT [0,1000]; algorithms: least-squares regression, Welford's stddev, half-split acceleration, CUSUM change-point detection — all O(n)), NumericRange, ScoredCbrCase (caseId field for retrieval traceability, reranked field for double-reranking guard, Path scope for hierarchical scope tracking), CbrOutcome (Outcome enum + EMA adjustConfidence + DEFAULT_LEARNING_RATE; recordOutcome SPI on CbrCaseMemoryStore + ReactiveCbrCaseMemoryStore for CBR Revise feedback loop), CbrRetentionPolicy (tenant/domain/caseType scoped age + count purge; purge(CbrRetentionPolicy) SPI on CbrCaseMemoryStore + ReactiveCbrCaseMemoryStore), TemporalDecay (sealed: HalfLife(Duration), Linear(Duration zeroAt), Step(Duration cutoff, double afterCutoff) — smooth recency decay applied post-scoring before minSimilarity/topK), ScopeDecay (sealed: Exponential(double base), Linear(int maxDepth), Step(double beyondExact) — scope-distance score decay applied by ScopeDecayCbrCaseMemoryStore @Decorator @Priority(85)), OutcomeWeightingFunction (@FunctionalInterface SPI for confidence-based score modulation), ExplanationRenderer (SPI for human-readable retrieval trace rendering), CbrRetrievalTrace (record: traceId, query, List<TracedCase>, timestamp — snapshots retrieval events for compliance audit), CbrRetrievalRecorded (CDI event fired after every retrieveSimilar), CbrRetrievalTracker + ReactiveCbrRetrievalTracker (retrieval traceability SPIs: record, findTraces with domain filtering, purgeOlderThan), BridgedCbrStore (marker interface for double-recording guard), PlanAdapter (SPI for CBR Reuse — adapt(String caseType, ScoredCbrCase, Map features) transforms retrieved PlanCbrCase into AdaptedPlan for current context; caseType is first-class parameter for type-specific adaptation rules), AdaptedPlan (adapted step list), AdaptedStep (bindingName, capabilityName, workerName, stepOutcome, priority, parameters, AdaptationAction, reason), AdaptationAction (RETAINED, SUBSTITUTED, BOOSTED, SUPPRESSED, ADDED, REMOVED), AdaptationTrace (audit record: traceId, retrievalTraceId, caseType, sourceCaseId, sourceScore, steps, currentFeatures, timestamp), CbrAdaptationRecorded (CDI event fired after plan adaptation), CbrCasesErased (sealed interface: ByRequest, ByEntity, ByScope — CDI event fired after erase/eraseEntity/eraseByScope when count > 0), SupersessionStatus (record: caseId, superseded, supersededAt, supersedingCaseId, reason, reinstatedAt — audit query result with wasReinstated() convenience; NOT_SUPERSEDED constant; getSupersessionStatus + findSupersededCases SPI on CbrCaseMemoryStore + ReactiveCbrCaseMemoryStore), PlanEnsembleAnalyzer (SPI for cross-plan structural analysis — analyze(String caseType, List<ScoredCbrCase<PlanCbrCase>>, List<AdaptedPlan>, Map<String, FeatureValue>) examines multiple adapted plans for consensus/divergence and synthesizes an ensemble plan; operates after per-plan PlanAdapter adaptation), EnsemblePlan (synthesizedPlan + List<StepConsensus> stepAnalysis + sourceCaseIds + ensembleConfidence [0,1] + inputPlanCount), StepConsensus (bindingName + nullable capabilityName + occurrenceCount ≥1 + totalPlans + workerDistribution + outcomeDistribution + priorityDistribution + contributingCaseIds + StepAgreement), StepAgreement (UNANIMOUS, CONSENSUS, CONTESTED, MINORITY, UNIQUE), EnsembleTrace (traceId, nullable retrievalTraceId, caseType, sourceCaseIds, stepAnalysis, synthesizedSteps, inputPlanCount, ensembleConfidence, currentFeatures, timestamp), CbrEnsembleRecorded (CDI event fired after ensemble analysis), FeatureStatistics (nearest-rank percentile computation — min, max, median, p75, sampleCount; compute(double[]) factory), CbrSuggestions (featureStats + historicalSuccessRate + experienceCount + averageSimilarity; EMPTY constant, isEmpty(), defensive copy on featureStats), MemoryScanRequest — Mutiny provided
memory/             — MemoryEmitter (@ApplicationScoped fire-and-forget CaseMemoryStore wrapper — error isolation + structured logging for CDI observers and programmatic callers; emit() single, emitAll() batch with partial-failure logging; SecurityException propagates), NoOpCbrCaseMemoryStore @DefaultBean, BlockingToReactiveBridge (@DefaultBean implements ReactiveGraphCaseMemoryStore — fallback for JDBC backends), BlockingToReactiveCbrBridge (implements BridgedCbrStore), CaseEnrichmentDecorator (@Decorator on CaseMemoryStore — applies CaseEnrichmentStep pipeline before store), CbrOutcomeConsumer (@ObservesAsync @CloudEventType(CbrEventTypes.CBR_OUTCOME) — deserializes CloudEvent data to CbrOutcomeData, bridges to CbrCaseMemoryStore.recordOutcome; depends on casehub-desiredstate-api + jackson-databind provided), OutcomeWeightingCbrCaseMemoryStore (@Decorator @Priority(65) on CbrCaseMemoryStore + reactive parity — modulates retrieval scores by case confidence; @IfBuildProperty casehub.cbr.outcome-weighting.enabled), DefaultOutcomeWeightingFunction (@DefaultBean — linear interpolation score*(1-α+α*confidence), α from casehub.cbr.outcome-weighting.influence default 0.3), DefaultExplanationRenderer (@DefaultBean — generic structural rendering from CbrRetrievalTrace), NoOpPlanAdapter (@DefaultBean — returns all steps RETAINED, zero behavioral change), ScopeDecayCbrCaseMemoryStore (@Decorator @Priority(85) on CbrCaseMemoryStore + reactive parity — applies ScopeDecay score multiplier by scope depth distance; null scopeDecay = pass-through; re-sorts and filters by minSimilarity after decay), TrendEnrichmentCbrCaseMemoryStore (@Decorator @Priority(90) on CbrCaseMemoryStore + reactive parity — intercepts registerSchema (expandSchema), store (enrichFeatures on case), retrieveSimilar (enrichFeatures on query); schema-driven activation via TrendSpec, no @IfBuildProperty; ConcurrentHashMap<String, CbrFeatureSchema> internal state), ErasureNotificationCbrCaseMemoryStore (@Decorator @Priority(45) on CbrCaseMemoryStore — fires CbrCasesErased.ByRequest/ByEntity/ByScope CDI events after erasure; blocking-only, no reactive counterpart; Clock injection for testability), NoOpPlanEnsembleAnalyzer (@DefaultBean — picks best-scoring adapted plan, reports inputPlanCount=1 with UNANIMOUS agreement, zero behavioral change)
memory-testing/     — CbrCaseMemoryStoreContractTest abstract base (142 tests incl. 30 structured field tests, 14 NumericList/filter/AllOf tests, 23 temporal field tests, 9 recordOutcome/EMA tests, 1 caseId round-trip test, 5 purge retention tests, 3 temporal decay tests, 5 trend detection tests, 8 eraseByScope scope-hierarchy tests), CbrRetrievalTrackerContractTest abstract base (10 tests incl. domain isolation), InMemoryCbrRetrievalTracker, PlanEnsembleAnalyzerContractTest abstract base (7 tests)
memory-cbr-inmem/   — InMemoryCbrCaseMemoryStore @Alternative @Priority(2) — in-memory stub for tests, clearCases() for test isolation (clears cases, preserves schemas), ReactiveInMemoryCbrCaseMemoryStore @Alternative @Priority(2) — native reactive wrapper (no worker pool dispatch)
memory-cbr-embedding/ — EmbeddingTextSimilarity: EmbeddingModel-based LocalSimilarityFunction for semantic text field cosine similarity, batch precompute() via embedAll(), cache-backed compute(). Depends on memory-api + langchain4j-core only — zero Qdrant deps
memory-cbr-crossencoder/ — Cross-encoder reranking @Decorator @Priority(75) on CbrCaseMemoryStore + ReactiveCbrCaseMemoryStore. Sigmoid-normalized scores, double-reranking guard via ScoredCbrCase.reranked(). Classpath + config activated (casehub.cbr.reranking.enabled). Depends on memory-api + inference-tasks
memory-cbr-tracking/  — Retrieval tracking: CDI @Decorator @Priority(50) on CbrCaseMemoryStore + ReactiveCbrCaseMemoryStore — records retrieval events via CbrRetrievalTracker SPI, fires CbrRetrievalRecorded CDI events, bridge-detection guard (BridgedCbrStore marker) prevents double-recording through BlockingToReactiveCbrBridge. SqliteCbrRetrievalTracker (SQLite + HikariCP WAL + Flyway), BlockingToReactiveCbrRetrievalTracker @DefaultBean bridge, @Scheduled retention purge (casehub.cbr.tracking.retention.days default 90, purge every 24h). TrackingPlanAdapter @Decorator @Priority(50) on PlanAdapter — fires CbrAdaptationRecorded CDI events after plan adaptation (`casehub.cbr.adaptation-tracking.enabled=true`). TrackingPlanEnsembleAnalyzer @Decorator @Priority(50) on PlanEnsembleAnalyzer — fires CbrEnsembleRecorded CDI events after ensemble analysis (`casehub.cbr.ensemble-tracking.enabled=true`). Classpath + config activated (`casehub.cbr.tracking.enabled=true`)
memory-qdrant/      — ReactiveQdrantCbrCaseMemoryStore (@ApplicationScoped, canonical — async gRPC via ListenableFuture→Uni, parallel search legs) + QdrantCbrCaseMemoryStore (thin delegate via .await().indefinitely()) + QdrantCbrBeanProducer (produces CbrCollectionManager) — Qdrant-backed CBR with payload filters (categorical/numeric/text + structured: CategoricalList/NestedObject/ObjectList) + dense vector search (cosine similarity on problem() via EmbeddingModel, with minSimilarity threshold) + SPLADE sparse embeddings (optional SparseEmbedder via CDI Instance) + BM25 server-side inference (Qdrant Document vectors via CamelCaseExpander) + dynamic 2-4 leg hybrid fusion (CC weight renormalization among active semantic legs) + notBefore temporal filtering + structural filter translation (CbrQueryTranslator.applyStructuralFilters — Contains→matchKeyword, ContainsAll→multiple must, ContainsAny→matchKeywords, HasMatch→nested()/dot-notation) + per-inner-field payload indexes for NestedObject (dot-notation) and ObjectList (array path) + dimension validation (gated by allow-dimension-migration config, default false) + collection schema evolution (recreate with sparse vectors when SPLADE/BM25 enabled), two-pass retrieveSimilar() with batch precompute for semantic text fields, CbrPointBuilder (structured value serialization: List→toListValue, Map→toStructValue for native Qdrant payload), CbrReconciliationService (@ApplicationScoped, three-phase: orphan cleanup + reindex + vector enrichment backfill, discoverTenants + reconcileAll, @Timed + Micrometer counters, chunked batch upserts/updateVectors), auto-wires when on classpath (optional EmbeddingModel + SparseEmbedder + CaseMemoryStore via Instance), Testcontainers integration tests
memory-inmem/       — InMemoryMemoryStore @Alternative @Priority(10) — volatile ConcurrentHashMap, test + ephemeral + discoverTenants, ReactiveInMemoryMemoryStore @Alternative @Priority(10) — native reactive wrapper (no worker pool dispatch)
memory-jpa/         — JpaMemoryStore @ApplicationScoped — PostgreSQL + Flyway V1000 + FTS via websearch_to_tsquery + discoverTenants
memory-sqlite/      — SqliteMemoryStore @Alternative @Priority(1) — SQLite + HikariCP WAL + FTS5 + discoverTenants
memory-mem0/        — ReactiveMem0CaseMemoryStore @Alternative @Priority(1) (canonical — reactive REST client, all logic here) + Mem0CaseMemoryStore (thin delegate via .await().indefinitely()), ReactiveMem0Client (@RegisterRestClient, Uni<> returns)
memory-graphiti/    — ReactiveGraphitiCaseMemoryStore @Alternative @Priority(2) implements ReactiveGraphCaseMemoryStore (canonical — reactive REST client, all logic incl. graphQuery()) + GraphitiCaseMemoryStore (thin delegate via .await().indefinitely()), ReactiveGraphitiClient (@RegisterRestClient, Uni<> returns)
examples/
  example-text-analysis/  — standalone demos: NLI, zero-shot classification, scoring, reranking, SPLADE — no Quarkus
  example-rag-pipeline/   — Quarkus demos: corpus ingestion (flat + zip), hybrid search, CDI wiring — requires Qdrant
evaluation/
  code_domain_embeddings/  — Python evaluation scripts for #49: tokenizer analysis, embedding discrimination, benchmark runner, deployment check. Requires own venv (not Maven). Run with `python3 -m evaluation.code_domain_embeddings.<script>`.
```

Examples are excluded from the default build. Activate with `-Pexamples-smoke` (in-memory stubs) or `-Pexamples` (real ONNX models + Testcontainers Qdrant).

## Maven Coordinates

| Element | Value |
|---|---|
| GitHub repo | `casehubio/neocortex` |
| groupId | `io.casehub` |
| Parent artifactId | `casehub-neocortex-parent` |
| Inference API | `casehub-neocortex-inference-api` |
| Inference Runtime | `casehub-neocortex-inference-runtime` |
| Inference Tasks | `casehub-neocortex-inference-tasks` |
| Inference SPLADE | `casehub-neocortex-inference-splade` |
| Inference in-memory | `casehub-neocortex-inference-inmem` |
| Inference Quarkus | `casehub-neocortex-inference-quarkus` |
| Fusion API | `casehub-neocortex-fusion-api` |
| RAG API | `casehub-neocortex-rag-api` |
| RAG | `casehub-neocortex-rag` |
| RAG Tika | `casehub-neocortex-rag-tika` |
| RAG testing | `casehub-neocortex-rag-testing` |
| RAG Cross-Encoder | `casehub-neocortex-rag-crossencoder` |
| RAG Expansion | `casehub-neocortex-rag-expansion` |
| RAG Tracking | `casehub-neocortex-rag-tracking` |
| Corpus API | `casehub-neocortex-corpus-api` |
| Corpus | `casehub-neocortex-corpus` |
| Memory API | `casehub-neocortex-memory-api` |
| Memory CDI | `casehub-neocortex-memory` |
| Memory testing | `casehub-neocortex-memory-testing` |
| Memory CBR in-memory | `casehub-neocortex-memory-cbr-inmem` |
| Memory CBR Embedding | `casehub-neocortex-memory-cbr-embedding` |
| Memory CBR Cross-Encoder | `casehub-neocortex-memory-cbr-crossencoder` |
| Memory CBR Tracking | `casehub-neocortex-memory-cbr-tracking` |
| Memory CBR Qdrant | `casehub-neocortex-memory-qdrant` |
| Memory In-Memory | `casehub-neocortex-memory-inmem` |
| Memory JPA | `casehub-neocortex-memory-jpa` |
| Memory SQLite | `casehub-neocortex-memory-sqlite` |
| Memory Mem0 | `casehub-neocortex-memory-mem0` |
| Memory Graphiti | `casehub-neocortex-memory-graphiti` |
| Example Text Analysis | `casehub-neocortex-example-text-analysis` |
| Example RAG Pipeline | `casehub-neocortex-example-rag-pipeline` |
| Root Java package (inference) | `io.casehub.neocortex.inference` |
| Root Java package (fusion) | `io.casehub.neocortex.fusion` |
| Root Java package (rag) | `io.casehub.neocortex.rag` |
| Root Java package (examples) | `io.casehub.neocortex.examples.analysis`, `io.casehub.neocortex.examples.rag` |
| Root Java package (rag-crossencoder) | `io.casehub.neocortex.rag.crossencoder` |
| Root Java package (rag-expansion) | `io.casehub.neocortex.rag.expansion` |
| Root Java package (rag-tracking) | `io.casehub.neocortex.rag.tracking` |
| Root Java package (corpus) | `io.casehub.neocortex.corpus` |
| Root Java package (memory) | `io.casehub.neocortex.memory` |
| Root Java package (memory-cbr) | `io.casehub.neocortex.memory.cbr` |
| Root Java package (memory-cbr-embedding) | `io.casehub.neocortex.memory.cbr.embedding` |
| Root Java package (memory-cbr-crossencoder) | `io.casehub.neocortex.memory.cbr.crossencoder` |
| Root Java package (memory-cbr-tracking) | `io.casehub.neocortex.memory.cbr.tracking` |
| Root Java package (memory-qdrant) | `io.casehub.neocortex.memory.cbr.qdrant` |

## Build Commands

```bash
# Build all modules
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install

# Build without tests
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -DskipTests

# Build specific module
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -pl inference-api

# Examples — smoke tests (no models, no Docker, seconds)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean test -Pexamples-smoke

# Examples — full tests (downloads ONNX models, Testcontainers Qdrant)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean test -Pexamples
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

## Native Image — JVM Mode by Design

The inference service is long-running — native image's fast startup provides no benefit, and HotSpot's JIT optimisation outperforms AOT for sustained workloads. `inference-*` modules operate in JVM mode.

The C2 native image gate passed (ONNX Runtime JNI + HuggingFace Tokenizers JNI both work in Quarkus native image on macOS ARM). Reachability metadata ships in `inference-quarkus/src/main/resources/META-INF/native-image/` for downstream consumers that distribute as native binaries (e.g. Hortora CLI).

---

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/neocortex
**Changelog:** GitHub Releases
