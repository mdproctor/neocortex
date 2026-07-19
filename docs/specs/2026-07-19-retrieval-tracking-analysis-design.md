# Retrieval Tracking Analysis Service — Design Spec

**Issue:** casehubio/neocortex#109
**Date:** 2026-07-19
**Status:** Approved
**Depends on:** #105 (retrieval tracking SPI — landed)

## Problem

The retrieval tracking SPI (#105) records raw events: which documents were
returned for which query, and optional graduated feedback on document
usefulness. But raw events aren't actionable — no consumer can look at
thousands of retrieval records and decide which documents need attention.

The gap is a computation layer that interprets the event log and surfaces
structured quality signals. Engine's `gardenUnretrieved` MCP tool already
does a primitive version of this inline (~60 lines of manual set-diffing),
but the logic is generic and belongs in neocortex.

## Design Decisions

1. **Pure computation, not an SPI.** The analysis service is a stateless
   utility class with static methods — same pattern as `CbrSimilarityScorer`
   in `memory-api`. There's one correct way to compute "retrieval frequency
   per document" from `findRecords()`. Multiple implementations would be
   premature abstraction. If storage-level aggregation (SQL GROUP BY) is
   needed later for scale, we add aggregation methods to `RetrievalTracker`
   itself — the analysis logic on top stays the same.

2. **Returns structured data, never executes actions.** neocortex is
   Foundation tier — it doesn't know what "review" means for a garden
   entry or "flag for expert review" means for a clinical corpus.
   `DocumentQualitySignal` carries a domain-agnostic classification
   (`NEVER_RETRIEVED`, `HIGH_RETRIEVAL_LOW_QUALITY`, `STALE`) that
   consumers map to domain-specific actions. This follows the trust
   scoring pattern in ledger: compute scores, let consumers set thresholds.

3. **Lives in `rag-api`, not a new module.** Both inputs (`RetrievalTracker`,
   `EmbeddingIngestor`) are already in `rag-api`. The computation has zero
   external dependencies. `memory-api` already contains pure computation
   classes (`CbrSimilarityScorer`, `DtwSimilarity`, `TrendAnalyzer`,
   `CbrFeatureValidator`) alongside its SPIs — this is the established
   pattern, not an exception.

4. **Focused static methods, not a monolithic analyzer.** Three methods
   that each compute one analysis dimension independently:
   `documentStats`, `unretrievedDocuments`, `qualitySignals`. Consumers
   call only what they need. The query→document→outcome correlation graph
   (mentioned in the issue) is deferred to #167 — it's a fundamentally
   different kind of computation (graph-building vs. aggregation) with
   no consumer yet. **Issue #109 will remain open** after this work
   lands, with #167 as the remaining deliverable.

   Issue #109 also lists "surface curation recommendations." The
   `QualitySignal` enum provides the foundation: domain-agnostic
   classifications that consumers map to domain-specific actions
   (garden: review/erase; clinical: flag for expert review). This is
   consistent with neocortex being Foundation tier — it surfaces the
   signal, consumers interpret it as a recommendation.

5. **Caller-controlled thresholds.** What counts as "low quality" or
   "stale" depends on the corpus and domain. `QualityThresholds` puts
   the heuristic knobs in the caller's hands with sensible defaults.
   The analysis service never hardcodes domain assumptions.

## Value Types (rag-api)

### DocumentStats

Per-document retrieval statistics computed from `RetrievalRecord` and
`RetrievalFeedback` data within a time window:

| Field | Type | Notes |
|-------|------|-------|
| `sourceDocumentId` | `String` | The document being analysed |
| `retrievalCount` | `int` | Total retrieval appearances in the window |
| `firstRetrieved` | `Instant` | Earliest retrieval timestamp |
| `lastRetrieved` | `Instant` | Most recent retrieval timestamp |
| `averageRetrievalScore` | `double` | Mean retrieval score across all appearances |
| `feedbackDistribution` | `Map<RetrievalOutcome, Integer>` | Outcome → count; empty if no feedback |

`feedbackDistribution` uses the existing `RetrievalOutcome` enum. An empty
map means no feedback was submitted — itself a signal (document returned
but never engaged with).

### DocumentQualitySignal

A document flagged by a quality heuristic:

| Field | Type | Notes |
|-------|------|-------|
| `sourceDocumentId` | `String` | The flagged document |
| `stats` | `DocumentStats` | Underlying statistics (null for NEVER_RETRIEVED — no retrieval data exists) |
| `signal` | `QualitySignal` | Why it was flagged |

### QualitySignal

Domain-agnostic quality classification:

| Value | Meaning |
|-------|---------|
| `NEVER_RETRIEVED` | Not in any retrieval record within the window |
| `HIGH_RETRIEVAL_LOW_QUALITY` | Frequently retrieved but mostly NOT_RELEVANT / PARTIALLY_RELEVANT feedback |
| `STALE` | Retrieved historically but not within the stale window |

Consumers map these to domain actions. Garden: review/erase. Clinical:
flag for expert review. Legal: archive check.

**Interaction with data retention:** `RetrievalTracker.purgeOlderThan()`
and `RetentionScheduler` (default: 90 days, per #110) purge old
retrieval records. This affects signal semantics:
- `NEVER_RETRIEVED` means "no retrieval record exists in the store" —
  the document may have been retrieved before the retention window and
  subsequently purged. Consumers that need to distinguish "truly never
  retrieved" from "retrieved but purged" must maintain their own
  out-of-band record or accept the ambiguity.
- `STALE` only functions within the retention window. A `staleWindow`
  exceeding the effective retention period produces no STALE results
  because the records that would prove staleness have been purged.
  Callers should set `staleWindow` ≤ retention period.

### QualityThresholds

Caller-controlled heuristic parameters:

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `minRetrievalsForQualityCheck` | `int` | 3 | Document must have at least this many retrievals before checking feedback quality |
| `minFeedbackForQualityCheck` | `int` | 3 | Document must have at least this many feedback entries before the low-quality ratio is meaningful |
| `lowQualityRatio` | `double` | 0.7 | Fraction of NOT_RELEVANT + PARTIALLY_RELEVANT feedback to trigger HIGH_RETRIEVAL_LOW_QUALITY |
| `staleWindow` | `Duration` | 90 days | No retrieval within this window → STALE |

`QualityThresholds.defaults()` provides the default values.

Validation: `minRetrievalsForQualityCheck` must be ≥ 1,
`minFeedbackForQualityCheck` must be ≥ 1, `lowQualityRatio` must be in
[0, 1], `staleWindow` must not be null.

## API (rag-api)

`RetrievalAnalyzer` — pure static utility class in
`io.casehub.neocortex.rag`:

```java
public final class RetrievalAnalyzer {
    private RetrievalAnalyzer() {}

    public static Map<String, DocumentStats> documentStats(
            RetrievalTracker tracker,
            CorpusRef corpus,
            Instant since, Instant until);

    public static Set<String> unretrievedDocuments(
            RetrievalTracker tracker,
            EmbeddingIngestor ingestor,
            CorpusRef corpus,
            Instant since, Instant until);

    public static List<DocumentQualitySignal> qualitySignals(
            RetrievalTracker tracker,
            EmbeddingIngestor ingestor,
            CorpusRef corpus,
            Instant since, Instant until,
            QualityThresholds thresholds);
}
```

### Method Behaviour

**`documentStats`** — The `since`/`until` parameters define the
*retrieval window*: which retrieval events are included. Feedback
follows via the retrieval link, not by its own timestamp.

Algorithm:
1. Calls `tracker.findRecords(corpus, since, until)` to select
   retrieval events within the window.
2. Calls `tracker.findFeedback(corpus, since, Instant.MAX)` — uses
   `since` as the lower bound (feedback is always submitted after the
   retrieval) but removes the upper bound so late-submitted feedback
   for retrievals in the window is captured. `findFeedback` filters on
   `retrieval_feedback.timestamp` (the SPI spec's documented semantics),
   so without the widened upper bound, feedback submitted after `until`
   for retrievals before `until` would be silently dropped.
3. Post-filters feedback: only entries whose `retrievalId` matches a
   record from step 1 contribute to `feedbackDistribution`. Feedback
   for retrievals outside the window is discarded.
4. Groups by `sourceDocumentId`: computes count, min/max timestamps,
   average retrieval score. Each feedback entry's `outcome` increments
   the distribution for its `sourceDocumentId`.

Returns only documents that appear in retrieval records (not the full
corpus inventory).

**`unretrievedDocuments`** — Calls `tracker.findRetrievedDocumentIds()`
and `ingestor.listDocuments()`. Returns the set difference:
documents in the ingestor inventory with zero retrieval records in
the window. Cheap — no record-level iteration.

**`qualitySignals`** — Calls `documentStats()` internally, then calls
`unretrievedDocuments()`. Applies thresholds from `QualityThresholds`:

**HIGH_RETRIEVAL_LOW_QUALITY ratio computation:** The denominator is
`sum(feedbackDistribution.values())` — the total number of feedback
entries for the document. The numerator is `NOT_RELEVANT count +
PARTIALLY_RELEVANT count`. The quality check is skipped when:
- The denominator is zero (no feedback submitted) — absence of feedback
  is not evidence of low quality.
- The denominator is below `minFeedbackForQualityCheck` — sparse
  feedback produces unreliable ratios (e.g., 1 negative review out of
  100 retrievals would produce a 100% ratio from a sample of 1).

Returns a flat list sorted by signal severity: NEVER_RETRIEVED first,
then HIGH_RETRIEVAL_LOW_QUALITY, then STALE. Each entry carries its
`DocumentStats` so the consumer can inspect underlying data
(null stats for NEVER_RETRIEVED — no retrieval data exists by definition).
When a document could match multiple signals, only the highest severity
is emitted.

**Severity ordering rationale:**
- `NEVER_RETRIEVED` is highest: zero-value documents are the clearest
  curation candidates — no retrieval data exists, so the action (remove
  or review) is unambiguous.
- `HIGH_RETRIEVAL_LOW_QUALITY` is next: actively retrieved but poorly
  rated. Requires investigation (embedding quality? outdated content?)
  before action — more nuanced than never-retrieved.
- `STALE` is lowest: formerly useful documents that may return to
  relevance (seasonal queries, periodic topics). Least urgent.

**Overlap example:** Given a 1-year analysis window and a 90-day stale
window, a document retrieved 50 times in the first 6 months with mostly
negative feedback, but not retrieved in the last 90 days, matches both
`HIGH_RETRIEVAL_LOW_QUALITY` and `STALE`. Only
`HIGH_RETRIEVAL_LOW_QUALITY` is emitted (higher severity). The consumer
can inspect `DocumentStats.lastRetrieved` to detect staleness
independently if needed.

All methods are stateless. No caching, no side effects, no CDI.

## Module Placement

All new code in `rag-api`:

| File | Type | Location |
|------|------|----------|
| `RetrievalAnalyzer` | Utility class | `rag-api/src/main/java/io/casehub/neocortex/rag/` |
| `DocumentStats` | Record | Same package |
| `DocumentQualitySignal` | Record | Same package |
| `QualitySignal` | Enum | Same package |
| `QualityThresholds` | Record | Same package |
| `RetrievalAnalyzerTest` | Test | `rag-api/src/test/java/io/casehub/neocortex/rag/` |

No new module. No new dependencies for `rag-api` — all inputs
(`RetrievalTracker`, `EmbeddingIngestor`, `CorpusRef`, `RetrievalRecord`,
`RetrievalFeedback`, `RetrievalOutcome`, `RetrievedDocumentRef`) are
already in `rag-api`.

## Testing Strategy

Tests in `rag-api/src/test/java/` using inline anonymous stubs of
`RetrievalTracker` and `EmbeddingIngestor`. No dependency on `rag-testing`
(which would be circular). Stubs return pre-built data for deterministic
assertions.

### Test Coverage

**`documentStats`:**
- Single document, single retrieval
- Multiple retrievals for same doc (count, timestamps, averaged score)
- Multiple documents (independent stats)
- With feedback (distribution populated)
- Mixed feedback outcomes (each outcome counted)
- No retrievals in window (empty map)
- Multiple chunks collapsed to same sourceDocumentId
- Late feedback included: retrieval in window, feedback submitted after `until` → feedback appears in distribution
- Out-of-window feedback excluded: retrieval outside window, feedback timestamp after `since` → feedback discarded by `retrievalId` post-filter
- Feedback at exactly `until`: included (upper bound widened to `Instant.MAX`)

**`unretrievedDocuments`:**
- All documents retrieved → empty set
- Some never retrieved → correct set difference
- Empty corpus → empty set
- Empty retrieval history → all documents returned

**`qualitySignals`:**
- NEVER_RETRIEVED for unretrieved docs
- HIGH_RETRIEVAL_LOW_QUALITY when ratio exceeds threshold
- HIGH_RETRIEVAL_LOW_QUALITY not flagged when below threshold
- Below minRetrievalsForQualityCheck → not flagged even with bad feedback
- Above minRetrievalsForQualityCheck with zero feedback → not flagged (denominator zero, quality check skipped)
- Above minRetrievalsForQualityCheck with feedback below minFeedbackForQualityCheck → not flagged (sparse feedback guard)
- STALE when last retrieval outside stale window
- Multiple signals possible → highest severity only
- Custom thresholds override defaults
- Result ordering: NEVER_RETRIEVED > HIGH_RETRIEVAL_LOW_QUALITY > STALE

**`QualityThresholds`:**
- `defaults()` returns expected values
- Validation: negative minRetrievals rejected, negative minFeedback rejected, ratio outside [0,1] rejected, null staleWindow rejected

## Consumer Upgrade Path (Not In Scope)

Engine's `gardenUnretrieved` MCP tool can replace its ~60 lines of inline
analysis with:

```java
// Stats for ALL documents (used for display/reporting of the full corpus)
Map<String, DocumentStats> stats = RetrievalAnalyzer.documentStats(
        tracker, corpus, since, until);

// Signals for flagged documents only (each carries its DocumentStats)
List<DocumentQualitySignal> signals = RetrievalAnalyzer.qualitySignals(
        tracker, ingestor, corpus, since, until,
        new QualityThresholds(minDays, 3, 0.7, Duration.ofDays(staleDays)));
```

Both calls are shown because the consumer needs stats for ALL documents
(the output table), not just flagged ones. `qualitySignals()` calls
`documentStats()` internally but only returns stats for documents that
match a signal. If the consumer only needs flagged document stats,
calling `qualitySignals()` alone suffices.

The MCP tool retains garden-specific logic: `minDays` filtering based on
GE date parsing, domain grouping from document ID path prefixes, and
human-readable formatted output. The generic computation moves to
neocortex.

Engine upgrade is tracked as #168, not part of #109.

## What This Enables (future)

- Query→document→outcome correlation graph (#167)
- Retrieval strategy A/B comparison via outcome distributions
- Automatic re-ranking model training from feedback signals
- Scheduled curation reports via CDI `@Scheduled` + analysis service

## Out of Scope

- Query→document→outcome correlation graph (#167)
- Engine `gardenUnretrieved` refactoring (#168)
- Reactive variants (the underlying `RetrievalTracker` methods are blocking;
  reactive callers use the existing `BlockingToReactiveRetrievalTracker`
  bridge — the analysis methods themselves are pure computation that
  completes synchronously)
- CDI integration / `@ApplicationScoped` bean (pure static methods —
  no CDI needed)
- REST endpoints for browsing analysis data
- Scheduled/periodic analysis runs
