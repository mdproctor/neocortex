# CBR Plan Adaptation SPI — Design Spec

**Issue:** casehubio/neocortex#85
**Date:** 2026-07-13
**Status:** Approved

## Problem

The CBR cycle has four phases: Retain, Retrieve, Reuse, Revise. Neocortex implements Retain (`CbrCaseRetainObserver`), Retrieve (`CbrCaseMemoryStore` + decorator chain), and Revise (`recordOutcome` + EMA confidence). **Reuse is missing.** Retrieved plans pass unchanged to routing strategies.

Today, `CbrAgentRoutingStrategy.analyseExperiences()` does primitive inline adaptation — filtering steps by current capability, checking worker eligibility, computing weighted success rates. This logic is scattered, incomplete, and closed to extension. Different consumers (engine routing, clinical AE escalation) need different adaptation strategies.

## Solution

A `PlanAdapter` SPI in `memory-api` that transforms a retrieved `PlanCbrCase` into an `AdaptedPlan` for the current case context. The SPI operates on a single retrieved plan per invocation. Cross-plan structural analysis is a separate concern (tracked in other issues).

### Position in the data flow

```
caller → retrieveSimilar()
           ↓ (passes through decorator chain: Tracking @50 → OutcomeWeighting @65 → Reranking @75 → Base)
         List<ScoredCbrCase<PlanCbrCase>>
           ↓
         PlanAdapter.adapt(each) ← NEW
           ↓
         map AdaptedPlan → RetrievedExperience
           ↓
         routing strategies
```

The adapter sits between retrieval (storage-layer concern) and consumption (routing-layer concern). It is NOT a `@Decorator` on `CbrCaseMemoryStore` — it's a separate SPI called by the consumer between retrieval and consumption. Reasons:
1. The decorator chain doesn't know the case type (would require downcasting)
2. Adaptation is a domain operation, not a storage operation
3. The adapter needs current case features, which aren't in the `retrieveSimilar()` signature

### Responsibility separation

- **Adapter**: transforms plan structure — substitute workers, adjust priorities, add/remove steps
- **Routing strategy**: selects a worker from the adapted steps for the current capability

## SPI Contract

### PlanAdapter

```java
package io.casehub.neocortex.memory.cbr;

public interface PlanAdapter {
    AdaptedPlan adapt(ScoredCbrCase<PlanCbrCase> retrieved,
                      Map<String, FeatureValue> currentFeatures);
}
```

Not `@FunctionalInterface` — unlike `OutcomeWeightingFunction` (which is genuinely a math function `(double, double) -> double`), `PlanAdapter` is a domain SPI that may acquire lifecycle methods (e.g., `supports(CbrCase)`) as the platform evolves. Matches the convention of other CBR SPIs (`CbrCaseMemoryStore`, `CbrRetrievalTracker`, `ExplanationRenderer`) — none of which are `@FunctionalInterface`.

The context parameter is the current case's feature map — the same data that drove retrieval. Engine-specific context (available workers, trust scores, capability registry) is injected by the implementation via CDI, not passed through the SPI method. This keeps the SPI in Tier 1 (zero engine deps).

### AdaptedPlan

```java
public record AdaptedPlan(
    List<AdaptedStep> steps
) {
    public AdaptedPlan {
        Objects.requireNonNull(steps, "steps");
        steps = List.copyOf(steps);
    }
}
```

### AdaptedStep

```java
public record AdaptedStep(
    String bindingName,
    String capabilityName,
    String workerName,
    String stepOutcome,
    int priority,
    Map<String, Object> parameters,
    AdaptationAction action,
    String reason
) {
    public AdaptedStep {
        Objects.requireNonNull(bindingName, "bindingName");
        if (priority < 0) throw new IllegalArgumentException("priority must be >= 0");
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        Objects.requireNonNull(action, "action");
        // capabilityName nullable — ADDED steps where the exact capability
        //   is not yet resolved (adapter says "add a step here" but binding
        //   to a concrete capability happens at routing time)
        // workerName nullable — REMOVED steps (no worker to assign) and
        //   ADDED steps (worker selection is the routing strategy's job)
        // stepOutcome nullable — null for ADDED steps (no historical execution).
        //   For RETAINED/BOOSTED/SUPPRESSED/SUBSTITUTED: carries the original
        //   PlanTrace.stepOutcome (a RoutingOutcome name string). Required by
        //   CbrAgentRoutingStrategy.analyseExperiences() for weighted success rates.
        // reason nullable (RETAINED steps)
    }
}
```

### AdaptationAction

```java
public enum AdaptationAction {
    RETAINED,       // kept unchanged from original
    SUBSTITUTED,    // worker or capability changed
    BOOSTED,        // priority increased (lower number = higher priority)
    SUPPRESSED,     // priority decreased
    ADDED,          // new step not in original plan
    REMOVED         // original step dropped
}
```

### AdaptationTrace

Audit record for a single plan adaptation. Captures what was adapted and the
context that drove the adaptation, for compliance and debugging.

```java
public record AdaptationTrace(
    String traceId,
    String retrievalTraceId,
    String sourceCaseId,
    double sourceScore,
    List<AdaptedStep> steps,
    Map<String, FeatureValue> currentFeatures,
    Instant timestamp
) {
    public AdaptationTrace {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(steps, "steps");
        steps = List.copyOf(steps);
        Objects.requireNonNull(currentFeatures, "currentFeatures");
        currentFeatures = Map.copyOf(currentFeatures);
        Objects.requireNonNull(timestamp, "timestamp");
        // sourceCaseId nullable (when caseId unavailable)
        // retrievalTraceId nullable — links to the CbrRetrievalTrace that
        //   produced the source case, establishing full provenance from
        //   retrieval through adaptation. Null when adaptation is triggered
        //   outside the retrieval flow or when tracking is disabled.
    }
}
```

### CbrAdaptationRecorded

CDI event fired after adaptation, for observability:

```java
public record CbrAdaptationRecorded(AdaptationTrace trace) {
    public CbrAdaptationRecorded {
        Objects.requireNonNull(trace, "trace");
    }
}
```

No dedicated `AdaptationTracker` SPI — the CDI event is sufficient. If persistent tracking becomes needed, it follows the `memory-cbr-tracking` pattern.

### TrackingPlanAdapter (decorator)

Fires `CbrAdaptationRecorded` — same pattern as `TrackingCbrCaseMemoryStore` fires `CbrRetrievalRecorded`.

```java
@Decorator
@Priority(50)
@IfBuildProperty(name = "casehub.cbr.adaptation-tracking.enabled", stringValue = "true")
public class TrackingPlanAdapter implements PlanAdapter {

    private final PlanAdapter delegate;
    private final Consumer<CbrAdaptationRecorded> eventSink;

    @Inject
    TrackingPlanAdapter(@Delegate @Any PlanAdapter delegate,
                        Event<CbrAdaptationRecorded> recordedEvent) {
        this(delegate, recordedEvent::fire);
    }

    TrackingPlanAdapter(PlanAdapter delegate,
                        Consumer<CbrAdaptationRecorded> eventSink) {
        this.delegate = delegate;
        this.eventSink = eventSink;
    }

    @Override
    public AdaptedPlan adapt(ScoredCbrCase<PlanCbrCase> retrieved,
                             Map<String, FeatureValue> currentFeatures) {
        AdaptedPlan result = delegate.adapt(retrieved, currentFeatures);
        try {
            var trace = new AdaptationTrace(
                UUID.randomUUID().toString(),
                null,  // retrievalTraceId — set by engine integration if available
                retrieved.caseId(),
                retrieved.score(),
                result.steps(),
                currentFeatures,
                Instant.now()
            );
            eventSink.accept(new CbrAdaptationRecorded(trace));
        } catch (Exception e) {
            LOG.warn("CBR adaptation tracking failed — returning result unchanged", e);
        }
        return result;
    }
}
```

Responsibilities:
1. **traceId generation** — UUID, generated by the decorator
2. **timestamp capture** — `Instant.now()` after adaptation completes
3. **trace assembly** — builds `AdaptationTrace` from the `ScoredCbrCase` input, `currentFeatures` input, and `AdaptedPlan` output
4. **event firing** — `CbrAdaptationRecorded` via CDI `Event.fire()`
5. **failure isolation** — tracking failure never breaks adaptation (warn + return result unchanged)

Opt-in via `@IfBuildProperty`, matching the retrieval tracking pattern. Trace creation happens even when the adapter is a no-op (consistent with retrieval tracking which records all retrievals, not just interesting ones).

The `retrievalTraceId` field is null at this layer because the decorator has no access to the retrieval trace. Engine integration (casehubio/engine#727) can set it by wrapping the adapter call with a subclass or by constructing a richer trace after the fact.

## Default Implementation

```java
@DefaultBean
@ApplicationScoped
public class NoOpPlanAdapter implements PlanAdapter {
    @Override
    public AdaptedPlan adapt(ScoredCbrCase<PlanCbrCase> retrieved,
                             Map<String, FeatureValue> currentFeatures) {
        return new AdaptedPlan(
            retrieved.cbrCase().planTrace().stream()
                .map(t -> new AdaptedStep(
                    t.bindingName(), t.capabilityName(), t.workerName(),
                    t.stepOutcome(), t.priority(), t.parameters(),
                    AdaptationAction.RETAINED, null))
                .toList()
        );
    }
}
```

Zero behavioral change by default. Existing consumers work without adaptation until they provide an implementation.

## Plan Quality Scoring

The issue mentions "this plan succeeded 4/5 times for similar cases" as a retrieval ranking signal. This is already implemented:

- `CbrOutcome.adjustConfidence()` — EMA tracks per-case quality
- `OutcomeWeightingCbrCaseMemoryStore` `@Decorator @Priority(65)` — modulates retrieval scores by confidence

A plan with 4/5 success rate has high confidence → higher retrieval rank. No additional work needed in this issue.

Per-step quality across multiple plans (cross-plan structural analysis) is tracked in #148.

## Module Placement

| Type | Module | Package |
|------|--------|---------|
| `PlanAdapter` | `memory-api` | `io.casehub.neocortex.memory.cbr` |
| `AdaptedPlan` | `memory-api` | `io.casehub.neocortex.memory.cbr` |
| `AdaptedStep` | `memory-api` | `io.casehub.neocortex.memory.cbr` |
| `AdaptationAction` | `memory-api` | `io.casehub.neocortex.memory.cbr` |
| `AdaptationTrace` | `memory-api` | `io.casehub.neocortex.memory.cbr` |
| `CbrAdaptationRecorded` | `memory-api` | `io.casehub.neocortex.memory.cbr` |
| `NoOpPlanAdapter` | `memory` | `io.casehub.neocortex.memory.cbr.runtime` |
| `TrackingPlanAdapter` | `memory-cbr-tracking` | `io.casehub.neocortex.memory.cbr.tracking` |
| Contract tests | `memory-testing` | `io.casehub.neocortex.memory.cbr.testing` |

## Testing Strategy

### Contract tests in memory-testing

`PlanAdapterContractTest` — abstract base class that implementations extend:

1. **retained_steps_when_no_adaptation_needed** — all steps RETAINED with null reason
2. **adapted_plan_preserves_step_order** — step ordering matches original
3. **adapted_plan_immutable** — steps list is unmodifiable
4. **null_retrieved_case_rejected** — NPE on null input
5. **null_features_rejected** — NPE on null features
6. **empty_plan_trace_produces_empty_steps** — edge case

### NoOpPlanAdapter unit tests in memory/

1. **noOp_retains_all_steps** — all steps RETAINED, null reasons
2. **noOp_preserves_step_fields** — bindingName, capabilityName, workerName, stepOutcome, priority, parameters all round-trip
3. **noOp_empty_trace** — empty plan trace → empty steps

### TrackingPlanAdapter tests in memory-cbr-tracking

1. **tracking_fires_event_after_adaptation** — `CbrAdaptationRecorded` event fired with valid `AdaptationTrace`
2. **tracking_trace_contains_correct_fields** — traceId non-null, sourceCaseId from input, sourceScore from input, steps from result, currentFeatures from input, timestamp non-null
3. **tracking_failure_does_not_break_adaptation** — event sink throws → adaptation result still returned (matching retrieval pattern: "tracker failure never breaks retrieval")
4. **tracking_fires_for_noop_adapter** — trace recorded even when adapter makes no changes (all RETAINED)

### Value type tests in memory-api

1. **AdaptedStep validation** — null bindingName rejected, negative priority rejected, null action rejected, null workerName allowed (REMOVED and ADDED), null stepOutcome allowed (ADDED), null reason allowed (RETAINED)
2. **AdaptedPlan immutability** — steps list is defensively copied
3. **AdaptationTrace immutability** — steps and features defensively copied
4. **AdaptationTrace_retrievalTraceId_nullable** — null retrievalTraceId accepted
5. **AdaptationAction coverage** — all enum values accessible

## Configuration

| Property | Default | Module |
|----------|---------|--------|
| `casehub.cbr.adaptation-tracking.enabled` | `false` | memory-cbr-tracking |

Matches the retrieval tracking convention (`casehub.cbr.tracking.enabled`). When `false`, `TrackingPlanAdapter` is not activated and no `CbrAdaptationRecorded` events are fired.

## Downstream Integration

### Engine (casehubio/engine#727)

`CbrRetrievalService` wires `PlanAdapter` between retrieval and mapping:

```
retrieveSimilar() → planAdapter.adapt(each) → action-aware mapping → RetrievedExperience → routing strategies
```

Engine provides a real `PlanAdapter` implementation that injects `CapabilityRegistry`, `TrustScoreSource`, etc.

#### Action-aware mapping to ExperiencePlanStep

`AdaptedStep` carries the `action` field and the original `stepOutcome`. `ExperiencePlanStep` (engine-api) has neither. The mapping from `AdaptedStep` → `ExperiencePlanStep` must interpret `stepOutcome` through the `action` lens:

| AdaptationAction | workerName | stepOutcome mapping | Rationale |
|------------------|-----------|-------------------|-----------|
| RETAINED | original | original | Unchanged — historical outcome applies directly |
| BOOSTED | original | original | Priority changed, same worker — outcome still valid |
| SUPPRESSED | original | original | Priority changed, same worker — outcome still valid |
| SUBSTITUTED | new worker | null | Original outcome was for the replaced worker — no evidence for the new one |
| ADDED | null or new | null | No historical execution exists |
| REMOVED | — | excluded | Step dropped from the adapted plan — not mapped |

`CbrAgentRoutingStrategy.analyseExperiences()` currently calls `RoutingOutcome.valueOf(step.stepOutcome())` which catches `IllegalArgumentException` but not `NullPointerException`. Engine#727 must guard against null `stepOutcome` — treating it as "no evidence" (weight 0.0), consistent with the existing unknown-value handling.

### Clinical (casehubio/clinical#118)

Clinical provides its own `PlanAdapter` for AE escalation plans — different adaptation logic (regulatory steps, escalation rules).

## Out of Scope

- Engine-side `PlanAdapter` implementation (casehubio/engine#727)
- Cross-plan structural analysis / ensemble adaptation (#148)
- Persistent adaptation tracking storage (follow-on if needed)
- `ReactivePlanAdapter` — add when a reactive consumer needs it (same pattern as `ReactiveCbrCaseMemoryStore`)
