# Engine — CBR for Plan Traces

## Why CBR

The engine executes plans — sequences of bindings activating capabilities through
workers. When composing a new plan, CBR finds similar past plans and surfaces what
worked: which bindings were activated, which workers were selected, what the outcomes
were. This is CHEF-style case-based planning — retrieve a similar past plan, then
adapt it for the current situation.

## CBR Paradigm

**Feature-Vector → Plan-Based CBR.** Structured features (domain-specific attributes)
filter the candidate set. The plan trace captures the full execution chain:
binding → capability → worker → outcome. This is the only CaseHub app that uses
`PlanCbrCase` — all others use `FeatureVectorCbrCase`.

Plan-Based CBR goes beyond "find similar cases" to "find similar plans and show me
how they were structured." The plan trace enables adaptation: "4 out of 5 similar
plans activated scout → assess-threat → early-pressure — those 4 won."

## Feature Schema

Feature schemas are domain-specific — each case type defines its own features.
QuarkMind (StarCraft II) is the reference example:

```java
CbrFeatureSchema SCHEMA = CbrFeatureSchema.of("starcraft-game",
    FeatureField.categorical("opponent_race"),     // ZERG, PROTOSS, TERRAN
    FeatureField.categorical("detected_build"),    // ROACH_RUSH, ZEALOT_RUSH, etc.
    FeatureField.numeric("army_size_ratio", 0.0, 3.0),
    FeatureField.numeric("resource_advantage", -5000, 5000));
```

Other engine domains define their own schemas with different features — the SPI
is generic. The pattern is always: categorical fields for discrete situation
classification, numeric fields for continuous state.

## PlanTrace

`PlanTrace` captures one step of plan execution:

```java
public record PlanTrace(
    String bindingName,        // which binding was activated
    String capabilityName,     // which capability it targeted
    String workerName,         // which worker was selected (nullable if not yet assigned)
    String stepOutcome,        // SUCCESS, FAILURE, SKIPPED, TIMEOUT
    int priority,              // activation priority
    Map<String, Object> parameters)  // domain-specific step parameters
```

A plan case contains the full execution chain as `List<PlanTrace>`.

## Retain — Storing Plan Outcomes

At case close, the `CaseOutcomeObserver` builds a `PlanCbrCase` from the plan
execution trace:

```java
@ApplicationScoped
public class PlanOutcomeObserver {

    @Inject CbrCaseMemoryStore cbrStore;

    void onCaseOutcome(@Observes CaseOutcomeEvent event) {
        List<PlanTrace> traces = event.planSteps().stream()
            .map(step -> new PlanTrace(
                step.bindingName(),
                step.capabilityName(),
                step.workerName(),
                step.outcome().name(),
                step.priority(),
                step.parameters()))
            .toList();

        var cbrCase = new PlanCbrCase(
            describeProblem(event),                         // NL problem description
            describeSolution(event),                        // NL solution summary
            event.outcomeLabel(),                           // WIN, LOSS, TIMEOUT, etc.
            event.confidence(),                             // nullable
            extractFeatures(event),                         // domain-specific features
            traces);                                        // full plan trace

        cbrStore.store(cbrCase, event.caseType(),
            event.entityId(), PLAN_DOMAIN, event.tenantId(), event.caseId());
    }

    private Map<String, Object> extractFeatures(CaseOutcomeEvent event) {
        // Domain-specific — QuarkMind extracts opponent_race, detected_build, etc.
        // from the CaseFileSnapshot
        return event.caseFileSnapshot().toCbrFeatures();
    }
}
```

## Retrieve — Finding Similar Past Plans

At plan creation, query for similar past plans:

```java
@ApplicationScoped
public class PlanAdvisor {

    @Inject CbrCaseMemoryStore cbrStore;

    public List<PlanCbrCase> findSimilarPlans(String caseType, Map<String, Object> features,
                                               String tenantId) {
        var query = CbrQuery.of(tenantId, PLAN_DOMAIN, caseType, features, 5);
        return cbrStore.retrieveSimilar(query, PlanCbrCase.class);
    }
}
```

### Using Plan Traces for Adaptation

Retrieved plan traces enable CHEF-style adaptation:

```java
List<PlanCbrCase> similar = planAdvisor.findSimilarPlans("starcraft-game",
    Map.of("opponent_race", "Zerg", "detected_build", "ROACH_RUSH"), tenantId);

// Analyse retrieved plans
long winsWithScout = similar.stream()
    .filter(c -> "WIN".equals(c.outcome()))
    .filter(c -> c.planTrace().stream()
        .anyMatch(t -> "scout".equals(t.bindingName())))
    .count();

// "4/5 similar winning plans started with scouting — suggest scout binding at priority 1"
```

This is null adaptation today (apply the retrieved plan as-is or use it for
suggestions). Transformational adaptation (modify the retrieved plan — swap a
worker, adjust priority, skip/add bindings) is the Revise step and is future work.

## Mock → Production

| Phase | Backend | What works |
|-------|---------|-----------|
| **Now** | `memory-cbr-inmem` | Schema validation, categorical exact match, plan trace round-trip, store/retrieve. No persistence. |
| **Production** | `memory-qdrant` | Payload filters on features + dense vector on problem() + plan trace in payload JSON. Persistent. Scalable. |

## Maven Dependencies

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-api</artifactId>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-cbr-inmem</artifactId>
    <scope>test</scope>
</dependency>
```
