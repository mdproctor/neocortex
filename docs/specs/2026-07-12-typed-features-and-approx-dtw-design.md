# Typed CBR Feature Values + Approximate DTW

**Issues:** #131 (typed feature values), #137 (approximate DTW)
**Date:** 2026-07-12
**Module:** `memory-api`, `memory-cbr-inmem`, `memory-cbr-embedding`, `memory-cbr-crossencoder`, `memory-qdrant`

## Execution Order

#131 first, then #137. The typed feature representation is the foundation — DTW optimization code should be written once against the clean typed API rather than built on `Map<String, Object>` and then migrated.

---

## Part 1: Typed CBR Feature Values (#131)

### Problem

The entire CBR feature system uses `Map<String, Object>` for feature values — `CbrCase.features()`, `CbrQuery.features()`, observation maps in TimeSeries, HasMatch sub-fields. Schema validation catches type errors at runtime (store/query time), but there is no compile-time safety. The scorer, validator, serializer, and filter engine all cast from `Object` — 10+ call sites across 5 files with unsafe casts.

### Approach: Sealed `FeatureValue` hierarchy

Replace `Map<String, Object>` with `Map<String, FeatureValue>` everywhere. `FeatureValue` is a sealed interface with one variant per data shape.

### FeatureValue type hierarchy

```java
public sealed interface FeatureValue {

    record StringVal(String value) implements FeatureValue {
        public StringVal { Objects.requireNonNull(value, "value"); }
    }

    record NumberVal(double value) implements FeatureValue {}

    record RangeVal(double min, double max) implements FeatureValue {
        public RangeVal {
            if (min > max) throw new IllegalArgumentException(
                "min must be <= max, got min=" + min + " max=" + max);
        }
    }

    record StringListVal(List<String> values) implements FeatureValue {
        public StringListVal {
            Objects.requireNonNull(values, "values");
            values = List.copyOf(values);
        }
    }

    record NumberListVal(List<Double> values) implements FeatureValue {
        public NumberListVal {
            Objects.requireNonNull(values, "values");
            values = List.copyOf(values);
        }
    }

    record StructVal(Map<String, FeatureValue> fields) implements FeatureValue {
        public StructVal {
            Objects.requireNonNull(fields, "fields");
            fields = Map.copyOf(fields);
        }
    }

    record StructListVal(List<Map<String, FeatureValue>> items) implements FeatureValue {
        public StructListVal {
            Objects.requireNonNull(items, "items");
            items = items.stream().map(Map::copyOf).toList();
        }
    }

    // Static factories
    static StringVal string(String value) { return new StringVal(value); }
    static NumberVal number(double value) { return new NumberVal(value); }
    static RangeVal range(double min, double max) { return new RangeVal(min, max); }
    static StringListVal stringList(String... values) { return new StringListVal(List.of(values)); }
    static StringListVal stringList(List<String> values) { return new StringListVal(values); }
    static NumberListVal numberList(Double... values) { return new NumberListVal(List.of(values)); }
    static NumberListVal numberList(List<Double> values) { return new NumberListVal(values); }
    static StructVal struct(Map<String, FeatureValue> fields) { return new StructVal(fields); }
    static StructListVal structList(List<Map<String, FeatureValue>> items) {
        return new StructListVal(items);
    }
    static StructListVal structList(Map<String, FeatureValue>... items) {
        return new StructListVal(List.of(items));
    }
}
```

### Variant-to-field mapping

| FeatureValue variant | Used by FeatureField type(s) | Store | Query |
|---------------------|------------------------------|-------|-------|
| `StringVal` | Categorical, Text | yes | yes |
| `NumberVal` | Numeric | yes | yes |
| `RangeVal` | Numeric (range queries) | no | yes |
| `StringListVal` | CategoricalList, DiscreteSequence | yes | DiscreteSequence only |
| `NumberListVal` | NumericList | yes | no (filter-only) |
| `StructVal` | NestedObject | yes | no (filter-only) |
| `StructListVal` | ObjectList, TimeSeries | yes | TimeSeries only |

The **schema** (FeatureField) tells you the semantics (Categorical vs Text, ObjectList vs TimeSeries). The **value** (FeatureValue) tells you the data shape. Together they're unambiguous. The scorer dispatches on FeatureField type first, then pattern-matches the FeatureValue — zero casts.

### API changes

#### CbrCase

```java
public interface CbrCase {
    String cbrType();
    String problem();
    String solution();
    String outcome();
    Double confidence();
    default Map<String, FeatureValue> features() { return Map.of(); }
}
```

#### FeatureVectorCbrCase

```java
public record FeatureVectorCbrCase(String problem, String solution,
                                    String outcome, Double confidence,
                                    Map<String, FeatureValue> features) implements CbrCase { ... }
```

#### PlanCbrCase

```java
public record PlanCbrCase(String problem, String solution,
                          String outcome, Double confidence,
                          Map<String, FeatureValue> features,
                          List<PlanTrace> planTrace) implements CbrCase { ... }
```

#### CbrQuery

```java
public record CbrQuery(
    String tenantId,
    MemoryDomain domain,
    String caseType,
    Map<String, FeatureValue> features,
    Map<String, CbrFilter> filters,
    Map<String, Double> weights,
    ...
) { ... }
```

The `CbrQuery.of()` factory and `with*()` methods update accordingly.

#### CbrFilter.HasMatch

```java
record HasMatch(Map<String, FeatureValue> subFields) implements CbrFilter { ... }
```

Replaces `Map<String, Object>`. Sub-field values use `StringVal`, `NumberVal`, `RangeVal` (flat types only, matching the inner-field constraint).

#### NumericRange

Stays unchanged — used only by `CbrFilter.ContainsRange`. `RangeVal` is the FeatureValue equivalent for feature maps and HasMatch sub-fields.

### CbrSimilarityScorer transformation

Before (unsafe casts):
```java
double caseNum = ((Number) caseVal).doubleValue();
String q = (String) queryVal;
List<Map<String, Object>> obs = (List<Map<String, Object>>) queryVal;
```

After (pattern matching on sealed hierarchy):
```java
// Numeric
case FeatureField.Numeric n -> {
    if (queryVal instanceof FeatureValue.RangeVal rv) {
        // range query: compute distance from case value to range
    } else if (queryVal instanceof FeatureValue.NumberVal qn
               && caseVal instanceof FeatureValue.NumberVal cn) {
        // point query: compute distance between values
    }
}

// Categorical
case FeatureField.Categorical c -> {
    if (queryVal instanceof FeatureValue.StringVal qs
        && caseVal instanceof FeatureValue.StringVal cs) {
        // exact match or table lookup
    }
}

// TimeSeries
case FeatureField.TimeSeries ts -> {
    if (queryVal instanceof FeatureValue.StructListVal qObs
        && caseVal instanceof FeatureValue.StructListVal cObs) {
        // DTW
    }
}

// DiscreteSequence
case FeatureField.DiscreteSequence ds -> {
    if (queryVal instanceof FeatureValue.StringListVal qSeq
        && caseVal instanceof FeatureValue.StringListVal cSeq) {
        // edit distance on qSeq.values(), cSeq.values()
    }
}
```

### CbrFeatureValidator transformation

Validates that FeatureValue variant matches FeatureField type:

```java
case FeatureField.Categorical c -> {
    if (!(value instanceof FeatureValue.StringVal))
        throw new IllegalArgumentException(
            "Categorical field '" + name + "' requires StringVal, got: "
            + value.getClass().getSimpleName());
}
case FeatureField.Numeric n -> {
    if (!(value instanceof FeatureValue.NumberVal)
        && !(value instanceof FeatureValue.RangeVal))
        throw new IllegalArgumentException(...);
}
case FeatureField.TimeSeries ts -> {
    if (!(value instanceof FeatureValue.StructListVal sl))
        throw new IllegalArgumentException(...);
    // validate each observation map against inner fields
    validateTimeSeriesObservations(name, sl.items(), ts);
}
```

Query validation additionally accepts `RangeVal` for Numeric fields. Store validation rejects `RangeVal` (ranges are query-only).

### DtwSimilarity signature change

```java
public static DtwResult compute(List<Map<String, FeatureValue>> query,
                                List<Map<String, FeatureValue>> caseSeq,
                                FeatureField.TimeSeries schema) { ... }

public static DtwResult compute(List<Map<String, FeatureValue>> query,
                                List<Map<String, FeatureValue>> caseSeq,
                                FeatureField.TimeSeries schema,
                                WarpingConstraint constraint) { ... }
```

`observationDistance` changes from `(Number) a.get(f.name())` to pattern matching:

```java
FeatureValue aVal = a.get(f.name());
FeatureValue bVal = b.get(f.name());
if (aVal instanceof FeatureValue.NumberVal aN && bVal instanceof FeatureValue.NumberVal bN) {
    double diff = (aN.value() - bN.value()) / range;
    sumSq += diff * diff;
}
```

### LocalSimilarityFunction

```java
@FunctionalInterface
public interface LocalSimilarityFunction {
    double compute(FeatureValue queryValue, FeatureValue caseValue);

    LocalSimilarityFunction EXACT_MATCH = (q, c) -> q.equals(c) ? 1.0 : 0.0;
}
```

Changes from `Object` to `FeatureValue` parameters. `EmbeddingTextSimilarity` (in `memory-cbr-embedding`) updates to pattern match on `StringVal`:

```java
if (queryValue instanceof FeatureValue.StringVal qs
    && caseValue instanceof FeatureValue.StringVal cs) {
    // compute cosine similarity between embeddings of qs.value() and cs.value()
}
```

### EditDistanceSimilarity

No signature change — stays `List<String>`. The scorer extracts `StringListVal.values()` before calling.

### Qdrant serialization (CbrPointBuilder)

**`_features_json` payload** — raw JSON format unchanged (`{"race":"Terran","mmr":3500}`).

Serialize: switch on FeatureValue variant → raw JSON value:
- `StringVal` → JSON string
- `NumberVal` → JSON number
- `StringListVal` → JSON array of strings
- `NumberListVal` → JSON array of numbers
- `StructVal` → JSON object
- `StructListVal` → JSON array of objects

Deserialize (in `QdrantCbrCaseMemoryStore.reconstructCase()`): raw JSON + schema → FeatureValue. The schema determines which variant to construct from each JSON value.

**Per-field payload keys** (`f_<name>`) — switch on FeatureValue variant:
- `StringVal` → `ValueFactory.value(sv.value())`
- `NumberVal` → `ValueFactory.value(nv.value())`
- `StringListVal` → `toListValue(sl.values())`
- `StructVal` → `toStructValue(...)` adapted for FeatureValue
- `StructListVal` → `toListValue(...)` adapted for FeatureValue

No Qdrant migration needed — payload format is unchanged.

### InMemoryCbrCaseMemoryStore filter matching

`matchesSingleFilter()` updates:
```java
case CbrFilter.Contains c ->
    storedValue instanceof FeatureValue.StringListVal sl
    && sl.values().contains(c.value());
case CbrFilter.ContainsRange cr ->
    storedValue instanceof FeatureValue.NumberListVal nl
    && nl.values().stream().anyMatch(n ->
        n >= cr.range().min() && n <= cr.range().max());
```

`allSubFieldsMatch()` for HasMatch — pattern match on FeatureValue variants instead of instanceof Number/String/NumericRange.

### Cross-repo impact

`CbrRetrievalService` in the engine repo uses `c.features()` (1 call site). File a GitHub issue on casehubio/engine for the type migration.

---

## Part 2: Approximate DTW (#137)

### Problem

Full DTW is O(n×m) per candidate. With >5000 candidates and sequences of ~50 time steps, the cumulative cost becomes a bottleneck. Two well-established optimizations apply.

### Component 1: Early abandonment (all constraint types)

New overload on `DtwSimilarity`:

```java
public static DtwResult compute(List<Map<String, FeatureValue>> query,
                                List<Map<String, FeatureValue>> caseSeq,
                                FeatureField.TimeSeries schema,
                                WarpingConstraint constraint,
                                double abandonCostThreshold)
```

During the DP loop, track the minimum cost value in each row. If `rowMin > abandonCostThreshold`, the final DTW cost is guaranteed to exceed the threshold (costs only accumulate along alignment paths — they never decrease). Return `DtwResult(0.0, List.of())` immediately.

The existing 4-arg and 3-arg `compute()` overloads delegate to the 5-arg with `Double.POSITIVE_INFINITY` (no abandonment — preserving current behavior).

**Threshold conversion** (caller's responsibility):

```
abandonCostThreshold = (1.0 / minScore - 1.0) * max(queryLength, caseLength)
```

Where `minScore` is the k-th best DTW score seen so far (or the query's `minSimilarity`).

### Component 2: LB_Keogh lower-bound pruning (SakoeChibaBand only)

New `LbKeogh` utility class in `memory-api`:

```java
public final class LbKeogh {

    public record Envelope(double[][] upper, double[][] lower,
                           int length, int dimensions) {
        // upper[i][d] = max normalized value at time step i, dimension d
        // lower[i][d] = min normalized value at time step i, dimension d
    }

    public static Envelope computeEnvelope(
            List<Map<String, FeatureValue>> sequence,
            FeatureField.TimeSeries schema,
            int windowSize) { ... }

    public static double lowerBound(
            List<Map<String, FeatureValue>> query,
            Envelope caseEnvelope,
            FeatureField.TimeSeries schema) { ... }
}
```

#### Envelope computation — O(m × D)

For a case sequence of length m with D scorable numeric dimensions:

1. Extract scorable numeric fields from schema (same as `DtwSimilarity.scorableNumericFields()`)
2. Normalize each observation value: `v_norm = v / range` (matching DTW's per-field range normalization)
3. Adjust window: `w = max(windowSize, |queryLength - m|)` (ensures feasible alignment)
4. For each time step i ∈ [0, m) and dimension d ∈ [0, D):
   - `upper[i][d] = max(v_norm[j][d])` for j ∈ [max(0, i-w), min(m-1, i+w)]
   - `lower[i][d] = min(v_norm[j][d])` for j ∈ [max(0, i-w), min(m-1, i+w)]

#### Lower bound computation — O(n × D)

For a query of length n against a case envelope of length m:

For each query time step i ∈ [0, min(n, m)):
1. Normalize query values: `q_norm[d] = q[i][d] / range[d]`
2. For each dimension d:
   - If `q_norm[d] > upper[i][d]`: `contrib_d = (q_norm[d] - upper[i][d])²`
   - If `q_norm[d] < lower[i][d]`: `contrib_d = (lower[i][d] - q_norm[d])²`
   - Else: `contrib_d = 0`
3. `stepDist = sqrt(sum_d contrib_d)` — matches DTW's per-step Euclidean distance
4. Accumulate: `lb += stepDist`

**Property:** `lowerBound(Q, env(C)) ≤ DTW(Q, C)` — valid lower bound.

If `lowerBound ≥ abandonCostThreshold` → skip full DTW entirely.

#### Constraint applicability

| WarpingConstraint | LB_Keogh | Early abandonment |
|-------------------|----------|-------------------|
| SakoeChibaBand | yes | yes |
| ItakuraParallelogram | no (position-dependent envelope) | yes |
| Unconstrained | no (trivially loose) | yes |

### Integration with retrieval

The store's retrieval loop orchestrates both optimizations for TimeSeries fields.

For candidates with TimeSeries features scored via SakoeChibaBand:

1. Maintain `abandonCostThreshold` from the k-th best DTW score (or `minSimilarity`)
2. **LB_Keogh screen** — compute `LbKeogh.lowerBound(queryTS, candidateEnvelope, schema)` — O(n). If `lb ≥ abandonCostThreshold` → skip candidate's DTW.
3. **Full DTW with abandonment** — compute `DtwSimilarity.compute(query, case, schema, constraint, abandonCostThreshold)` — O(n×m) worst case, short-circuits in practice.

As better candidates are found, the threshold tightens, pruning more aggressively.

For candidates with non-SakoeChibaBand TimeSeries fields: skip step 2, apply step 3 only.

**Envelope caching:** computed on-the-fly at query time. O(m×D) per candidate — trivial relative to full DTW. No precomputation or storage overhead in v1.

### Integration scope

LB_Keogh + early abandonment integrate into `InMemoryCbrCaseMemoryStore.retrieveSimilar()` and `CbrSimilarityScorer` (pass-through of abandon threshold for DTW fields).

`QdrantCbrCaseMemoryStore` scoring happens client-side via the scorer after Qdrant retrieval — the same optimization applies transparently.

---

## Implementation phases

### Phase 1: FeatureValue type + API migration (#131)
- New `FeatureValue.java` sealed interface
- `CbrCase`, `FeatureVectorCbrCase`, `PlanCbrCase` — features type change
- `CbrQuery` — features type change
- `CbrFilter.HasMatch` — subFields type change

### Phase 2: Validator + scorer (#131)
- `CbrFeatureValidator` — validate FeatureValue variants
- `CbrSimilarityScorer` — pattern match, eliminate all casts
- `LocalSimilarityFunction` — Object → FeatureValue params
- `DtwSimilarity` — observation type change
- Tests for all of the above

### Phase 3: Store backends + embedding (#131)
- `InMemoryCbrCaseMemoryStore` — filter matching
- `QdrantCbrCaseMemoryStore` — reconstruction, scoring
- `CbrPointBuilder` — serialization
- `CbrMemorySerializer` — serialization
- `CbrQueryTranslator` — HasMatch FeatureValue handling
- `EmbeddingTextSimilarity` — LocalSimilarityFunction FeatureValue params
- Contract test migration (111 tests)

### Phase 4: Early abandonment (#137)
- `DtwSimilarity.compute()` — 5-arg overload with abandon threshold
- Row-minimum tracking in DP loop
- Tests

### Phase 5: LB_Keogh (#137)
- New `LbKeogh.java` — envelope computation + lower bound
- Tests

### Phase 6: Integration (#137)
- `InMemoryCbrCaseMemoryStore` — LB_Keogh + abandon threshold in retrieval loop
- `CbrSimilarityScorer` — pass-through abandon threshold for DTW fields
- Integration tests + benchmark

### Phase 7: Cross-repo
- File GitHub issue on casehubio/engine for `CbrRetrievalService` migration
