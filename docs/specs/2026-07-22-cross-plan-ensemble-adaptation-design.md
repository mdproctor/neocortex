# Cross-Plan Structural Analysis / Ensemble Adaptation — Design Spec

**Issue:** casehubio/neocortex#148
**Date:** 2026-07-22
**Status:** Draft

## Problem

Single-plan adaptation (`PlanAdapter`, #85) transforms one retrieved plan at a time. When multiple similar cases are retrieved, each is adapted independently — no cross-plan analysis occurs. This misses stronger signals available from examining multiple plans together:

- **Consensus** — a step appearing in 4/5 successful plans is a stronger signal than one plan alone
- **Divergence** — plans disagreeing on which worker to use at a binding point is actionable information
- **Quality** — per-step outcome distribution across plans (mostly COMPLETED vs mostly FAULTED)
- **Synthesis** — assembling the best elements from multiple adapted plans into one ensemble plan

Clinical safety plans particularly benefit: "4 of 5 similar past cases used this step successfully" is a stronger audit answer than "the most similar case used it."

## Literature Foundation

CBR literature consistently treats multi-case reuse as a **two-stage process**, not a replacement for per-case adaptation:

- **ABARC model** (Manzano, Ontañón, Plaza — ICCBR 2011): divides reuse into *individual reuse* (each case generates a full solution independently) then *multiagent reuse* (solutions are combined via amalgam operations)
- **Workflow streams** (Müller & Bergmann — ICCBR 2014): decomposes retrieved workflows into reusable subcomponents and recombines them — per-case processing first, then cross-case combination
- **Compositional adaptation** (Sizov, Öztürk, Marsi — ICCBR 2016): "use of only one case often yields an incomplete explanation" — compositional adaptation combines parts from multiple cases to fill gaps no single case covers
- **OLCBP cycle**: separates SPA (Single Plan Adaptation) from MPA (Multi-Plan Adaptation), describing MPA as "a way to allow SPA to contain an expansion process by merging several cases"

**Key insight:** cross-case analysis works better on *adapted* plans because the adapted plans already account for context differences, making consensus/divergence signals cleaner. Analyzing raw plans pollutes signals with steps that should have been context-adapted away.

## Solution

A `PlanEnsembleAnalyzer` SPI in `memory-api` that examines multiple adapted plans together to identify structural patterns and synthesize an ensemble plan. The SPI operates **after** per-plan `PlanAdapter` adaptation — it is purely additive, never replaces or hampers single-plan adaptation.

### Position in the data flow

```
caller → retrieveSimilar()
           ↓ (decorator chain: Tracking @50 → OutcomeWeighting @65 → Reranking @75 → Base)
         List<ScoredCbrCase<PlanCbrCase>>
           ↓
         PlanAdapter.adapt(each) ← existing (Phase 5, #85)
           ↓
         List<AdaptedPlan>  ← per-plan results
           ↓                    ↓
    map → List<RetrievedExperience>    PlanEnsembleAnalyzer.analyze() ← NEW
           ↓ (context injection)        ↓ (routing strategies)
         cbrExperiences JSON          EnsemblePlan
```

**Caller orchestrates both stages.** The engine's `CbrRetrievalService` calls `PlanAdapter.adapt()` per case (as today), collects the adapted plans, then calls `PlanEnsembleAnalyzer.analyze()` with both the scored cases and the adapted plans. The two SPIs are independent and composable — consumers can use per-plan adaptation without ensemble, or ensemble without per-plan adaptation (though the latter loses adaptation quality).

**Both outputs serve different purposes:**
- `List<RetrievedExperience>` — per-case experiences for context injection (rules/AI reasoning about individual past cases)
- `EnsemblePlan` — synthesized plan for routing strategies (strongest combined signal)

### Responsibility separation

- **PlanAdapter** (existing): transforms a single plan's structure for the current context — substitute workers, adjust priorities, add/remove steps
- **PlanEnsembleAnalyzer** (new): examines multiple adapted plans for cross-plan patterns — consensus, divergence, quality — and synthesizes an ensemble plan
- **Routing strategy** (engine): selects a worker from the ensemble/adapted steps for the current capability

## SPI Contract

### PlanEnsembleAnalyzer

```java
package io.casehub.neocortex.memory.cbr;

import java.util.List;
import java.util.Map;

public interface PlanEnsembleAnalyzer {
    EnsemblePlan analyze(String caseType,
                         List<ScoredCbrCase<PlanCbrCase>> scoredCases,
                         List<AdaptedPlan> adaptedPlans,
                         Map<String, FeatureValue> currentFeatures);
}
```

Not `@FunctionalInterface` — domain SPI that may acquire lifecycle methods, matching `PlanAdapter` and other CBR SPIs.

**Parallel list contract:** `scoredCases.get(i)` corresponds to `adaptedPlans.get(i)`. Same order, same length. Implementations must reject mismatched sizes with `IllegalArgumentException`.

**Parameters:**
- `caseType` — threads through from config, same as `PlanAdapter.adapt()`
- `scoredCases` — raw retrieval results for provenance (caseId, score, featureSimilarities)
- `adaptedPlans` — output of per-plan `PlanAdapter.adapt()`, already context-adapted
- `currentFeatures` — the current case's feature map

### EnsemblePlan

```java
public record EnsemblePlan(
    AdaptedPlan synthesizedPlan,
    List<StepConsensus> stepAnalysis,
    List<String> sourceCaseIds,
    double ensembleConfidence,
    int inputPlanCount
) {
    public EnsemblePlan {
        Objects.requireNonNull(synthesizedPlan, "synthesizedPlan");
        Objects.requireNonNull(stepAnalysis, "stepAnalysis");
        stepAnalysis = List.copyOf(stepAnalysis);
        Objects.requireNonNull(sourceCaseIds, "sourceCaseIds");
        sourceCaseIds = List.copyOf(sourceCaseIds);
        if (!(ensembleConfidence >= 0.0 && ensembleConfidence <= 1.0))
            throw new IllegalArgumentException("ensembleConfidence must be in [0,1]");
        if (inputPlanCount < 0)
            throw new IllegalArgumentException("inputPlanCount must be >= 0");
        if (inputPlanCount == 0 && !stepAnalysis.isEmpty())
            throw new IllegalArgumentException("stepAnalysis must be empty when inputPlanCount is 0");
    }
}
```

- `synthesizedPlan` — the merged plan, reusing existing `AdaptedPlan` type. Steps in the synthesized plan carry `AdaptationAction` semantics: RETAINED means kept from the consensus, REMOVED means dropped due to low support, etc.
- `stepAnalysis` — per-step consensus/divergence data covering all steps seen across all input plans, not just steps in the synthesized plan
- `sourceCaseIds` — all cases that contributed at least one step to the synthesis
- `ensembleConfidence` — aggregate signal: high when strong consensus among high-scoring cases, low when divergent or low-scoring. Range [0,1].
- `inputPlanCount` — how many adapted plans were analyzed (denominator for consensus ratios)

### StepConsensus

```java
public record StepConsensus(
    String bindingName,
    String capabilityName,
    int occurrenceCount,
    int totalPlans,
    Map<String, Integer> workerDistribution,
    Map<String, Integer> outcomeDistribution,
    Map<Integer, Integer> priorityDistribution,
    List<String> contributingCaseIds,
    StepAgreement agreement
) {
    public StepConsensus {
        Objects.requireNonNull(bindingName, "bindingName");
        // capabilityName nullable — AdaptedStep permits null capabilityName
        // (ADDED steps with unresolved capability). Step identity uses
        // Objects.equals for comparison, so null == null is correct.
        if (occurrenceCount < 1)
            throw new IllegalArgumentException("occurrenceCount must be >= 1");
        if (totalPlans < 1)
            throw new IllegalArgumentException("totalPlans must be >= 1");
        workerDistribution = workerDistribution != null ? Map.copyOf(workerDistribution) : Map.of();
        outcomeDistribution = outcomeDistribution != null ? Map.copyOf(outcomeDistribution) : Map.of();
        priorityDistribution = priorityDistribution != null ? Map.copyOf(priorityDistribution) : Map.of();
        contributingCaseIds = contributingCaseIds != null ? List.copyOf(contributingCaseIds) : List.of();
        Objects.requireNonNull(agreement, "agreement");
    }
}
```

**Step identity:** two adapted steps are "the same step" when they share `bindingName` + `capabilityName` (compared via `Objects.equals` — null `capabilityName` on both sides is a match). Worker, outcome, and priority are what varies — that's what the distribution maps capture.

- `workerDistribution` — e.g., `{"Worker-A": 3, "Worker-B": 2}` — divergence visible at a glance
- `outcomeDistribution` — e.g., `{"COMPLETED": 4, "FAULTED": 1}` — per-step quality signal
- `priorityDistribution` — e.g., `{1: 4, 5: 1}` — priority consensus/divergence across plans
- `contributingCaseIds` — which cases had this step (provenance)
- `agreement` — classified enum (see below)

### StepAgreement

```java
public enum StepAgreement {
    UNANIMOUS,    // all plans include this step with same worker
    CONSENSUS,    // majority of plans include this step (>50%), workers may vary
    CONTESTED,    // step present in multiple plans but workers diverge significantly
    MINORITY,     // step appears in ≤50% of plans
    UNIQUE        // step appears in exactly one plan
}
```

Gives routing strategies and audit consumers a classified signal without needing to recompute ratios from the raw distributions.

## Tracking

### EnsembleTrace

```java
public record EnsembleTrace(
    String traceId,
    String retrievalTraceId,
    String caseType,
    List<String> sourceCaseIds,
    List<StepConsensus> stepAnalysis,
    List<AdaptedStep> synthesizedSteps,
    int inputPlanCount,
    double ensembleConfidence,
    Map<String, FeatureValue> currentFeatures,
    Instant timestamp
) {
    public EnsembleTrace {
        Objects.requireNonNull(traceId, "traceId");
        // retrievalTraceId nullable — set by engine integration, not available
        // at the decorator layer (same pattern as AdaptationTrace.retrievalTraceId)
        Objects.requireNonNull(caseType, "caseType");
        Objects.requireNonNull(sourceCaseIds, "sourceCaseIds");
        sourceCaseIds = List.copyOf(sourceCaseIds);
        Objects.requireNonNull(stepAnalysis, "stepAnalysis");
        stepAnalysis = List.copyOf(stepAnalysis);
        Objects.requireNonNull(synthesizedSteps, "synthesizedSteps");
        synthesizedSteps = List.copyOf(synthesizedSteps);
        Objects.requireNonNull(currentFeatures, "currentFeatures");
        currentFeatures = Map.copyOf(currentFeatures);
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
```

Captures analysis results, synthesized steps, and context for audit. Self-contained — an auditor can reconstruct what was analyzed and what was produced without external lookups.

### CbrEnsembleRecorded

```java
public record CbrEnsembleRecorded(EnsembleTrace trace) {
    public CbrEnsembleRecorded {
        Objects.requireNonNull(trace, "trace");
    }
}
```

CDI event fired after ensemble analysis, matching the `CbrAdaptationRecorded` pattern.

### TrackingPlanEnsembleAnalyzer

```java
@Decorator
@Priority(50)
@IfBuildProperty(name = "casehub.cbr.ensemble-tracking.enabled", stringValue = "true")
public class TrackingPlanEnsembleAnalyzer implements PlanEnsembleAnalyzer {

    private final PlanEnsembleAnalyzer delegate;
    private final Consumer<CbrEnsembleRecorded> eventSink;

    @Inject
    TrackingPlanEnsembleAnalyzer(@Delegate @Any PlanEnsembleAnalyzer delegate,
                                 Event<CbrEnsembleRecorded> recordedEvent) {
        this(delegate, recordedEvent::fire);
    }

    TrackingPlanEnsembleAnalyzer(PlanEnsembleAnalyzer delegate,
                                 Consumer<CbrEnsembleRecorded> eventSink) {
        this.delegate = delegate;
        this.eventSink = eventSink;
    }

    @Override
    public EnsemblePlan analyze(String caseType,
                                List<ScoredCbrCase<PlanCbrCase>> scoredCases,
                                List<AdaptedPlan> adaptedPlans,
                                Map<String, FeatureValue> currentFeatures) {
        EnsemblePlan result = delegate.analyze(caseType, scoredCases, adaptedPlans, currentFeatures);
        try {
            var trace = new EnsembleTrace(
                UUID.randomUUID().toString(),
                null,  // retrievalTraceId — set by engine integration
                caseType,
                result.sourceCaseIds(),
                result.stepAnalysis(),
                result.synthesizedPlan().steps(),
                result.inputPlanCount(),
                result.ensembleConfidence(),
                currentFeatures,
                Instant.now()
            );
            eventSink.accept(new CbrEnsembleRecorded(trace));
        } catch (Exception e) {
            LOG.warn("CBR ensemble tracking failed — returning result unchanged", e);
        }
        return result;
    }
}
```

Same pattern as `TrackingPlanAdapter`: opt-in via `@IfBuildProperty`, failure isolation, testable via constructor injection.

## Default Implementation

```java
@DefaultBean
@ApplicationScoped
public class NoOpPlanEnsembleAnalyzer implements PlanEnsembleAnalyzer {
    @Override
    public EnsemblePlan analyze(String caseType,
                                List<ScoredCbrCase<PlanCbrCase>> scoredCases,
                                List<AdaptedPlan> adaptedPlans,
                                Map<String, FeatureValue> currentFeatures) {
        Objects.requireNonNull(caseType, "caseType");
        Objects.requireNonNull(scoredCases, "scoredCases");
        Objects.requireNonNull(adaptedPlans, "adaptedPlans");
        Objects.requireNonNull(currentFeatures, "currentFeatures");
        if (scoredCases.size() != adaptedPlans.size())
            throw new IllegalArgumentException(
                "scoredCases and adaptedPlans must have same size: "
                + scoredCases.size() + " vs " + adaptedPlans.size());

        if (adaptedPlans.isEmpty()) {
            return new EnsemblePlan(new AdaptedPlan(List.of()), List.of(), List.of(), 0.0, 0);
        }

        int bestIdx = 0;
        for (int i = 1; i < scoredCases.size(); i++) {
            if (scoredCases.get(i).score() > scoredCases.get(bestIdx).score()) {
                bestIdx = i;
            }
        }

        AdaptedPlan best = adaptedPlans.get(bestIdx);
        String caseId = scoredCases.get(bestIdx).caseId();

        List<StepConsensus> analysis = best.steps().stream()
            .map(s -> new StepConsensus(
                s.bindingName(), s.capabilityName(),
                1, 1,
                s.workerName() != null ? Map.of(s.workerName(), 1) : Map.of(),
                s.stepOutcome() != null ? Map.of(s.stepOutcome(), 1) : Map.of(),
                Map.of(s.priority(), 1),
                caseId != null ? List.of(caseId) : List.of(),
                StepAgreement.UNANIMOUS))
            .toList();

        return new EnsemblePlan(best, analysis,
            caseId != null ? List.of(caseId) : List.of(),
            Math.max(0.0, scoredCases.get(bestIdx).score()),
            1);
    }
}
```

Zero behavioral change by default. Picks the top-scoring case's adapted plan and reports `inputPlanCount = 1` with all steps UNANIMOUS — honestly reflecting that only one plan was examined. Consumers see `{occurrenceCount: 1, totalPlans: 1, agreement: UNANIMOUS}` which correctly conveys "full consensus within the examined scope" rather than the misleading `{occurrenceCount: 1, totalPlans: 5, agreement: UNIQUE}` which would imply weak consensus from thorough analysis. Existing consumers work without ensemble until they provide an implementation.

## Module Placement

| Type | Module | Package |
|------|--------|---------|
| `PlanEnsembleAnalyzer` | `memory-api` | `io.casehub.neocortex.memory.cbr` |
| `EnsemblePlan` | `memory-api` | `io.casehub.neocortex.memory.cbr` |
| `StepConsensus` | `memory-api` | `io.casehub.neocortex.memory.cbr` |
| `StepAgreement` | `memory-api` | `io.casehub.neocortex.memory.cbr` |
| `EnsembleTrace` | `memory-api` | `io.casehub.neocortex.memory.cbr` |
| `CbrEnsembleRecorded` | `memory-api` | `io.casehub.neocortex.memory.cbr` |
| `NoOpPlanEnsembleAnalyzer` | `memory` | `io.casehub.neocortex.memory.cbr.runtime` |
| `TrackingPlanEnsembleAnalyzer` | `memory-cbr-tracking` | `io.casehub.neocortex.memory.cbr.tracking` |
| Contract tests | `memory-testing` | `io.casehub.neocortex.memory.cbr.testing` |

No new modules. Follows the module placement established by `PlanAdapter` (#85).

## Testing Strategy

### Contract tests in memory-testing

`PlanEnsembleAnalyzerContractTest` — abstract base class that implementations extend. Establishes the CBR SPI contract test pattern; `PlanAdapterContractTest` (specified in #85 but not yet implemented) should be created alongside:

1. **single_plan_returns_that_plan** — N=1, synthesized plan matches input
2. **empty_plans_handled** — empty list produces empty EnsemblePlan
3. **parallel_list_length_mismatch_rejected** — IllegalArgumentException
4. **null_inputs_rejected** — NPE on null caseType, null lists, null features
5. **source_case_ids_populated** — contributing cases appear in sourceCaseIds
6. **ensemble_confidence_in_range** — always [0,1]
7. **input_plan_count_in_valid_range** — inputPlanCount >= 1 && inputPlanCount <= adaptedPlans.size()

### NoOp tests in memory/

1. **noOp_returns_best_scoring_plan** — picks highest-score case's adapted plan
2. **noOp_step_analysis_reflects_single_plan** — each step shows occurrenceCount=1, totalPlans=1, agreement=UNANIMOUS
3. **noOp_empty_input** — returns empty ensemble
4. **noOp_preserves_step_fields** — all fields round-trip
5. **noOp_multiple_plans_picks_best** — with 3 plans at different scores, picks highest
6. **noOp_multi_plan_reports_inputPlanCount_1** — with N>1 plans, inputPlanCount=1 (not N)

### Value type tests in memory-api/

1. **EnsemblePlan_confidence_range** — reject < 0 and > 1
2. **EnsemblePlan_inputPlanCount_minimum** — reject < 0
3. **EnsemblePlan_immutability** — stepAnalysis and sourceCaseIds defensively copied
4. **StepConsensus_validation** — null bindingName rejected, null capabilityName accepted, occurrenceCount < 1 rejected, totalPlans < 1 rejected
5. **StepConsensus_immutability** — distributions (including priorityDistribution) and contributingCaseIds defensively copied
6. **StepConsensus_null_distributions_default_to_empty** — null maps (including priorityDistribution) → empty maps
7. **StepAgreement_coverage** — all enum values accessible
8. **EnsembleTrace_immutability** — all collections (including synthesizedSteps) defensively copied
9. **EnsembleTrace_null_traceId_rejected** — NPE
10. **EnsembleTrace_retrievalTraceId_nullable** — null retrievalTraceId accepted
11. **EnsemblePlan_empty_input_invariant** — inputPlanCount=0 with non-empty stepAnalysis rejected

### Tracking tests in memory-cbr-tracking/

1. **tracking_fires_event_after_analysis** — CbrEnsembleRecorded fired with valid trace
2. **tracking_trace_fields_correct** — traceId non-null, caseType matches, sourceCaseIds match, timestamp non-null
3. **tracking_failure_isolated** — event sink throws → result still returned
4. **tracking_fires_for_noOp** — trace recorded even when no real ensemble runs

## Configuration

| Property | Default | Module |
|----------|---------|--------|
| `casehub.cbr.ensemble-tracking.enabled` | `false` | memory-cbr-tracking |

Matches the convention of `casehub.cbr.tracking.enabled` and `casehub.cbr.adaptation-tracking.enabled`.

## Downstream Integration

### Engine

`CbrRetrievalService` wires `PlanEnsembleAnalyzer` after the per-plan adaptation loop:

```
retrieveSimilar() → [for each: planAdapter.adapt()] → adaptedPlans
                                                        ↓
                   map adaptedPlans → List<RetrievedExperience>  (unchanged)
                                                        ↓
                   ensembleAnalyzer.analyze(scoredCases, adaptedPlans, features) → EnsemblePlan
                                                        ↓
                   AgentRoutingContext gains ensemblePlan field  (new)
```

Engine integration is a separate issue (follow-on, same pattern as engine#727 for PlanAdapter).

## Out of Scope

- Engine-side wiring (follow-on issue)
- Real ensemble implementation with consensus/divergence algorithms (consumers provide domain-specific implementations — engine, clinical)
- `ReactivePlanEnsembleAnalyzer` — add when a reactive consumer needs it
- Persistent ensemble trace storage (follow-on if needed)
- Cross-plan analysis for non-plan case types (FeatureVectorCbrCase, TextualCbrCase)
