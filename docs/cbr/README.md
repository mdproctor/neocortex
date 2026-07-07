# CBR — Case-Based Reasoning

Every CaseHub application is a natural CBR system. Cases arrive, decisions are made, outcomes
are observed. The next time a similar case arrives, the system should draw on that history
rather than starting from scratch. CBR makes that concrete: retain solved cases as structured
records, retrieve the most similar ones when a new case arrives, and let the application
reuse or adapt those precedents.

This is not information retrieval. RAG finds *documents* relevant to a query. CBR finds
*past decisions* structurally comparable to a current situation. The difference matters:
a RAG system retrieves protocol text about adverse events; a CBR system retrieves the five
most similar adverse events your team has already handled and what they did about them.

## The CBR Cycle

| Step | What it does | SPI |
|------|-------------|-----|
| **Retain** | Store a solved case with structured features | `CbrCaseMemoryStore.store()` |
| **Retrieve** | Find similar past cases | `CbrCaseMemoryStore.retrieveSimilar()` |
| **Reuse** | Apply retrieved solutions to the new case | Application-layer |
| **Revise** | Adapt retrieved solutions when they don't fit directly | Future: plan-based adaptation |

## Why Structured Similarity, Not Just Embeddings

Dense vector search finds things that *sound similar*. That is necessary but not sufficient.
Two AML investigations might describe entirely different situations in similar language, or
describe identical situations in different language. What makes them comparable is structure:
same transaction pattern, same risk tier, same jurisdiction. The embedding similarity then
discriminates *within* that structural cohort.

CBR uses a two-phase retrieval: payload filters narrow to structurally comparable cases
(categorical exact match, numeric range, text keywords), then optional dense vector
similarity ranks within the filtered set. The structural filter is the precision; the
vector is the recall.

## Case Types

Three `CbrCase` implementations cover different reasoning paradigms. All share
`problem()`, `solution()`, `outcome()`, `confidence()`, and `cbrType()`.

| Type | Discriminator | When to use |
|------|--------------|-------------|
| `TextualCbrCase` | `"textual"` | No structured features. Pure NL similarity on problem text. |
| `FeatureVectorCbrCase` | `"feature-vector"` | Structured categorical/numeric/text features. Most applications. |
| `PlanCbrCase` | `"plan"` | Feature-Vector plus ordered execution traces. CHEF-style case-based planning. |

See [CBR Types](cbr-types.md) for a detailed explanation of each type, its inputs/outputs,
and how they layer for routing.

## Feature Schema

Schemas declare the shape of features per case type. They drive Qdrant index creation,
query validation, and similarity scoring.

```java
CbrFeatureSchema schema = CbrFeatureSchema.of("aml-investigation",
    FeatureField.categorical("transaction_pattern"),
    FeatureField.categorical("risk_tier", SimilaritySpec.categoricalTableBuilder()
        .add("LOW", "MEDIUM", 0.6)
        .add("MEDIUM", "HIGH", 0.5)
        .build()),
    FeatureField.numeric("amount", 0, 1_000_000,
        new SimilaritySpec.GaussianDecay(0.3)),
    FeatureField.text("description"),
    FeatureField.semanticText("analyst_notes"));
```

### FeatureField — sealed hierarchy

Three field types, each with optional similarity configuration:

| Type | Factory | Query behaviour | Optional config |
|------|---------|----------------|-----------------|
| `Categorical` | `FeatureField.categorical(name)` | Exact match (default) or similarity table lookup | `SimilaritySpec` — `CategoricalTable` for graded similarity |
| `Numeric` | `FeatureField.numeric(name, min, max)` | Range filter | `SimilaritySpec` — `GaussianDecay`, `StepDecay`, or `ExponentialDecay` |
| `Text` | `FeatureField.text(name)` | Keyword match | — |
| `Text (semantic)` | `FeatureField.semanticText(name)` | Cosine similarity via `EmbeddingModel` | Requires `memory-cbr-embedding` on classpath |

`FeatureField` is sealed with `permits Categorical, Numeric, Text`. All dispatch sites
use exhaustive `switch` — adding a new field type is a compile error until every consumer
handles it.

### SimilaritySpec — sealed interface

Pure data records that attach optional similarity configuration to fields. The schema
carries these; `CbrSimilarityScorer` reads them at scoring time.

| Spec | Attaches to | What it does |
|------|------------|-------------|
| `CategoricalTable` | `Categorical` | Graded similarity between category values. Symmetric: `add("A", "B", 0.7)` implies `("B", "A") = 0.7`. Self-pairs silently ignored. Unlisted pairs score 0.0. |
| `GaussianDecay` | `Numeric` | `exp(-0.5 * (d/sigma)^2)` — smooth decay around the query value. Higher sigma = broader tolerance. |
| `StepDecay` | `Numeric` | Binary: 1.0 if normalised distance <= tolerance, 0.0 otherwise. |
| `ExponentialDecay` | `Numeric` | `exp(-decayRate * d)` — sharp falloff for fields where close matches matter much more than distant ones. |

`SimilaritySpec` is sealed with `permits CategoricalTable, GaussianDecay, StepDecay, ExponentialDecay`.

## Similarity Scoring

`CbrSimilarityScorer` computes weighted composite similarity between a query and a
candidate case. Three-level precedence for each field:

1. **Caller override** — `LocalSimilarityFunction` passed at query time (highest priority)
2. **Schema spec** — `SimilaritySpec` attached to the `FeatureField` in the schema
3. **Type default** — categorical: exact match (1.0 or 0.0); numeric: Gaussian decay with sigma=0.3

```java
double score = CbrSimilarityScorer.score(
    queryFeatures, caseFeatures, weights, schema);

// Or with caller overrides:
double score = CbrSimilarityScorer.score(
    queryFeatures, caseFeatures, weights, schema,
    Map.of("description", myCustomSimilarity));
```

When dense vector search is also active, feature and vector scores combine:

```java
double combined = CbrSimilarityScorer.compositeScore(
    featureScore, vectorScore, query.vectorWeight());
```

### Semantic Text Similarity

`EmbeddingTextSimilarity` (in `memory-cbr-embedding`) implements `LocalSimilarityFunction`
using an `EmbeddingModel` for cosine similarity on text fields marked `semantic`.
Batch `precompute()` embeds all candidate texts in one call; `compute()` uses the cache.
The Qdrant backend builds these overrides automatically for semantic text fields during
two-pass retrieval.

## CbrQuery

Immutable query record with builder-style `with*` methods:

```java
CbrQuery query = CbrQuery.of(tenantId, domain, "aml-investigation",
        Map.of("transaction_pattern", "STRUCTURING", "amount", 50_000), 10)
    .withProblem("Suspicious structuring across three accounts")
    .withMinSimilarity(0.6)
    .withNotBefore(Instant.now().minus(Duration.ofDays(90)))
    .withWeights(Map.of("transaction_pattern", 2.0, "amount", 1.0))
    .withVectorWeight(0.3);
```

| Field | Purpose |
|-------|---------|
| `features` | Structural filter — categorical exact match, numeric range, text keyword |
| `weights` | Per-field importance for composite scoring |
| `problem` | Enables dense vector search within the filtered set |
| `minSimilarity` | Threshold for dense vector results |
| `notBefore` | Temporal filter — only cases after this instant |
| `vectorWeight` | Balance between feature score and vector score in composite |
| `topK` | Maximum results |

## Store and Retrieve

```java
// Store
var cbrCase = new FeatureVectorCbrCase(
    "Suspicious structuring across three accounts",
    "Filed SAR, escalated to compliance",
    "SAR_FILED", 0.95,
    Map.of("transaction_pattern", "STRUCTURING", "risk_tier", "HIGH", "amount", 50_000));

String id = cbrStore.store(cbrCase, "aml-investigation", entityId, domain, tenantId, caseId);

// Retrieve
List<ScoredCbrCase<FeatureVectorCbrCase>> similar =
    cbrStore.retrieveSimilar(query, FeatureVectorCbrCase.class);
```

## Case Enrichment

`CaseEnrichmentStep` SPI enables pre-store transformation pipelines. Steps are applied
by `CaseEnrichmentDecorator` (`@Decorator` on `CaseMemoryStore`) before the case reaches
the store. Each step declares `appliesTo()`, `enrich()`, `priority()`, and `required()`.

## Backends

| Backend | Module | Retrieval | Persistence | Use for |
|---------|--------|-----------|-------------|---------|
| **InMemory** | `memory-cbr-inmem` | Categorical exact match, `CbrSimilarityScorer` scoring | None | Tests, dev. No vector search. |
| **Qdrant** | `memory-qdrant` | Payload filters + dense vector + semantic text (two-pass) | Qdrant + optional `CaseMemoryStore` delegate | Production. Full similarity. |

### Qdrant Backend — two-pass retrieval

The Qdrant backend (`QdrantCbrCaseMemoryStore`) runs a two-pass retrieve when semantic
text fields are present:

1. **First pass:** Qdrant payload filters (categorical/numeric/text) + optional dense vector
   on `problem()`. Returns candidates with vector scores.
2. **Second pass:** `EmbeddingTextSimilarity.precompute()` batch-embeds all candidate
   semantic text values, then `CbrSimilarityScorer` computes weighted composite scores
   with the text similarity overrides.

Without semantic text fields, single-pass retrieval (Qdrant filters + vector).

### Reconciliation

`CbrReconciliationService` synchronises the Qdrant index with the platform
`CaseMemoryStore` after schema changes (e.g., new dimensions). Batch upserts,
`discoverTenants()` for multi-tenant sweeps, Micrometer counters for observability.
Admin-triggered — not automatic.

## Configuration

```properties
casehub.memory.cbr.qdrant.host=localhost
casehub.memory.cbr.qdrant.port=6334
casehub.memory.cbr.qdrant.collection-prefix=cbr
casehub.memory.cbr.qdrant.dense-vector-name=dense
casehub.memory.cbr.qdrant.max-retries=3
# casehub.memory.cbr.qdrant.api-key=           # optional
# casehub.memory.cbr.qdrant.use-tls=false
# casehub.memory.cbr.qdrant.allow-dimension-migration=false
```

## Testing

`CbrCaseMemoryStoreContractTest` (in `memory-testing`) provides a 37-test contract suite
that every backend must pass. Covers store/retrieve round-trip, schema registration,
query validation, erase, feature filtering, and type hierarchy.

## Maven Dependencies

```xml
<!-- API types (compile) -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-api</artifactId>
</dependency>

<!-- CDI wiring: NoOp default + reactive bridge + enrichment (runtime) -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Semantic text similarity (runtime, optional) -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-cbr-embedding</artifactId>
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
| Life | Feature-Vector | [guide-life.md](guide-life.md) |
| IoT | Feature-Vector | [guide-iot.md](guide-iot.md) |

## Examples

See [example-cbr](../../examples/example-cbr/) for runnable demos across six domains.
