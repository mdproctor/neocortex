# CBR Revise SPI — recordOutcome for CbrCaseMemoryStore

**Issue:** #140
**Date:** 2026-07-13
**Status:** Approved

## Problem

The CBR cycle implements Retrieve and Reuse but not Revise. Cases are stored with
`outcome` (String) and `confidence` (Double) fields, but these are write-once at
store time — never updated after the case is applied. Without Revise, CBR
recommendations are static: quality depends entirely on the initial case corpus.

casehub-desiredstate#76 added CBR outcome event emission (`io.casehub.cbr.outcome`
CloudEvents). Neocortex needs an SPI method to record those outcomes and adjust
case confidence.

## Design

### CbrOutcome (memory-api)

```java
public record CbrOutcome(
    Outcome result,
    double successRate,
    String detail,
    Instant observedAt
) {
    public enum Outcome { SUCCESS, PARTIAL, FAILURE }

    public static final double DEFAULT_LEARNING_RATE = 0.2;

    public CbrOutcome {
        Objects.requireNonNull(result);
        if (successRate < 0.0 || successRate > 1.0)
            throw new IllegalArgumentException("successRate must be in [0,1]");
        Objects.requireNonNull(observedAt);
    }

    public static CbrOutcome of(double successRate, String detail, Instant observedAt) {
        Outcome result = successRate == 1.0 ? Outcome.SUCCESS
                       : successRate == 0.0 ? Outcome.FAILURE
                       : Outcome.PARTIAL;
        return new CbrOutcome(result, successRate, detail, observedAt);
    }

    public static double adjustConfidence(Double oldConfidence, double successRate,
                                          double learningRate) {
        double old = oldConfidence != null ? oldConfidence : 1.0;
        return (1.0 - learningRate) * old + learningRate * successRate;
    }
}
```

**Outcome classification** from successRate (exact boundary thresholds, not configurable):
- `SUCCESS` — successRate = 1.0
- `FAILURE` — successRate = 0.0
- `PARTIAL` — 0.0 < successRate < 1.0

The `of()` factory uses exact IEEE 754 equality (`== 0.0`, `== 1.0`). This is correct
for integer ratio inputs (e.g. `(double) success / resolved` from `CbrOutcomeData`).
Callers with non-integer arithmetic should use the full constructor with an explicit
`Outcome` value.

The `Outcome` enum classifies the observed result. The EMA formula uses `successRate`
directly — the enum does not affect the calculation.

`detail` is nullable — free-form context (e.g. node outcome summary from CloudEvent).

### SPI Methods

**CbrCaseMemoryStore:**

```java
void recordOutcome(String caseId, String tenantId, CbrOutcome outcome);
```

**ReactiveCbrCaseMemoryStore:**

```java
Uni<Void> recordOutcome(String caseId, String tenantId, CbrOutcome outcome);
```

No default methods — all implementations must handle it explicitly.

**Signature rationale:** `caseId` and `tenantId` are the minimum keys to locate a case.
`caseType` is intentionally omitted — the CloudEvent consumer receives `CbrOutcomeData`
which carries `sourceId` (mapped to `caseId`) and `tenancyId` (mapped to `tenantId`)
but not `caseType`. Backends that partition by caseType (Qdrant) iterate registered
schemas — the same cross-collection pattern already established in `erase()` and
`eraseEntity()`.

### CbrCase.withOutcome

Abstract method on `CbrCase` for immutable record reconstruction:

```java
CbrCase withOutcome(String outcome, Double confidence);
```

Each record implements it — compile-time safety ensures new subtypes cannot silently
inherit a broken default:

```java
// FeatureVectorCbrCase
@Override
public CbrCase withOutcome(String outcome, Double confidence) {
    return new FeatureVectorCbrCase(problem(), solution(), outcome, confidence, features());
}

// PlanCbrCase
@Override
public CbrCase withOutcome(String outcome, Double confidence) {
    return new PlanCbrCase(problem(), solution(), outcome, confidence, features(), planTrace());
}

// TextualCbrCase
@Override
public CbrCase withOutcome(String outcome, Double confidence) {
    return new TextualCbrCase(problem(), solution(), outcome, confidence);
}
```

Used by the InMemory backend to replace stored cases with updated outcome/confidence.

### Confidence Adjustment

Standard exponential moving average:

```
newConfidence = (1 - learningRate) * oldConfidence + learningRate * successRate
```

- `learningRate` default: `CbrOutcome.DEFAULT_LEARNING_RATE` (0.2)
- `oldConfidence` null: treated as 1.0 (unobserved case starts at full confidence)
- Bidirectional: SUCCESS increases, FAILURE decreases, converges toward observed rate

With learningRate=0.2 and oldConfidence=0.8:
- rate=1.0 → 0.84 (gradual increase)
- rate=0.5 → 0.74 (moderate decrease)
- rate=0.0 → 0.64 (significant decrease)

### Overwrite-in-Place

Each `recordOutcome` overwrites the `outcome` field with the latest `Outcome` name and
recalculates `confidence` via EMA. No outcome history is stored — confidence encodes
history implicitly via the EMA formula. Outcome history/traceability is #84's concern.

### Implementations

**NoOpCbrCaseMemoryStore:** No-op, returns immediately.

**InMemoryCbrCaseMemoryStore:** Find StoredCase by caseId + tenantId. Skip if
`outcome.observedAt()` ≤ `stored.lastOutcomeAt` (idempotency guard). Reconstruct
CbrCase via `withOutcome()`, replace entry. `StoredCase` carries `lastOutcomeAt` —
detail and timestamp are storage-level metadata, not surfaced through `CbrCase`.

**QdrantCbrCaseMemoryStore:** Payload-only update — no re-embedding needed:
1. For each registered caseType, compute deterministic UUID via
   `CbrPointBuilder.pointId(tenantId, caseType, caseId)`
2. Direct `getAsync(collection, uuid)` — O(1) lookup, no scroll
3. If found: check `last_outcome_at` payload — skip if ≥ `outcome.observedAt()`
   (idempotency guard against duplicate CloudEvent delivery)
4. Read current `confidence` from payload, compute new confidence via
   `CbrOutcome.adjustConfidence(old, outcome.successRate(), CbrOutcome.DEFAULT_LEARNING_RATE)`
5. `setPayload` to update: `outcome`, `confidence`, `outcome_detail`, `last_outcome_at`
6. Break on first match — a caseId belongs to exactly one collection

Case not found is silently ignored — the consumer may receive outcomes for erased cases
or cases in a different backend.

**JpaCbrCaseMemoryStore:** Find entity by caseId + tenantId. Skip if
`entity.lastOutcomeAt` ≥ `outcome.observedAt()` (idempotency guard). Update
outcome/confidence/outcomeDetail/lastOutcomeAt fields, persist. Uses
`CbrOutcome.DEFAULT_LEARNING_RATE` for confidence adjustment.

**New columns on `CbrCaseEntity`:**

| Column | Type | Nullable |
|--------|------|----------|
| `outcome_detail` | TEXT | Yes |
| `last_outcome_at` | TIMESTAMP | Yes |

DDL migration adds two nullable columns to `cbr_case` — no data backfill needed.

**RerankingCbrCaseMemoryStore (+ reactive):** Pass-through to delegate.

**BlockingToReactiveCbrBridge:** Wraps blocking call in `Uni`.

### Qdrant Payload Fields

| Field | Type | Value |
|-------|------|-------|
| `outcome` | string | `"SUCCESS"` / `"PARTIAL"` / `"FAILURE"` |
| `confidence` | double | EMA-adjusted value |
| `outcome_detail` | string | Nullable detail from CbrOutcome |
| `last_outcome_at` | string | ISO-8601 timestamp of observation |

### Contract Tests (CbrCaseMemoryStoreContractTest)

| Test | What it verifies |
|------|-----------------|
| `recordOutcome_updatesOutcomeAndConfidence` | Store with confidence 0.8, SUCCESS rate=1.0 → confidence=0.84, outcome="SUCCESS" |
| `recordOutcome_partialResult` | PARTIAL rate=0.5 → proportional confidence adjustment |
| `recordOutcome_failure_decreasesConfidence` | FAILURE rate=0.0 → confidence decreases |
| `recordOutcome_multipleOutcomes_emaConverges` | 5 consecutive outcomes → confidence converges toward observed rate |
| `recordOutcome_nullInitialConfidence_treatsAsOne` | No initial confidence → baseline 1.0 |
| `recordOutcome_unknownCaseId_silentlyIgnored` | Non-existent caseId → no exception |
| `recordOutcome_preservesOtherFields` | problem/solution/features unchanged after outcome update |
| `recordOutcome_withDetail_doesNotCorruptCase` | Recording with a detail string preserves all case fields; detail verified in backend-specific tests |
| `recordOutcome_duplicateObservedAt_idempotent` | Same caseId + same observedAt applied twice → confidence unchanged after second call |

### CloudEvent Consumer

CDI bean in the `memory/` module (`casehub-neocortex-memory`) that closes the feedback
loop. New compile dependency: `casehub-desiredstate-api` (for `CbrOutcomeData` and
`CbrEventTypes`).

```java
@ApplicationScoped
public class CbrOutcomeConsumer {

    @Inject CbrCaseMemoryStore store;

    public void onEvent(@Observes @CloudEventType(CbrEventTypes.CBR_OUTCOME)
                        CloudEvent event) {
        CbrOutcomeData data = deserialize(event);
        CbrOutcome outcome = CbrOutcome.of(
            data.successRate(),
            summarize(data.nodeOutcomes()),
            data.observedAt());
        store.recordOutcome(data.sourceId(), data.tenancyId(), outcome);
    }
}
```

**Mapping:** `sourceId` → `caseId` (per the desiredstate spec's sourceId contract),
`tenancyId` → `tenantId`. The consumer constructs `CbrOutcome` from the event data
and delegates to the SPI — no transformation beyond mapping field names.

`detail` is populated by summarizing `nodeOutcomes` (e.g. "3/4 nodes succeeded,
1 FAILED: node-xyz"). This is a human-readable summary, not structured data —
structured outcome history is #84's concern.

## Scope

**In scope (#140):**
- `CbrOutcome` record + `Outcome` enum in memory-api
- `CbrOutcome.adjustConfidence()` static EMA method + `DEFAULT_LEARNING_RATE` constant
- `CbrCase.withOutcome()` abstract method + implementations on all three records
- `CbrCaseMemoryStore.recordOutcome()` + reactive parity
- All 6 implementations (NoOp, InMemory, Qdrant, JPA, reranking decorator, bridge)
- JPA migration: `outcome_detail` and `last_outcome_at` columns on `cbr_case`
- CloudEvent consumer for `io.casehub.cbr.outcome` in `memory/` module
- New dependency: `casehub-desiredstate-api` in `memory/` (for consumer event types)
- Contract tests (9 new)
- CLAUDE.md module description update

**Out of scope (follow-on issues):**
- Outcome-weighted retrieval (#84) — using recorded outcomes to rank results
- Outcome history / audit trail (#84) — traceability
- Configurable learning rate per case type (#84)

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Outcome model | Overwrite-in-place | EMA encodes history implicitly; audit trail is #84 |
| Confidence baseline | 1.0 for null | Unobserved = full confidence; first outcome adjusts |
| Case not found | Silent ignore | Consumer may receive outcomes for erased or cross-backend cases |
| Signature | caseId + tenantId (no caseType) | Consumer has no caseType; backends iterate schemas like erase() |
| Enum naming | `Outcome` (not `Result`) | Consistent with desiredstate spec; avoids java.sql collision |
| EMA location | Static on CbrOutcome | Pure function, testable in isolation, shared across backends |
| Learning rate | `DEFAULT_LEARNING_RATE = 0.2` constant | Explicit threading; configurable per case type in #84 |
| withOutcome | Abstract on CbrCase | Compile-time safety; each record knows its own fields |
| CloudEvent consumer | In scope, `memory/` module | Closes the feedback loop; desiredstate-api is a stable API dependency |
| Detail storage | Backend-level, not on CbrCase | Observation metadata ≠ case domain; avoids bloating the retrieval interface |
| Idempotency | `observedAt` guard in all backends | At-least-once delivery causes EMA drift without guard; one-line check |
