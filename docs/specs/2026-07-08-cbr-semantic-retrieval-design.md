# CBR Phase 3 — Semantic Case Retrieval (Bridge CBR + RAG)

**Issue:** casehubio/neocortex#83
**Epic:** #81 — CBR roadmap, Phase 3
**Date:** 2026-07-08
**Prerequisites:** #70 (dense vector search, closed), #82 (feature-based scoring, closed)

## Summary

Bridge CBR and RAG retrieval techniques: hybrid two-pass retrieval (feature
filtering + semantic ranking), RRF/CC fusion of scored legs, optional
cross-encoder reranking, and explicit retrieval mode configuration on
`CbrQuery`.

## API Changes — `memory-api`

### `RetrievalMode` enum

```java
public enum RetrievalMode {
    FEATURE_ONLY,    // feature filtering + scoring only, no vector search
    SEMANTIC_ONLY,   // dense vector search only, no feature scoring
    HYBRID           // both legs, fused via CbrFusionStrategy
}
```

Hard gate — `FEATURE_ONLY` ignores `problem`/`vectorWeight` even if populated.
`SEMANTIC_ONLY` ignores `features`/`weights`. No validation errors — the mode
wins silently.

### `CbrFusionStrategy` enum

```java
public enum CbrFusionStrategy {
    RRF,  // Reciprocal Rank Fusion (rank-based, scale-independent)
    CC    // Convex Combination (score-based, min-max normalized, uses vectorWeight)
}
```

### `CbrQuery` additions

Two new fields:

| Field | Type | Default |
|-------|------|---------|
| `retrievalMode` | `RetrievalMode` | `HYBRID` |
| `fusionStrategy` | `CbrFusionStrategy` | `RRF` |

Existing `vectorWeight` continues to serve as the CC weight parameter when
strategy is CC. Ignored by RRF.

Builder methods following the existing `with*()` pattern:

- `withRetrievalMode(RetrievalMode)` — sets retrieval mode
- `withFusionStrategy(CbrFusionStrategy)` — sets fusion strategy

Updated `CbrQuery.of()` factory supplies defaults:
`retrievalMode=HYBRID`, `fusionStrategy=RRF`. All existing `with*()`
methods pass through the two new fields.

### Auto-degradation

When prerequisites for the requested mode are absent, the store
auto-degrades silently (with a log warning):

| Requested mode | Missing prerequisite | Effective mode |
|----------------|---------------------|----------------|
| `HYBRID` | `problem()` is null | `FEATURE_ONLY` |
| `HYBRID` | No `EmbeddingModel` | `FEATURE_ONLY` |
| `SEMANTIC_ONLY` | `problem()` is null | Return empty list |
| `SEMANTIC_ONLY` | No `EmbeddingModel` | Return empty list |

No validation error — this matches the existing organic fallback behavior
in `QdrantCbrCaseMemoryStore` and extends it to explicit mode dispatch.

### `ScoreFusion` utility class

Package: `io.casehub.neocortex.memory` (domain-neutral, shared by CBR and RAG).

```java
public final class ScoreFusion {

    record ScoredLeg<T>(
        List<T> items,
        ToDoubleFunction<T> scoreExtractor,
        double weight
    ) {}

    record FusedResult<T>(T item, double score) {}

    static <T> List<FusedResult<T>> rrf(
        List<ScoredLeg<T>> legs,
        Function<T, String> idExtractor,
        int topK,
        double k   // RRF constant, default 60
    );

    static <T> List<FusedResult<T>> convexCombination(
        List<ScoredLeg<T>> legs,
        Function<T, String> idExtractor,
        int topK
    );
}
```

Generic over `T` — operates on `ScoredCbrCase` or any future type. Score
extraction via function reference. ID extraction for deduplication across legs.

**RRF algorithm:** Each leg is sorted internally by `scoreExtractor` descending
before computing ranks — no pre-sorted precondition on callers. For each
candidate across all legs, compute `score = Σ 1/(k + rank_i)` where `rank_i`
is the candidate's 1-based position in leg `i`. Candidates absent from a leg
contribute nothing. Sort descending, take `topK`.

**CC algorithm:** Per leg, min-max normalize scores to [0, 1]. When all items
in a leg have the same score (min = max), all items receive normalized score
1.0 (treats equal scores as "all equally good"). Per candidate,
`composite = Σ(weight_i × normalized_score_i)`. Candidates absent from a
leg contribute 0.0 for that leg's weighted term (penalized for not being
found by that retrieval strategy — same convention as RRF's "contribute
nothing"). Weights renormalized to sum to 1.0. Dedup by ID (keep highest
composite). Sort descending, take `topK`.

**Score normalization:** RRF output scores are normalized to [0, 1] by
dividing by the theoretical maximum `N/(k+1)` where N is the number of legs.
This ensures all fused scores fit in `ScoredCbrCase`'s `[-1, 1]` range and
are interpretable as relative ranking quality.

**`minSimilarity` interaction:** `minSimilarity` filtering is **skipped** for
RRF fusion — RRF scores are rank-based, not similarity-based, so a similarity
threshold is semantically meaningless. Only `topK` truncation applies. For CC
fusion, `minSimilarity` applies normally (CC scores are weighted similarity
values in [0, 1]).

**Relationship to `RrfFusion`:** `rag-api` contains `RrfFusion`, a
`RetrievedChunk`-specific RRF implementation used by query expansion.
`ScoreFusion` is the generic replacement. Issue #124 tracks migrating
`RrfFusion` callers to `ScoreFusion` and removing `RrfFusion`.

### `ScoredCbrCase` changes

New field:

| Field | Type | Default |
|-------|------|---------|
| `reranked` | `boolean` | `false` |

Backward-compatible two-arg constructor retained: `ScoredCbrCase(cbrCase, score)`
delegates to `ScoredCbrCase(cbrCase, score, false)`.

`withReranked()` method returns a copy with `reranked=true`. Used by the
reranking decorator to stamp results and prevent double-reranking.

### `CbrSimilarityScorer.compositeScore()` — superseded

`compositeScore(featureScore, vectorScore, vectorWeight)` is superseded by
`ScoreFusion.convexCombination()` and removed. The single call site in
`QdrantCbrCaseMemoryStore` is replaced by the mode-dispatched fusion path.

## `QdrantCbrCaseMemoryStore` Changes — `memory-qdrant`

### Mode-dispatched retrieval

`retrieveSimilar()` dispatches on `query.retrievalMode()`, applying
auto-degradation when prerequisites are absent (see §Auto-degradation):

**`FEATURE_ONLY`:**
- Filter-only scroll (identity filters: tenant, domain, caseType, notBefore)
- Reconstruct cases from payload
- Score via `CbrSimilarityScorer.score()` with query features/weights
- Precompute `EmbeddingTextSimilarity` for semantic text fields
- Filter by `minSimilarity`, sort, take `topK`
- This is the current filter-only path, unchanged

**`SEMANTIC_ONLY`:**
- Embed `problem` text via `EmbeddingModel`
- Dense vector search in Qdrant (cosine similarity)
- Reconstruct cases from payload
- Use Qdrant vector score directly as ranking score
- Filter by `minSimilarity`, sort, take `topK`
- No feature scoring

**`HYBRID`:**
1. Execute dense vector search — `topK * oversampleFactor` candidates, each
   with a vector score from Qdrant
2. Execute filter-only scroll — `max(topK, overFetchLimit)` candidates
3. Merge candidate pools by case ID — reconstruct each case once
4. Precompute `EmbeddingTextSimilarity` for semantic text fields —
   batch-embed all text values across merged candidates (same two-pass
   pattern as FEATURE_ONLY path)
5. Score feature leg: `CbrSimilarityScorer.score()` on all candidates
   (dense-only candidates get feature-scored too — they have payload)
6. Build two `ScoredLeg`s:
   - Feature leg: all candidates with feature scores,
     weight = `1 - vectorWeight`
   - Semantic leg: candidates from dense search with vector scores,
     weight = `vectorWeight`
7. Call `ScoreFusion.rrf()` or `ScoreFusion.convexCombination()` based on
   `fusionStrategy`
8. If `fusionStrategy` is CC: filter by `minSimilarity`, take `topK`.
   If RRF: skip `minSimilarity` (rank-based scores are not similarity
   values), take `topK` only

### Candidate pool merging

- A candidate in both legs gets both scores (participates in both RRF
  rankings or both CC weighted sums)
- A candidate in only the feature leg has no vector score (absent from
  semantic ranking in RRF, zero semantic contribution in CC)
- A candidate in only the dense leg gets feature-scored client-side
  (payload is available for scoring)
- Deduplication by case ID during merge — each case reconstructed once

### Oversampling

Both legs overfetch independently. Existing `oversampleFactor` (dense) and
`overFetchLimit` (scroll) config properties apply. Fusion output trimmed to
`topK`.

## `InMemoryCbrCaseMemoryStore` Changes — `memory-cbr-inmem`

Mode behavior (no `EmbeddingModel` available):

| Mode | Behavior |
|------|----------|
| `FEATURE_ONLY` | Current behavior — feature scoring only |
| `SEMANTIC_ONLY` | Return empty list (no embedding capability) |
| `HYBRID` | Auto-degrade to `FEATURE_ONLY` with log warning |

No code changes required for `FEATURE_ONLY`. `SEMANTIC_ONLY` and `HYBRID`
handling added to `retrieveSimilar()` with mode dispatch matching the
auto-degradation table in §Auto-degradation.

## New Module — `memory-cbr-crossencoder`

### Purpose

Optional cross-encoder reranking for CBR retrieval. Classpath-activated,
isolates the heavy `inference-tasks` dependency (ONNX Runtime JVM).

### Dependencies

- `memory-api`
- `inference-tasks`

### `RerankingCbrCaseMemoryStore`

`@Decorator @Priority(75)` on `CbrCaseMemoryStore`. Priority 75 positions
the reranking decorator as the outermost in the chain — it sees results
after any inner decorators (at higher priority values) have processed them,
making reranking the final refinement step before results reach the caller.

Behaviour:
1. Overfetch: `max(topK, config.rerankPoolSize())`
2. Delegate to wrapped store
3. Extract `problem()` text from query and each candidate case
4. Call `CrossEncoderReranker.rerank(query.problem(), candidateProblems)`
5. Sigmoid-normalize raw cross-encoder logits: `1 / (1 + exp(-rawScore))`
   → scores in [0, 1]. Cross-encoder models (e.g., MS-MARCO) output
   unbounded logits; `ScoredCbrCase` validates `[-1, 1]`.
6. Re-sort by normalized score, trim to original `topK`. Output
   `ScoredCbrCase` instances carry the normalized cross-encoder score
   (not the original fused score).
7. Stamp results via `ScoredCbrCase.withReranked()` — sets `reranked=true`

Guards:
- `FEATURE_ONLY` mode: skip entirely (no semantic text to cross-encode)
- `problem()` is null: skip entirely
- Config disabled: pass-through
- Already reranked (`ScoredCbrCase.reranked()` true on all inputs): pass-through

### CDI wiring

Constructor injection: `Instance<CrossEncoderReranker>` (optional resolution,
not direct injection). If `rerankerInstance.isResolvable()` is false,
pass-through — consistent with the other guards. The consuming application
must produce a `CrossEncoderReranker` CDI bean (typically via
`inference-quarkus` model configuration). Follows the established pattern
from `rag-crossencoder`'s `CrossEncoderBeanProducer`.

### Activation

- Classpath: module on classpath
- Config: `casehub.cbr.reranking.enabled=true`
- `@IfBuildProperty` gated

### Reactive parity

`RerankingReactiveCbrCaseMemoryStore` — `@Decorator @Priority(75)` on
`ReactiveCbrCaseMemoryStore`. Same logic, returns `Uni<List<ScoredCbrCase>>`.

## Testing

### `ScoreFusion` unit tests (memory-api test sources)

- RRF with two legs, disjoint candidates — rank-based scoring
- RRF with overlapping candidates — score accumulation across legs
- RRF score normalization — output in [0, 1]
- CC with two legs — min-max normalization and weighted sum
- CC with disjoint candidates — absent contributes 0.0
- Single-leg fusion — degenerates to passthrough
- Empty leg — handled gracefully
- `topK` trimming
- CC weight renormalization
- CC with constant-score leg — all items normalized to 1.0 (no division by zero)

### `CbrCaseMemoryStoreContractTest` additions (memory-testing)

Tests all implementations (including `InMemoryCbrCaseMemoryStore`) must pass:

- `FEATURE_ONLY` with `problem` populated — `problem` ignored
- Default `retrievalMode` is `HYBRID`
- Default `fusionStrategy` is `RRF`
- `HYBRID` without `EmbeddingModel` — degrades to `FEATURE_ONLY`
- `SEMANTIC_ONLY` without `EmbeddingModel` — returns empty list

### `QdrantCbrCaseMemoryStore` integration tests (memory-qdrant, Testcontainers)

- `HYBRID`: cases with varying feature AND problem similarity — fusion ranks
  cases strong on both legs higher
- `FEATURE_ONLY` vs `SEMANTIC_ONLY` — different ranking orders, same case set
- `SEMANTIC_ONLY` with `features` populated — features ignored
- `HYBRID` returns results ranked by fused score
- RRF vs CC on same data — different but valid orderings
- RRF results not filtered by `minSimilarity`
- Oversampling — candidate pool large enough for effective fusion

### `memory-cbr-crossencoder` tests

- Reranking decorator with in-memory stub backend
- `FEATURE_ONLY` skips reranking
- Disabled config skips reranking
- Overfetch + trim to `topK`
- Double-reranking guard
- Sigmoid normalization of cross-encoder scores — output in [0, 1]

## Module Placement Summary

| Component | Module | Rationale |
|-----------|--------|-----------|
| `RetrievalMode` enum | `memory-api` | API type on `CbrQuery` |
| `CbrFusionStrategy` enum | `memory-api` | API type |
| `ScoreFusion` utility | `memory-api` | Pure algorithm, zero deps, shared by all backends |
| `CbrQuery` field additions | `memory-api` | Record change |
| `ScoredCbrCase` `reranked` field | `memory-api` | Record change, used by reranking decorator |
| `compositeScore()` removal | `memory-api` | Superseded by `ScoreFusion.convexCombination()` |
| Two-pass retrieval | `memory-qdrant` | Qdrant-specific orchestration |
| Mode degradation | `memory-cbr-inmem` | Auto-degrade for missing embedding |
| Reranking decorator | `memory-cbr-crossencoder` (new) | Isolates inference-tasks dep, classpath-activated |

## What This Does NOT Include

- Sparse embeddings (SPLADE) for CBR — tracked in #122
- Server-side Qdrant fusion — feature scoring is client-side, server-side
  fusion not applicable
- Changes to `CaseRetriever` or the RAG path — this is CBR-only
- BM25 leg for CBR — tracked in #123
- `RrfFusion` → `ScoreFusion` consolidation (RAG callers) — tracked in #124
