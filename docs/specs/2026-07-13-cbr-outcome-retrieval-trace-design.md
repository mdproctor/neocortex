# CBR Outcome-Weighted Retrieval & Retrieval Traceability

**Issue:** casehubio/neocortex#84 — CBR Phase 4
**Date:** 2026-07-13
**Status:** Design approved

## Context

`recordOutcome` and EMA confidence adjustment landed in #140. Every CBR case now
carries a `confidence` field updated by outcome feedback — but `retrieveSimilar`
ignores it. Confidence is dead data. Additionally, no retrieval decision is
auditable: there is no record of what was queried, what was returned, or how
scores were computed. For regulated domains (clinical, AML), retrieval
traceability is a compliance requirement.

**Garden context:**
- GE-20260612-bd3b4d: trust-scored routing is degenerate CBR (Retain+Reuse only).
  This issue activates Revise (outcome weighting) and adds observability.
- GE-20260706-56a75c: `WorkerOutcomeResolvedEvent` fires only for non-success
  outcomes. Consumers wiring outcome recording must use
  `WorkflowExecutionCompleted` instead.

## Scope

**In scope (remaining after #140):**
1. Outcome-weighted retrieval — confidence modulates similarity scores
2. Retrieval traceability — tracker SPI + SQLite persistence + CDI event
3. ExplanationRenderer SPI — human-readable rendering of retrieval traces

**Already delivered by #140:**
- `recordOutcome(caseId, tenantId, CbrOutcome)` on both SPIs
- `CbrOutcome` record with EMA `adjustConfidence`
- All 6 implementations, 9 contract tests

**Prerequisite API change:**
- Add `String caseId` to `ScoredCbrCase` — the record currently has no case
  identifier. Without it, `TracedCase.caseId` can't be populated, and callers
  receiving retrieval results have no way to reference matched cases for
  outcome correlation, audit trails, or display. All store implementations
  (InMemory, Qdrant) already have the caseId internally — they just don't
  surface it. Pre-release; breaking change costs nothing.

**Out of scope (follow-on issues):**
- Configurable learning rate per case type (#143) — deferred from #140 → #84;
  orthogonal to outcome-weighted retrieval (concerns `recordOutcome` behavior,
  not retrieval scoring)
- ARC42STORIES.MD update for CBR Phase 4 (#144) — done after implementation

## Architecture

### New Types in memory-api

All in `io.casehub.neocortex.memory.cbr`. Zero external dependencies.

#### CbrRetrievalTrace (record)

```java
public record CbrRetrievalTrace(
    String traceId,
    CbrQuery query,
    List<TracedCase> results,
    Instant timestamp
) {
    public record TracedCase(
        String caseId,
        double score,
        boolean reranked,
        Map<String, Double> featureSimilarities,
        Double confidence
    ) {}
}
```

`TracedCase` snapshots the case's state at retrieval time. If confidence changes
later via `recordOutcome`, the trace preserves what the caller actually saw.

#### CbrRetrievalRecorded (CDI event)

```java
public record CbrRetrievalRecorded(
    String traceId,
    CbrQuery query,
    List<CbrRetrievalTrace.TracedCase> results
) {}
```

Lighter than the full trace. Consumers needing the timestamp or full trace
query the tracker.

#### CbrRetrievalTracker (SPI)

```java
public interface CbrRetrievalTracker {
    String record(CbrQuery query, List<ScoredCbrCase<?>> results);
    List<CbrRetrievalTrace> findTraces(String caseType, String tenantId,
                                        MemoryDomain domain,
                                        Instant since, Instant until);
    int purgeOlderThan(Instant cutoff);
}
```

Three methods: record, query, purge. `findTraces` filters by `domain` because
the same `caseType` can exist in different memory domains — a compliance audit
for a specific domain (e.g., clinical) must not return traces from other domains.
No feedback method — outcome feedback is already handled by
`CbrCaseMemoryStore.recordOutcome` (the Revise step from #140).

#### ReactiveCbrRetrievalTracker (SPI)

```java
public interface ReactiveCbrRetrievalTracker {
    Uni<String> record(CbrQuery query, List<ScoredCbrCase<?>> results);
    Uni<List<CbrRetrievalTrace>> findTraces(String caseType, String tenantId,
                                             MemoryDomain domain,
                                             Instant since, Instant until);
    Uni<Integer> purgeOlderThan(Instant cutoff);
}
```

Mutiny `Uni` variants of all three methods, matching the platform's reactive
parity convention (`RetrievalTracker` / `ReactiveRetrievalTracker` in RAG).
`BlockingToReactiveCbrRetrievalTracker` bridge in memory-cbr-tracking wraps
the blocking tracker with `runSubscriptionOn(workerPool)`, same pattern as
`BlockingToReactiveRetrievalTracker` in rag-tracking.

#### OutcomeWeightingFunction (SPI)

```java
@FunctionalInterface
public interface OutcomeWeightingFunction {
    double apply(double similarity, double confidence);
}
```

Default: linear interpolation with configurable influence parameter.
Consumers needing domain-specific weighting provide their own bean.

#### ExplanationRenderer (SPI)

```java
public interface ExplanationRenderer {
    String render(CbrRetrievalTrace trace);
}
```

Transforms a trace into a human-readable explanation for display and
logging. Generic default in memory module; domain-specific implementations
override via CDI priority. Compliance consumers requiring structured,
machine-parseable output should use `CbrRetrievalTrace` directly — the
renderer is a presentation layer, not the compliance interface.

### Decorator Priority Chain

```
caller → Tracking(50) → OutcomeWeighting(65) → Reranking(75) → Base Store
```

In CDI, lower `@Priority` values are called first (outermost). This matches
the established RAG decorator convention where `TrackingCaseRetriever` sits
at `@Priority(50)`.

Each independently activatable via `@IfBuildProperty`. All have blocking +
reactive parity.

**Why this ordering:**
- Tracking (50, outermost) records the fully-processed result — what the
  caller actually receives. Post-weighting, post-reranking. Consistent with
  RAG tracking at `@Priority(50)`.
- Outcome weighting (65) runs after reranking so it doesn't interfere with
  the reranker's pool selection. The reranker gets the full similarity-scored
  pool, produces its best ranking, then confidence modulates final scores.
- Reranking (75, existing) — unchanged, innermost of the three.

### Outcome Weighting Decorator (memory module)

`io.casehub.neocortex.memory.cbr.runtime.OutcomeWeightingCbrCaseMemoryStore`

```
@Decorator @Priority(65)
@IfBuildProperty(name = "casehub.cbr.outcome-weighting.enabled", stringValue = "true")
```

Behavior:
1. Delegates `retrieveSimilar` to inner chain
2. For each `ScoredCbrCase`: reads `cbrCase().confidence()`, applies
   `OutcomeWeightingFunction`
3. Null confidence → treat as 1.0 (new cases, no penalty)
4. Re-sorts by weighted score, preserves `featureSimilarities` and `reranked`
5. All other methods pass through

Default `OutcomeWeightingFunction` (`@DefaultBean`):
- Linear interpolation: `score * (1 - α + α * confidence)`
- α from config: `casehub.cbr.outcome-weighting.influence` (default 0.3)
- At α=0: pure similarity (backward compatible). At α=1: full multiplication.

Reactive variant: `ReactiveOutcomeWeightingCbrCaseMemoryStore`

### Tracking Decorator (memory-cbr-tracking module — new)

`io.casehub.neocortex.memory.cbr.tracking.TrackingCbrCaseMemoryStore`

```
@Decorator @Priority(50)
@IfBuildProperty(name = "casehub.cbr.tracking.enabled", stringValue = "true")
```

Behavior:
1. Delegates `retrieveSimilar` to inner chain
2. Calls `CbrRetrievalTracker.record(query, results)` → gets traceId
3. Fires `CbrRetrievalRecorded` CDI event via `Event.fire()` (synchronous)
4. Returns results unchanged (tracking is observation, not mutation)
5. Tracker failure never breaks retrieval (warn + return results)

Reactive variant: `ReactiveTrackingCbrCaseMemoryStore` — uses `Event.fireAsync()`
for non-blocking event delivery on the Vert.x event loop.

**Double-recording guard.** When `BlockingToReactiveCbrBridge` is active, a
reactive consumer's call traverses both decorator chains — the reactive tracking
decorator wraps the bridge, which delegates to the decorated blocking chain
including the blocking tracking decorator. Without a guard, both decorators
record the same retrieval.

The CBR guard uses bridge-detection rather than RAG's metadata-stamp approach
(CBR's `ScoredCbrCase` record has no metadata map, unlike RAG's `RetrievedChunk`).
A marker interface `BridgedCbrStore` in memory-api indicates that the delegate
is a blocking-to-reactive bridge. `BlockingToReactiveCbrBridge` in the memory
module implements it. The reactive tracking decorator checks
`delegate instanceof BridgedCbrStore` at construction time — if the bridge is
present, the reactive decorator becomes a pass-through. This keeps the
dependency on memory-api only (no concrete-class coupling from
memory-cbr-tracking to the memory module).

When the bridge is active, the blocking decorator handles all tracking since
every call (blocking or reactive via bridge) traverses the blocking chain.
When a native reactive `ReactiveCbrCaseMemoryStore` implementation displaces
the bridge, each path has exactly one tracking decorator — no guard needed,
no skipping.

Correct in all deployment modes:
- Bridge active → blocking decorator records, reactive decorator passes through
- Native reactive → each path has one decorator, each records independently

### SQLite Tracker (memory-cbr-tracking module)

`SqliteCbrRetrievalTracker` — same pattern as `SqliteRetrievalTracker` in
rag-tracking:
- SQLite + HikariCP (WAL mode)
- Flyway V1 schema: traces table (traceId, caseType, tenantId, domain,
  queryJson, resultsJson, timestamp)
- Retention: `@Scheduled(every = "24h")` method on `SqliteCbrRetrievalTracker`
  calls `purgeOlderThan`, configured via
  `casehub.cbr.tracking.retention.days` (default 90). Uses `quarkus-scheduler`
  for graceful shutdown, dev-mode restart safety, and health check visibility.

**Serialization strategy.** `queryJson` stores `CbrQuery` with features
serialized via `FeatureValue.toRawValue()` → Jackson → TEXT. Deserialization
uses `FeatureValue.of(Object)` to reconstruct from the raw value round-trip.
`resultsJson` stores `List<TracedCase>` — all fields are primitives
(`String`, `double`, `boolean`, `Map<String, Double>`, `Double`), no sealed
hierarchy or generics involved. The `record()` method extracts `TracedCase`
projections from `ScoredCbrCase<?>` before serializing, avoiding the generic
type erasure problem entirely.

Dependencies: sqlite-jdbc, hikaricp, flyway-core, quarkus-scheduler.

### ExplanationRenderer Default (memory module)

`io.casehub.neocortex.memory.cbr.runtime.DefaultExplanationRenderer`

```
@DefaultBean @ApplicationScoped
```

Produces structural explanations from trace data:
```
Retrieved 3 cases (mode: HYBRID, caseType: adverse-event).
Top match: caseId=ae-2024-001, score=0.92, confidence=0.85.
Feature breakdown: grade=1.00, eventType=0.95, trialPhase=0.80.
```

No domain knowledge — just field names and scores.

## Module Dependency Summary

```
memory-api          ← new SPIs + value types (zero new deps)
                      CbrRetrievalTracker, ReactiveCbrRetrievalTracker,
                      CbrRetrievalTrace, CbrRetrievalRecorded,
                      OutcomeWeightingFunction, ExplanationRenderer,
                      BridgedCbrStore (marker interface)
                      ScoredCbrCase — add String caseId field
memory              ← outcome weighting decorator + default function
                      + DefaultExplanationRenderer (no new deps)
memory-cbr-tracking ← tracking decorator + SQLite tracker
                      + BlockingToReactiveCbrRetrievalTracker bridge
                      (new module: sqlite-jdbc, hikaricp, flyway-core,
                       quarkus-scheduler)
memory-testing      ← InMemoryCbrRetrievalTracker + contract test base
```

## Testing Strategy

### Contract Tests (memory-testing)

`CbrRetrievalTrackerContractTest` — abstract base:
- `record_returnsNonBlankTraceId`
- `record_persistsQueryAndResults`
- `findTraces_byCaseTypeAndTenant`
- `findTraces_domainIsolation`
- `findTraces_timeRangeFiltering`
- `findTraces_emptyWhenNoMatches`
- `findTraces_multipleTraces_orderedByTimestamp`
- `purgeOlderThan_removesOldTraces`
- `purgeOlderThan_preservesRecentTraces`
- `purgeOlderThan_returnsDeletedCount`

`InMemoryCbrRetrievalTracker` — `@Alternative @Priority(1)` for test use.

### Unit Tests

**Outcome weighting:**
- `OutcomeWeightingCbrCaseMemoryStoreTest` — weighting math, null confidence,
  re-sorting, pass-through, custom function injection
- `DefaultOutcomeWeightingFunctionTest` — formula correctness, edge cases
  (confidence 0/1, α 0/1), score range validity

**Tracking:**
- `TrackingCbrCaseMemoryStoreTest` — tracker called correctly, CDI event fired,
  tracker failure is non-fatal, results unchanged

**Explanation:**
- `DefaultExplanationRendererTest` — structure, empty results, missing data

**Reactive parity:** mirror of each blocking test, Uni-wrapped.

**SQLite tracker:**
- `SqliteCbrRetrievalTrackerTest` extends `CbrRetrievalTrackerContractTest` —
  WAL mode, Flyway migration, retention scheduler

### Decorator Chain Integration Test

`@QuarkusTest` in memory-cbr-tracking verifying the full chain
(Tracking → OutcomeWeighting → Reranking → Base) executes in the correct
priority order and the trace captures post-weighting, post-reranking results.
Validates that `@Priority(50)` tracking is outermost and records what the
caller actually receives.

### Double-Recording Guard Test

`@QuarkusTest` with `BlockingToReactiveCbrBridge` active, verifying that a
reactive `retrieveSimilar()` call produces exactly one `CbrRetrievalTrace`
record and one `CbrRetrievalRecorded` CDI event despite both tracking
decorators being in the call chain. Also tests the native-reactive path
(bridge displaced) to confirm each path records independently.

### Outcome Weighting Integration

Tested via `OutcomeWeightingCbrCaseMemoryStoreTest` (decorator unit test, not
contract test — the contract test exercises the base store, not the decorator
chain). Tests use a mock delegate and verify weighting behavior in isolation:
- `successfulCaseRanksHigher` — equal similarity, different confidence → order
- `nullConfidence_treatedAsOne` — no penalty for new cases
- `allConfidenceOne_orderUnchanged` — no outcome data → pure similarity
- `influenceZero_noEffect` — α=0 backward compatibility

## Configuration

| Property | Default | Module |
|----------|---------|--------|
| `casehub.cbr.outcome-weighting.enabled` | `false` | memory |
| `casehub.cbr.outcome-weighting.influence` | `0.3` | memory |
| `casehub.cbr.tracking.enabled` | `false` | memory-cbr-tracking |
| `casehub.cbr.tracking.retention-days` | `90` | memory-cbr-tracking |
| `casehub.cbr.tracking.sqlite.path` | — | memory-cbr-tracking |
| `casehub.cbr.tracking.sqlite.pool-max-size` | `5` | memory-cbr-tracking |
| `casehub.cbr.tracking.sqlite.busy-timeout-ms` | `5000` | memory-cbr-tracking |
