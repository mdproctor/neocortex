# CBR Similarity Functions — Schema-Attached Specs and Pre-Built Implementations

**Issues:** #107 (categorical similarity tables), #108 (per-field similarity function configuration)
**Part of:** CBR capability tiers (#86), Tier 2 structured case representation
**Blockers:** #82, #87 — both closed/resolved
**Date:** 2026-07-07
**Module:** `memory-api` (Tier 1, zero external deps)

## Problem

`CbrSimilarityScorer` uses fixed similarity logic per field type: binary exact match for
Categorical/Text, linear decay for Numeric. Some domains need graduated categorical similarity
(e.g., "headache" is more similar to "migraine" than to "fracture") and alternative numeric
decay curves (Gaussian, step, exponential).

The `LocalSimilarityFunction` override mechanism exists but is caller-side only — similarity
behaviour should be declarable on the schema so it travels with the field definition.

## Design

### SimilaritySpec — data-oriented similarity configuration

A sealed interface with record variants, replacing the original approach of attaching a
`LocalSimilarityFunction` (a `@FunctionalInterface`) to `FeatureField` records.

**Why data, not behavior:** `FeatureField` variants are Java records. Records derive `equals()`
and `hashCode()` from all components. Lambdas and anonymous implementations compare by identity,
not logical equivalence — putting a `LocalSimilarityFunction` on a record would break value
semantics. `SimilaritySpec` records are pure data: clean `equals`/`hashCode`, serializable, and
inspectable via pattern matching.

**Relationship to prior spec (#106):** The semantic text similarity spec rejected "Type-level
strategy on `CbrFeatureSchema` — turns the schema from a data declaration into a behavior
carrier." `SimilaritySpec` is data, not behavior. The schema remains a data declaration —
it declares *what kind* of similarity to compute (Gaussian with σ=0.5), and the scorer resolves
that declaration to behavior at compute time. No functional interfaces, no CDI dependencies,
no lambdas on records.

```java
public sealed interface SimilaritySpec {

    // Categorical
    record CategoricalTable(Map<String, Map<String, Double>> similarities) implements SimilaritySpec {
        public CategoricalTable {
            Objects.requireNonNull(similarities, "similarities");
            // Mirror entries for symmetry, validate scores in [0,1], reject conflicting entries
            similarities = mirrorAndValidate(similarities);
        }
    }

    // Numeric decay curves — shape parameters only, no min/max
    record GaussianDecay(double sigma) implements SimilaritySpec {
        public GaussianDecay {
            if (sigma <= 0) throw new IllegalArgumentException("sigma must be > 0");
        }
    }
    record StepDecay(double tolerance) implements SimilaritySpec {
        public StepDecay {
            if (tolerance < 0 || tolerance > 1)
                throw new IllegalArgumentException("tolerance must be in [0, 1]");
        }
    }
    record ExponentialDecay(double decayRate) implements SimilaritySpec {
        public ExponentialDecay {
            if (decayRate <= 0) throw new IllegalArgumentException("decayRate must be > 0");
        }
    }

    static CategoricalTableBuilder categoricalTableBuilder() { ... }
}
```

**Numeric specs carry only shape parameters.** The scorer extracts `min`/`max` from the
`FeatureField.Numeric` instance at compute time — single source of truth. No min/max
duplication between field and function.

### Schema-attached similarity specs

`FeatureField.Categorical` and `FeatureField.Numeric` gain an optional `SimilaritySpec`
parameter. `FeatureField.Text` does not — Text similarity is configured via the `semantic`
flag, which works through the caller override mechanism (see §Interaction with Text.semantic).

Each record validates accepted spec types at construction:

```java
public sealed interface FeatureField permits FeatureField.Categorical, FeatureField.Numeric, FeatureField.Text {
    String name();

    record Categorical(String name, SimilaritySpec similaritySpec) implements FeatureField {
        public Categorical(String name) { this(name, null); }
        public Categorical {
            Objects.requireNonNull(name, "name");
            if (similaritySpec != null) {
                switch (similaritySpec) {
                    case SimilaritySpec.CategoricalTable _ -> {}
                    case SimilaritySpec.GaussianDecay _, SimilaritySpec.StepDecay _,
                         SimilaritySpec.ExponentialDecay _ -> throw new IllegalArgumentException(
                        "Categorical fields only support CategoricalTable specs");
                }
            }
        }
    }

    record Numeric(String name, double min, double max, SimilaritySpec similaritySpec) implements FeatureField {
        public Numeric(String name, double min, double max) { this(name, min, max, null); }
        public Numeric {
            Objects.requireNonNull(name, "name");
            if (min > max) throw new IllegalArgumentException(
                "min must be <= max, got min=" + min + " max=" + max);
            if (similaritySpec != null) {
                switch (similaritySpec) {
                    case SimilaritySpec.GaussianDecay _, SimilaritySpec.StepDecay _,
                         SimilaritySpec.ExponentialDecay _ -> {}
                    case SimilaritySpec.CategoricalTable _ -> throw new IllegalArgumentException(
                        "Numeric fields do not support CategoricalTable specs");
                }
            }
        }
    }

    record Text(String name, boolean semantic) implements FeatureField {
        // Unchanged from current. No SimilaritySpec parameter.
        public Text { Objects.requireNonNull(name, "name"); }
        public Text(String name) { this(name, false); }
    }

    static FeatureField categorical(String name) { return new Categorical(name); }
    static FeatureField categorical(String name, SimilaritySpec similaritySpec) {
        return new Categorical(name, similaritySpec);
    }
    static FeatureField numeric(String name, double min, double max) {
        return new Numeric(name, min, max);
    }
    static FeatureField numeric(String name, double min, double max, SimilaritySpec similaritySpec) {
        return new Numeric(name, min, max, similaritySpec);
    }
    static FeatureField text(String name) { return new Text(name); }
    static FeatureField semanticText(String name) { return new Text(name, true); }
}
```

Usage:
```java
CbrFeatureSchema.of("clinical-ae",
    FeatureField.categorical("eventType",
        SimilaritySpec.categoricalTableBuilder()
            .add("headache", "migraine", 0.8)
            .add("headache", "fracture", 0.1)
            .add("migraine", "fracture", 0.1)
            .build()),
    FeatureField.numeric("grade", 1, 5, new SimilaritySpec.GaussianDecay(0.3)),
    FeatureField.numeric("age", 0, 120),  // default linear decay
    FeatureField.semanticText("description")
);
```

### Scorer precedence chain

`CbrSimilarityScorer.localSimilarity()` checks three levels:

1. **Caller override** (highest) — `Map<String, LocalSimilarityFunction> overrides` passed to `score()`. Handles runtime concerns like `EmbeddingTextSimilarity` which needs a live `EmbeddingModel`. This is behavioral — lambdas and live dependencies belong here.
2. **Field-attached SimilaritySpec** — declared on the `FeatureField` in the schema. Resolved by the scorer to the corresponding computation. This is declarative — data that travels with the schema.
3. **Type default** (lowest) — Numeric → linear decay, Categorical/Text → exact match.

The scorer resolves `SimilaritySpec` via pattern matching and handles `NumericRange` centrally
before delegating to the decay function:

```java
private static double localSimilarity(FeatureField field, Object queryVal, Object caseVal,
                                      Map<String, LocalSimilarityFunction> overrides) {
    LocalSimilarityFunction override = overrides.get(field.name());
    if (override != null) return override.compute(queryVal, caseVal);

    return switch (field) {
        case FeatureField.Numeric n -> numericSimilarity(n, queryVal, caseVal);
        case FeatureField.Categorical c -> categoricalSimilarity(c, queryVal, caseVal);
        case FeatureField.Text t -> queryVal.equals(caseVal) ? 1.0 : 0.0;
    };
}

private static double categoricalSimilarity(FeatureField.Categorical field,
                                             Object queryVal, Object caseVal) {
    if (field.similaritySpec() == null) return queryVal.equals(caseVal) ? 1.0 : 0.0;
    return switch (field.similaritySpec()) {
        case SimilaritySpec.CategoricalTable(var table) -> {
            String q = (String) queryVal;
            String c = (String) caseVal;
            if (q.equals(c)) yield 1.0;
            yield table.getOrDefault(q, Map.of()).getOrDefault(c, 0.0);
        }
        case SimilaritySpec.GaussianDecay _, SimilaritySpec.StepDecay _,
             SimilaritySpec.ExponentialDecay _ ->
            throw new IllegalStateException("Unexpected spec on Categorical");
    };
}

private static double numericSimilarity(FeatureField.Numeric field,
                                         Object queryVal, Object caseVal) {
    double range = field.max() - field.min();
    if (range <= 0) return queryVal.equals(caseVal) ? 1.0 : 0.0;

    // NumericRange handling — centralized, not duplicated per decay function
    double normalizedDistance = computeNormalizedDistance(field, queryVal, caseVal);

    if (field.similaritySpec() == null) {
        return Math.max(0.0, 1.0 - normalizedDistance);  // linear decay (type default)
    }
    return switch (field.similaritySpec()) {
        case SimilaritySpec.GaussianDecay(var sigma) ->
            Math.exp(-normalizedDistance * normalizedDistance / (2 * sigma * sigma));
        case SimilaritySpec.StepDecay(var tol) ->
            normalizedDistance <= tol ? 1.0 : 0.0;
        case SimilaritySpec.ExponentialDecay(var rate) ->
            Math.exp(-rate * normalizedDistance);
        case SimilaritySpec.CategoricalTable _ ->
            throw new IllegalStateException("Unexpected spec on Numeric");
    };
}

private static double computeNormalizedDistance(FeatureField.Numeric field,
                                                Object queryVal, Object caseVal) {
    double range = field.max() - field.min();
    double caseNum = ((Number) caseVal).doubleValue();

    if (queryVal instanceof NumericRange nr) {
        if (caseNum >= nr.min() && caseNum <= nr.max()) return 0.0;
        double dist = caseNum < nr.min() ? nr.min() - caseNum : caseNum - nr.max();
        return dist / range;
    }

    double queryNum = ((Number) queryVal).doubleValue();
    return Math.abs(queryNum - caseNum) / range;
}
```

**Key benefit:** `NumericRange` handling is centralized in `computeNormalizedDistance()`. Every
decay function receives a normalized distance — no per-function `NumericRange` duplication.
Custom caller overrides (`LocalSimilarityFunction`, level 1) still need to handle `NumericRange`
themselves, but that is by design — caller overrides own their full behavior.

**Linear decay is the sole implementation.** The scorer's `null` SimilaritySpec path computes
`1.0 - normalizedDistance`, which is the current `numericSimilarity()` logic. There is no
separate `linear()` factory — linear is the type default when no SimilaritySpec is set.

### Categorical table — validation and builder

`CategoricalTable` construction validates and normalizes:

1. **Score range:** All scores must be in [0, 1]. Scores outside this range are rejected with `IllegalArgumentException`.
2. **Symmetry:** The constructor mirrors entries. `add("A", "B", 0.8)` automatically registers `("B", "A", 0.8)`.
3. **Conflicting symmetric entries:** If the input map contains both `("A", "B", 0.8)` and `("B", "A", 0.7)`, the constructor throws `IllegalArgumentException`. The builder rejects the second `add()` call for an already-registered pair.
4. **Self-pairs:** Ignored in the input — self-similarity is always 1.0, enforced by the scorer, not stored in the table.
5. **Empty table:** Valid. All unlisted pairs default to 0.0, self-pairs to 1.0. Equivalent to exact match. Documented as degenerate but accepted.
6. **Lookup keys:** Case-sensitive. `"Headache"` and `"headache"` are distinct values. Callers are responsible for normalization.

Builder:
```java
SimilaritySpec.CategoricalTable table = SimilaritySpec.categoricalTableBuilder()
    .add("headache", "migraine", 0.8)
    .add("headache", "fracture", 0.1)
    .add("migraine", "fracture", 0.1)
    .build();
```

The builder rejects:
- Duplicate pairs (same pair added twice, regardless of order)
- Scores outside [0, 1]
- Self-pairs (silently ignored, not rejected)

The built `CategoricalTable`'s internal map is unmodifiable (`Map.copyOf` after mirroring).

### Shape parameter semantics

| Function | Parameter | Meaning | Practical guidance |
|----------|-----------|---------|-------------------|
| `GaussianDecay(sigma)` | `sigma` | Width of the bell curve relative to the field range. `sigma=1.0` means the full range equals one standard deviation. | `sigma=0.3`: sharp — values beyond 30% of the range score near zero. `sigma=1.0`: gentle — even values at the range extremes retain ~60% similarity. `sigma=0.5`: moderate default for most domains. |
| `StepDecay(tolerance)` | `tolerance` | Fraction of the field range within which similarity is 1.0. Outside: 0.0. | `tolerance=0.1`: values within 10% of the range are "equal." `tolerance=0.0`: exact match only. `tolerance=0.5`: values within half the range are equal. |
| `ExponentialDecay(decayRate)` | `decayRate` | Controls how quickly similarity drops. Higher = sharper falloff. | `decayRate=1.0`: at full range distance, similarity is `exp(-1) ≈ 0.37`. `decayRate=3.0`: at full range distance, similarity is `exp(-3) ≈ 0.05` — very sharp. `decayRate=0.5`: gentle falloff. |

All numeric decay functions:
- Accept a normalized distance (computed by the scorer from the field's min/max)
- Clamp output to [0, 1]
- Handle zero range as exact-match fallback
- Handle `NumericRange` query values centrally (inside range → distance 0.0, outside → distance from nearest bound / range)

### Interaction with Text.semantic flag

`Text` fields do not carry a `SimilaritySpec`. The `semantic` flag from spec #106 is the
Text-specific mechanism:

| `semantic` | Effect |
|---|---|
| `false` | Exact match (type default, level 3) |
| `true` | Store builds a caller override with `EmbeddingTextSimilarity` (level 1) |

The `semantic` flag works through the **caller override mechanism** (level 1 in the precedence
chain), not through field-attached configuration (level 2). The Qdrant store's
`buildTextOverrides()` checks `field instanceof Text t && t.semantic()` and wires up an
`EmbeddingTextSimilarity` instance that requires a live `EmbeddingModel` — a runtime dependency
that cannot be expressed as declarative data.

This design is intentional: embedding similarity requires runtime capabilities (model loading,
batch precomputation, caching). `SimilaritySpec` is for pure domain configuration that needs
no runtime dependencies.

### Extensibility — taxonomic/hierarchical similarity

The `SimilaritySpec` sealed interface is designed for extension. Issue #107 asks for
"categorical similarity tables (or taxonomic distance)." Flat lookup tables are delivered by
this spec. Hierarchical/taxonomic similarity (e.g., MedDRA SOC → Preferred Term distance) is
a separate concern requiring a hierarchy data structure. A future `SimilaritySpec` variant:

```java
// Future — tracked as a separate issue
record HierarchicalDistance(CategoryHierarchy hierarchy) implements SimilaritySpec {}
```

This extends the sealed interface without changing existing code. The scorer adds one more
`case` branch. Existing `CategoricalTable` specs and all numeric specs are unaffected.

## Impact

### Changes

| File | Change |
|------|--------|
| `SimilaritySpec` | **New file.** Sealed interface with `CategoricalTable`, `GaussianDecay`, `StepDecay`, `ExponentialDecay` + inner `CategoricalTableBuilder` |
| `FeatureField` | **Sealed.** `Categorical` and `Numeric` gain optional `SimilaritySpec` parameter with backward-compatible constructors. New factory method overloads. Sealing enables exhaustive pattern matching in the scorer and all dispatch sites (`CbrQueryTranslator`, `CbrCollectionManager`, `InMemoryCbrCaseMemoryStore`). |
| `CbrSimilarityScorer.localSimilarity()` | Refactored to pattern-match on field type + SimilaritySpec. `numericSimilarity()` refactored to extract `computeNormalizedDistance()`. |
| `CbrSimilarityScorerTest` | New tests for SimilaritySpec resolution and all decay functions |
| `FeatureFieldTest` | New tests for construction with SimilaritySpec, validation |

### No changes

| File | Why |
|------|-----|
| `LocalSimilarityFunction` | Unchanged — still the interface for caller overrides (level 1) |
| `CbrFeatureSchema`, `CbrQuery`, `ScoredCbrCase` | Untouched — schema carries field changes transparently |
| `QdrantCbrCaseMemoryStore` | Caller override (priority 1) still beats SimilaritySpec (priority 2), so `EmbeddingTextSimilarity` retains priority. `buildTextOverrides()` unchanged. |
| `InMemoryCbrCaseMemoryStore` | Passes `Map.of()` overrides — SimilaritySpec on fields works automatically through the scorer |
| `CbrCaseMemoryStoreContractTest` | Existing 28 tests pass unchanged; new contract tests added for SimilaritySpec |
| Module dependencies | Everything stays in `memory-api` |

## Test plan

### Categorical table
- Exact match entries: `("A", "B", 0.8)` → `compute("A", "B") = 0.8`
- Symmetric lookup: `compute("B", "A") = 0.8` (mirrored automatically)
- Unlisted pair → 0.0
- Self-pair → 1.0
- Builder API: chained adds, `build()` returns immutable CategoricalTable
- Empty table: all pairs → 0.0, self-pairs → 1.0 (equivalent to exact match)
- Conflicting symmetric entries: `add("A", "B", 0.8)` then table contains `("B", "A", 0.7)` → throws
- Score outside [0, 1]: `add("A", "B", 1.5)` → throws
- Self-pair in builder: `add("A", "A", 0.9)` → silently ignored
- Case sensitivity: `"Headache" ≠ "headache"` (distinct keys)
- Built table is immutable: modifying the source map after construction has no effect

### Numeric decay functions
- Each function (Gaussian, Step, Exponential) at: exact match (distance=0 → 1.0), mid-range, max distance
- Zero-range fallback: `min == max` → exact match
- NumericRange inside: case value within range → distance 0.0 → similarity 1.0
- NumericRange outside: case value outside → distance from nearest bound → decay applied
- Linear type default: `null` SimilaritySpec → `1.0 - normalizedDistance`
- Parameter validation: `sigma <= 0` → throws, `tolerance` outside [0,1] → throws, `decayRate <= 0` → throws

### SimilaritySpec on FeatureField validation
- `CategoricalTable` on Categorical → accepted
- `GaussianDecay` on Categorical → throws at construction
- `GaussianDecay` on Numeric → accepted
- `CategoricalTable` on Numeric → throws at construction
- Exhaustive switch: adding a new SimilaritySpec variant forces handling in both Categorical and Numeric constructors, and in both scorer dispatch methods (sealed interface — compiler-enforced at all four sites)

### Precedence chain
- Caller override beats SimilaritySpec: field has `GaussianDecay`, caller provides custom fn → custom fn wins
- SimilaritySpec beats type default: field has `StepDecay` → StepDecay used, not linear
- Null SimilaritySpec falls through: field has no spec → type default (linear for Numeric, exact match for Categorical)
- All three levels populated: caller override + SimilaritySpec + type default → caller override wins

### Backward compatibility
- All existing `FeatureField` constructors still work (one-arg Categorical, three-arg Numeric, Text unchanged)
- All existing scorer tests pass unchanged
- Schemas without SimilaritySpec behave identically to current behavior

### Contract tests
- New tests in `CbrCaseMemoryStoreContractTest` for SimilaritySpec through the store
- Categorical table similarity ranking through InMemory and Qdrant stores
- Numeric Gaussian decay ranking through stores
