# CBR Integration Guide

Case-Based Reasoning (CBR) retrieves similar past cases to inform decisions on new ones.
CaseHub's CBR SPI provides structured similarity search across case histories — categorical
exact match, numeric range filtering, and optional dense vector similarity on text fields.

## The CBR Cycle

| Step | What it does | CaseHub status |
|------|-------------|----------------|
| **Retain** | Store solved cases with structured features | `CbrCaseMemoryStore.store()` |
| **Retrieve** | Find similar past cases | `CbrCaseMemoryStore.retrieveSimilar()` |
| **Reuse** | Apply retrieved solutions | Application-layer (routing, suggestions) |
| **Revise** | Adapt retrieved solutions | Future — plan-based adaptation |

## Case Types

Three `CbrCase` implementations cover different CBR paradigms:

| Type | When to use | Features | Extra |
|------|------------|----------|-------|
| `TextualCbrCase` | NL-only cases — no structured fields | — | — |
| `FeatureVectorCbrCase` | Structured categorical/numeric/text features | `Map<String, Object>` | — |
| `PlanCbrCase` | Plan traces — binding/capability/worker execution chains | `Map<String, Object>` | `List<PlanTrace>` |

All types share: `problem()` (NL text), `solution()`, `outcome()`, `confidence()`.

## Feature Schema

Declare the shape of your features per case type. Drives index creation and query validation.

```java
CbrFeatureSchema schema = CbrFeatureSchema.of("my-case-type",
    FeatureField.categorical("category_field"),    // exact match
    FeatureField.numeric("numeric_field", 0, 100), // range filter
    FeatureField.text("text_field"));               // keyword filter
```

Register at startup:

```java
@ApplicationScoped
public class MyCbrSetup {
    @Inject CbrCaseMemoryStore cbrStore;

    void onStart(@Observes StartupEvent ev) {
        cbrStore.registerSchema(schema);
    }
}
```

## Store a Case

```java
var cbrCase = new FeatureVectorCbrCase(
    "NL problem description",
    "what was done",
    "SUCCESS",
    0.9,
    Map.of("category_field", "VALUE", "numeric_field", 42));

cbrStore.store(cbrCase, "my-case-type", entityId, domain, tenantId, caseId);
```

## Retrieve Similar Cases

```java
var query = CbrQuery.of(tenantId, domain, "my-case-type",
    Map.of("category_field", "VALUE"), 5);

List<FeatureVectorCbrCase> similar = cbrStore.retrieveSimilar(query, FeatureVectorCbrCase.class);
```

Query features filter the candidate set — categorical fields require exact match, numeric
fields support range, text fields use keyword matching. `topK` limits results.

## Backends

| Backend | Module | Use for |
|---------|--------|---------|
| **InMemory** | `memory-cbr-inmem` | Tests and dev. Categorical exact match. No persistence. |
| **Qdrant** | `memory-qdrant` | Production. Payload filters + optional dense vector on `problem()`. |

## Maven Dependencies

```xml
<!-- API types (compile) -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-api</artifactId>
</dependency>

<!-- CDI wiring: NoOp default + reactive bridge (runtime) -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- In-memory backend for tests -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-cbr-inmem</artifactId>
    <scope>test</scope>
</dependency>

<!-- Qdrant backend for production -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-qdrant</artifactId>
    <scope>runtime</scope>
</dependency>
```

## App-Specific Guides

| App | Paradigm | Guide |
|-----|----------|-------|
| DevTown | Textual + Feature-Vector | [guide-devtown.md](guide-devtown.md) |
| AML | Textual + Feature-Vector | [guide-aml.md](guide-aml.md) |
| Clinical | Feature-Vector | [guide-clinical.md](guide-clinical.md) |
| Engine | Feature-Vector + Plan-Based | [guide-engine.md](guide-engine.md) |
