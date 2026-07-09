# CBR Fusion Consolidation + SPLADE/BM25 Legs

**Issues:** #124, #123, #122
**Date:** 2026-07-08
**Branch:** issue-124-cbr-fusion-consolidation

## Problem

Three fusion implementations duplicate the same two algorithms (RRF and Convex Combination):

| Class | Module | Type | Domain behavior |
|-------|--------|------|----------------|
| `ScoreFusion` | memory-api (Tier 1) | generic `<T>` | none (putIfAbsent) |
| `RrfFusion` | rag-api (Tier 1) | hardcoded `RetrievedChunk` | grade merging, dedupKey |
| `ConvexCombinationFusion` | rag-api (Tier 1) | hardcoded `RetrievedChunk` | grade merging, dedupKey |

The grade-merging behavior in `RrfFusion` and `ConvexCombinationFusion` is dead code — all chunks entering fusion are `UNGRADED` (grading happens downstream in the CRAG decorator, after fusion). The three implementations are functionally equivalent.

Consolidation is blocked by a module dependency constraint: `memory-api` and `rag-api` are independent Tier 1 modules with zero cross-dependency. `ScoreFusion` is in `memory-api`, so `rag-api` callers can't reach it. Neither module can depend on the other — CBR memory SPI and RAG retrieval SPI are peer domains.

Additionally, two duplicate fusion strategy enums exist: `FusionStrategy` in rag-api (`RRF, DBSF, CC`) and `CbrFusionStrategy` in memory-api (`RRF, CC`).

## Design

### 1. New `fusion-api` Module

**Module:** `fusion-api` (`casehub-neocortex-fusion-api`)
**Package:** `io.casehub.neocortex.fusion`
**Tier:** 1 — pure Java, zero first-party deps

**Contents:**

| Class | Origin | Notes |
|-------|--------|-------|
| `ScoreFusion` | moved from memory-api | Package rename to `io.casehub.neocortex.fusion` |
| `FusionStrategy` | new, unifies rag-api `FusionStrategy` + memory-api `CbrFusionStrategy` | `enum FusionStrategy { RRF, DBSF, CC }` |
| `CamelCaseExpander` | moved from rag | BM25 token pre-processing, shared by RAG and CBR |

**Removals:**

| Class | Module |
|-------|--------|
| `RrfFusion` | rag-api |
| `ConvexCombinationFusion` | rag-api |
| `FusionStrategy` | rag-api |
| `CbrFusionStrategy` | memory-api |
| `ScoreFusion` | memory-api |
| `CamelCaseExpander` | rag |
| `RrfFusionTest` | rag-api (test) |
| `ConvexCombinationFusionTest` | rag-api (test) |
| `CamelCaseExpanderTest` | rag (test) |

**Dependency changes:**

| Module | New dependency | Reason |
|--------|---------------|--------|
| `memory-api` | `fusion-api` | CbrQuery references FusionStrategy |
| `rag-expansion` | `fusion-api` | Uses ScoreFusion (replacing RrfFusion) |
| `rag` (runtime) | `fusion-api` | Uses ScoreFusion + FusionStrategy |
| `rag-api` | none added | Drops FusionStrategy, RrfFusion, ConvexCombinationFusion; no new deps |

### 2. Caller Migration

**CbrQuery:** `fusionStrategy` field type changes from `CbrFusionStrategy` to `FusionStrategy`. Mechanical — `CbrFusionStrategy.RRF` becomes `FusionStrategy.RRF`.

**RetrievedChunk gains two methods** to keep RAG call sites clean:

```java
public String fusionKey() {
    return sourceDocumentId + "\0" + content;
}

public RetrievedChunk withRelevanceScore(double score) {
    return new RetrievedChunk(content, sourceDocumentId, score, metadata, grade);
}
```

**QueryExpandingCaseRetriever / ReactiveQueryExpandingCaseRetriever:**

```java
// Before:
return RrfFusion.fuse(resultSets, maxResults);

// After:
List<ScoredLeg<RetrievedChunk>> legs = resultSets.stream()
    .map(rs -> new ScoredLeg<>(rs, RetrievedChunk::relevanceScore, 1.0))
    .toList();
return ScoreFusion.rrf(legs, RetrievedChunk::fusionKey, maxResults, 60)
    .stream().map(f -> f.item().withRelevanceScore(f.score())).toList();
```

**HybridCaseRetriever / ReactiveHybridCaseRetriever (CC path):**

Replace `ConvexCombinationFusion.fuse(legs, maxResults)` with equivalent `ScoreFusion.convexCombination()` call using `RetrievedChunk::fusionKey` as ID extractor and `RetrievedChunk::relevanceScore` as score extractor, mapping `FusedResult` back to `RetrievedChunk` via `withRelevanceScore()`.

**QdrantCbrCaseMemoryStore:** Import path changes only — `io.casehub.neocortex.memory.ScoreFusion` becomes `io.casehub.neocortex.fusion.ScoreFusion`.

### 3. SPLADE Leg for CBR (#122)

**New dependency:** memory-qdrant → `inference-splade` (for `SparseEmbedder`).

**Injection:** Optional CDI — SPLADE activates only when `SparseEmbedder` is on the classpath AND config enables it.

```java
@Inject
QdrantCbrCaseMemoryStore(..., Instance<SparseEmbedder> sparseEmbedderInstance, ...) {
    this.sparseEmbedder = sparseEmbedderInstance.isResolvable()
        ? sparseEmbedderInstance.get() : null;
}
```

**Ingestion (`store()`):** When `sparseEmbedder != null` and SPLADE is enabled, embed `cbrCase.problem()` with `SparseEmbedder.embed()`. Store the resulting `Map<Integer, Float>` as a sparse named vector alongside the existing dense vector.

**Collection schema:** `CbrCollectionManager` adds a sparse vector configuration when SPLADE is enabled. Named vector: configurable (default `"sparse"`).

**Retrieval (`retrieveHybrid()`):** New SPLADE leg:
1. Embed `query.problem()` with `SparseEmbedder.embed()`
2. Execute sparse vector query via `QueryFactory.nearest(sparseValues, sparseIndices)`
3. Reconstruct candidates from scored points
4. Add as a `ScoredLeg` in the fusion alongside dense and feature legs

**Degradation:** If `sparseEmbedder == null` or SPLADE not enabled or `query.problem() == null`, the SPLADE leg is silently skipped. HYBRID mode means "use all available legs." `RetrievalMode` enum does not change.

**Text indexed:** `problem()` only — consistent with dense embedding. SPLADE adds lexical matching over problem terms.

**Config:**
- `casehub.cbr.splade.enabled` (boolean, default `false`)
- `casehub.cbr.splade.vector-name` (string, default `"sparse"`)
- `casehub.cbr.splade.top-k` (int, default follows query topK)

### 4. BM25 Leg for CBR (#123)

**No new dependency.** BM25 is Qdrant server-side inference — the Qdrant client already supports `Document` queries.

**Ingestion (`store()`):** When BM25 is enabled, store `cbrCase.problem()` text as a BM25 named vector using the Qdrant `Document` proto. Pre-process with `CamelCaseExpander.expand()` (now in fusion-api). Qdrant tokenizes and indexes server-side.

**Collection schema:** `CbrCollectionManager` adds a BM25 text-index vector configuration when enabled. Named vector: configurable (default `"bm25"`). Model name: configurable (default `"Qdrant/bm25"`).

**Retrieval (`retrieveHybrid()`):** New BM25 leg:
1. Pre-process `query.problem()` with `CamelCaseExpander.expand()`
2. Construct `Document` query with expanded text and model name
3. Execute against BM25 named vector
4. Reconstruct candidates from scored points
5. Add as a `ScoredLeg` in the fusion

**Degradation:** If BM25 not enabled or `query.problem() == null`, the BM25 leg is silently skipped.

**Config:**
- `casehub.cbr.bm25.enabled` (boolean, default `false`)
- `casehub.cbr.bm25.vector-name` (string, default `"bm25"`)
- `casehub.cbr.bm25.model` (string, default `"Qdrant/bm25"`)
- `casehub.cbr.bm25.top-k` (int, default follows query topK)

### 5. Weighting Model for Multi-Leg CC Fusion

**CbrQuery record: no changes.** The existing `vectorWeight` field preserves the per-query contract.

| Leg | CC Weight |
|-----|-----------|
| Feature | `1.0 - vectorWeight` |
| Dense | `vectorWeight × denseShare` |
| SPLADE | `vectorWeight × sparseShare` |
| BM25 | `vectorWeight × bm25Share` |

`denseShare + sparseShare + bm25Share` are renormalized from config. If a leg is disabled, its share redistributes automatically.

**Per-query:** `vectorWeight` controls the feature/semantic split — application logic.
**Per-config:** The breakdown within semantic legs is infrastructure tuning.

**Config:**
- `casehub.cbr.cc-weights.dense` (double, default `0.6`)
- `casehub.cbr.cc-weights.sparse` (double, default `0.2`)
- `casehub.cbr.cc-weights.bm25` (double, default `0.2`)

**RRF:** Weights irrelevant — rank-based, all legs participate equally.

### 6. Reconciliation + Tests

**CbrReconciliationService:** `reconcileAll()` backfills sparse vectors (SPLADE) and BM25 text for cases stored before those legs were enabled.

**Contract tests (QdrantCbrCaseMemoryStoreTest, Testcontainers):**
- HYBRID retrieval with 3 legs (dense + SPLADE + feature)
- HYBRID retrieval with 3 legs (dense + BM25 + feature)
- HYBRID retrieval with 4 legs (all active)
- Degradation: SPLADE unavailable → skipped silently, results returned
- Degradation: BM25 unavailable → skipped silently
- CC weighting: verify per-config weight distribution affects ranking
- Reconciliation: cases stored without sparse vectors → reconcile → SPLADE retrieval succeeds

**InMemoryCbrCaseMemoryStore:** No changes — the in-memory stub implements the SPI with simple feature matching. Multi-leg tests are Qdrant-specific.

## Garden Context

- **GE-20260708-9213d2:** Fusion ID extraction must use storage-level unique IDs. Applied: ScoreFusion uses `idExtractor` function; CBR uses `pointId`, RAG uses `fusionKey()`.
- **GE-20260705-b59012:** Qdrant Formula query cannot reference prefetch leg scores — CC requires client-side fusion. Applied: CBR CC fusion is client-side via ScoreFusion, consistent with RAG's pattern.
