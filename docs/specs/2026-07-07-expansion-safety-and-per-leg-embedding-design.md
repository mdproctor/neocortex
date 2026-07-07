# Expansion Safety Net and Per-Leg Embedding Separation

**Issues:** #119 (original query in expanded set), #113 (per-leg embedding separation)
**Date:** 2026-07-07
**Status:** Approved

## Context

Cross-module feedback from Hortora/engine integration identified two gaps in the
query expansion and hybrid retrieval pipeline:

1. `LlmQueryExpander` and `TemplateQueryExpander` return only expanded queries —
   the original query is absent from the result set. If the expansion drifts
   semantically, retrieval quality regresses with no safety net. `StepBackQueryExpander`
   already includes the original; the other expanders do not.

2. `HybridCaseRetriever` calls `embedder.embed(query.searchText())` once and uses
   the result for all legs — dense, sparse, ColBERT, and BM25. When expansion is
   active, `searchText()` returns the hypothetical document. Dense embeddings benefit
   from this (HyDE bridges the query-document semantic gap), but sparse and ColBERT
   are harmed — they need exact vocabulary from the original query. ARC42STORIES C11
   documents this dense/sparse split as the intended design (L7: "dense uses
   searchText(), sparse uses text()"; L11: "Dense embedding uses the expanded
   searchText(); sparse embedding and reranking use the original text()"), but the
   code never implemented the split — `HybridCaseRetriever` uses a single embedding
   for all legs. This spec closes that implementation gap.

## Design

### #119 — Original Query in Expanded Set

**Enforcement point:** the `QueryExpandingCaseRetriever` decorator (not the expander
implementations). The decorator always ensures the original query is present in the
expanded list before fan-out and RRF fusion.

**Mechanism:** after calling `expander.expand(query)`, check if the original query
is present using record equality. If not, prepend it.

```java
if (!expanded.contains(query)) {
    var withOriginal = new ArrayList<RetrievalQuery>(expanded.size() + 1);
    withOriginal.add(query);
    withOriginal.addAll(expanded);
    expanded = withOriginal;
}
```

This uses `RetrievalQuery` record equality (both `text` and `expandedText` fields)
to precisely identify the unmodified original. Unlike a `expandedText() == null`
check, this correctly handles custom `QueryExpander` implementations that return
reformulated queries via `RetrievalQuery.of("reformulated")` — such queries have
`expandedText == null` but are not the original.

**SPI contract note:** `QueryExpander` implementations that return reformulated
queries (not the original) SHOULD use `withExpansion()` to distinguish them from
the original. The decorator's `contains()` check handles the case where they don't,
but `withExpansion()` makes the intent explicit and ensures `searchText()` returns
the right text for per-leg embedding routing.

**Impact per expander:**

| Expander | Current return | After decorator fix |
|----------|---------------|-------------------|
| `LlmQueryExpander` (n=1) | `[hydeQuery]` | `[original, hydeQuery]` |
| `LlmQueryExpander` (n=2) | `[hyde1, hyde2]` | `[original, hyde1, hyde2]` |
| `StepBackQueryExpander` | `[original, stepBack]` | `[original, stepBack]` (unchanged) |
| `TemplateQueryExpander` | `[expandedQuery]` | `[original, expandedQuery]` |
| `NoOpQueryExpander` | `[original]` | `[original]` (unchanged) |

Both `QueryExpandingCaseRetriever` and `ReactiveQueryExpandingCaseRetriever` get
this change. The single-query fast path (skip RRF) still applies when the list has
exactly one element — but with the prepend, that only happens when the expander
returns the original unmodified (NoOp case).

**RRF interaction with per-leg embedding (#113):** when the decorator prepends the
original query, lexical legs (sparse, BM25) produce identical or near-identical
results for the original and expanded queries (both use `text()` for these legs).
Documents that rank well on lexical legs get RRF scores from both result sets,
effectively upweighting lexical precision relative to HyDE-enhanced dense results.
This is the intended behavior — the original query prepend exists as a safety net,
and the lexical boost reflects higher confidence in lexically-matched documents.
The HyDE-enhanced dense leg adds diversity by surfacing semantically relevant
documents that lack lexical overlap. Issue #120 (expansion drift metrics) will
provide empirical data to validate this balance.

### #113 — Per-Leg Embedding Separation

Per-leg embedding is unconditional: when expansion is active (`query.expandedText()
!= null`), sparse and ColBERT legs use an embedding of the original `text()` while
the dense leg uses `searchText()`. No config flag — the split is always correct when
expansion produces non-original text, and the short-circuit when `expandedText ==
null` avoids any extra cost when expansion is inactive.

**Mechanism:** when `expandedText != null`, batch-embed both texts in a single call:

```java
MultiModalEmbedding searchTextEmbedding;
MultiModalEmbedding originalTextEmbedding;
if (query.expandedText() != null) {
    List<MultiModalEmbedding> embeddings = embedder.embedBatch(
        List.of(query.searchText(), query.text()));
    searchTextEmbedding = embeddings.get(0);
    originalTextEmbedding = embeddings.get(1);
} else {
    searchTextEmbedding = embedder.embed(query.searchText());
    originalTextEmbedding = searchTextEmbedding;
}
```

**Routing per leg:**

| Leg | Embedding source | Text used |
|-----|-----------------|-----------|
| Dense | `searchTextEmbedding.dense()` | `searchText()` (expanded) |
| Sparse (SPLADE) | `originalTextEmbedding.sparse()` | `text()` (original) |
| ColBERT rerank | `originalTextEmbedding.colbert()` | `text()` (original) |
| BM25 | N/A (text-based) | `text()` (already correct) |

**Capability checks:** `hasSparse` and ColBERT availability checks reference
`originalTextEmbedding` since these are the embeddings routed to the sparse and
ColBERT legs:

```java
boolean hasSparse = originalTextEmbedding.sparse() != null;
// ...
if (embedder.supportedModes().contains(EmbeddingMode.COLBERT)
        && originalTextEmbedding.colbert() != null
        && config.retrieval().rerankEnabled()) {
```

In practice, both embeddings come from the same embedder so capability presence is
identical — but the check should reference the embedding that will actually be used.

**Rationale for per-modality assignment:**

- **Dense** benefits from HyDE — bridges the query-document semantic gap.
- **Sparse (SPLADE)** needs exact vocabulary — hypothetical terms pollute term weights.
- **ColBERT** is a MaxSim reranker — shorter original query has better signal-to-noise
  ratio per token; MaxSim was designed to handle query-document asymmetry natively.
- **BM25** is pure lexical matching — already uses `query.text()`.

**Rationale for unconditional behavior (no config flag):** using expanded text for
sparse and ColBERT legs is not a quality tradeoff — it is actively wrong. SPLADE
produces term weights tuned for query-length text; a HyDE hypothetical document has
synthetic terms, longer text, and different vocabulary distribution. The additional
embedding cost when expansion is active is negligible: local ONNX embedding is
~5-15ms while the LLM call for HyDE is ~500-2000ms, making the embedding overhead
<3% of the expansion cost. A config flag that defaults to "broken sparse" when
expansion is enabled would be a silent-degradation trap.

**Code paths affected** (in both `HybridCaseRetriever` and `ReactiveHybridCaseRetriever`):

1. Server-side fusion (RRF/DBSF with prefetch legs) — sparse prefetch and ColBERT
   outer query switch to `originalTextEmbedding`
2. CC fusion (`executeConvexCombinationFusion`) — sparse leg switches to
   `originalTextEmbedding`; ColBERT rerank is not supported in CC path
3. Dense-only mode — no change (no sparse/ColBERT legs)

**Short-circuit:** when `query.expandedText() == null` (no expansion active),
`originalTextEmbedding == searchTextEmbedding` — single embed call, zero extra cost.

## Testing

### #119 tests

- Blocking decorator: original query always present after expand for each expander type
- Reactive decorator: same
- `StepBackQueryExpander` does not get a duplicate original
- Single-query fast path still works for NoOp
- Custom expander returning `RetrievalQuery.of("reformulated")` (no `withExpansion()`)
  still gets original prepended

### #113 tests

- No expansion active: single embed call, current behaviour preserved
- Expansion active: two texts batched via `embedBatch()`, correct text routed to each leg
- Both blocking and reactive variants
- Server-side fusion and CC fusion paths
- `hasSparse` and ColBERT checks reference `originalTextEmbedding`

### Combined #119 + #113 + CRAG integration

- Expansion decorator prepends original, each expanded query passes through CRAG
  individually, CRAG re-retrieval with higher top-K hits `HybridCaseRetriever` with
  per-leg embedding correctly applied

## ARC42STORIES

ARC42STORIES C11 L7 and L11 documented the dense/sparse split as shipped behavior.
The `RetrievalQuery` type and its `text()`/`searchText()` methods were designed to
support the split, but `HybridCaseRetriever` never implemented it — a single
`embed(query.searchText())` call was used for all legs. This spec closes the gap:
the code now matches the documented architecture. No ARC42STORIES text change
needed — the existing description becomes accurate.

## Out of Scope

- #120 (expansion drift metrics with auto-fallback) — separate issue, builds on these changes
- Changes to the `QueryExpander` SPI contract — expanders are unchanged
- Changes to `MultiModalEmbedder` SPI — no mode-specific embed methods
