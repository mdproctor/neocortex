# Sequence Similarity Refinements — Design Spec

**Issue:** casehubio/neocortex#92
**Date:** 2026-07-11
**Status:** Draft
**Prerequisite:** casehubio/neocortex#91 (temporal case representation — delivered)

## Problem

The core DTW and edit distance algorithms delivered in #91 are functional but lack three capabilities the acceptance criteria require:

1. **No alignment path** — DTW computes similarity but discards the cost matrix backtrace. Consumers cannot explain *why* two sequences matched ("your economy at minute 5 aligned with their economy at minute 7").
2. **No windowing** — full O(n×m) DTW fills every cell. For long sequences (hundreds of observations), a Sakoe-Chiba band constraint reduces this to O(n×w) without sacrificing alignment quality when temporal warping is bounded.
3. **No weighted substitution** — edit distance uses uniform costs (substitute any label for any other = cost 1). Domain-specific CBR needs graded costs: `MACRO→AGGRESSIVE` is a bigger shift than `MACRO→DEFENSIVE`.

Additionally, neither `TimeSeries` nor `DiscreteSequence` carry a `SimilaritySpec` — the schema-level configuration mechanism that `Categorical` and `Numeric` already use. There is no way to attach DTW windowing or edit distance cost tables to the schema.

## Decision

Four changes, all in `memory-api` (Tier 1 pure Java):

1. **Alignment path extraction** — DTW and edit distance always produce alignment paths via cost-matrix backtracing. Zero meaningful overhead (O(n+m) on O(n×m) computation).
2. **Windowed DTW** — Sakoe-Chiba band support, configured via new `DtwSpec` on `SimilaritySpec`.
3. **Weighted edit distance** — domain-specific substitution similarities, configured via new `EditDistanceSpec` on `SimilaritySpec`.
4. **SimilaritySpec on temporal fields** — `TimeSeries` and `DiscreteSequence` gain optional `SimilaritySpec` fields, following the pattern `Categorical` and `Numeric` already use.

No new modules. No SPI changes. No CbrQuery changes.

### Alternatives considered

**Separate `explain()` method that recomputes DTW for top-K results:** Rejected — backtracing is O(n+m) on a matrix that's already O(n×m) to fill. Always producing the path avoids recomputation and keeps the code simpler. The allocation overhead (one `List<AlignmentPair>` per comparison) is negligible relative to the cost matrix itself.

**Itakura parallelogram constraint as alternative to Sakoe-Chiba:** Deferred — Sakoe-Chiba is simpler (single parameter), better understood, and sufficient for the current use cases. Can be added as another `SimilaritySpec` variant later.

**Embedding `CategoricalTable` directly in `EditDistanceSpec`:** Rejected — `CategoricalTable` is a `SimilaritySpec` variant. Embedding one spec inside another creates confusing nesting. `EditDistanceSpec` carries its own `Map<String, Map<String, Double>>` with the same mirror-validation semantics.

**Full `SimilarityBreakdown` return type from the scorer:** Deferred to #84 — producing the alignment data (this issue) is separate from surfacing it through scorer → ScoredCbrCase → ExplanationRenderer (#84's scope).

## Design

### 1. Alignment path data structures

Three new records in `memory-api`:

```java
public record AlignmentPair(int queryIndex, int caseIndex) {}
```

A single step in a DTW alignment path. Each pair means "query observation i aligned with case observation j." In DTW, every step aligns two elements — there are no gaps.

```java
public record DtwResult(double score, List<AlignmentPair> alignment) {}
```

```java
public enum EditOp { MATCH, SUBSTITUTE, INSERT, DELETE }
```

The operation type for each step in an edit distance alignment. The algorithm knows the operation during backtracing — encoding it avoids pushing algorithmic knowledge to consumers.

```java
public record EditStep(int queryIndex, int caseIndex, EditOp operation) {}
```

A single step in an edit distance alignment path, with its operation explicitly tagged. Index semantics vary by operation:

- **MATCH / SUBSTITUTE**: `queryIndex` and `caseIndex` identify the aligned elements — `query[queryIndex]` corresponds to `case[caseIndex]`.
- **DELETE**: `queryIndex` identifies the deleted query element (`query[queryIndex]`). `caseIndex` is `-1` — no case element is involved in a deletion.
- **INSERT**: `caseIndex` identifies the inserted case element (`case[caseIndex]`). `queryIndex` is `-1` — no query element is involved in an insertion.

The `-1` sentinel follows Java convention (`String.indexOf()`, `Collections.binarySearch()`). Consumers use the `EditOp` discriminator to determine which indices are meaningful.

```java
public record EditDistanceResult(double score, List<EditStep> alignment) {}
```

Separate result types (not a shared `SequenceResult`) because they carry different semantic interpretations: DTW alignment uses `AlignmentPair` (all steps are "aligned with" — one operation type), edit distance uses `EditStep` (four distinct operation types). Future extensions may further diverge (e.g., `DtwResult` might gain warp distance per step).

### 2. DtwSimilarity changes

**Signature change:**

```java
// Before
public static double compute(List<Map<String, Object>> query,
                              List<Map<String, Object>> caseSeq,
                              FeatureField.TimeSeries schema)

// After
public static DtwResult compute(List<Map<String, Object>> query,
                                 List<Map<String, Object>> caseSeq,
                                 FeatureField.TimeSeries schema)
```

**Windowed DTW support:**

```java
public static DtwResult compute(List<Map<String, Object>> query,
                                 List<Map<String, Object>> caseSeq,
                                 FeatureField.TimeSeries schema,
                                 Integer windowSize)
```

When `windowSize` is non-null, cells where `|i - j| > windowSize` are set to `Double.MAX_VALUE` and skipped. The window is clamped to `max(windowSize, |n - m|)` to guarantee a valid path exists when sequences differ in length.

When `windowSize` is null, the full matrix is computed (current behavior).

**Alignment path extraction:**

After filling the cost matrix, backtrace from `(n, m)` to `(0, 0)`:

```
path = []
i = n, j = m
while i > 0 or j > 0:
    path.add(AlignmentPair(i-1, j-1))
    pick predecessor with minimum cost among:
        (i-1, j-1), (i-1, j), (i, j-1)
    — respecting window bounds when windowed
reverse(path)
```

The path is always produced. Edge cases:
- Both empty → empty path, score 1.0
- One empty → empty path, score 0.0

### 3. EditDistanceSimilarity changes

**Signature change:**

```java
// Before
public static double compute(List<String> query, List<String> caseSeq)

// After
public static EditDistanceResult compute(List<String> query, List<String> caseSeq)
```

**Weighted substitution support:**

```java
public static EditDistanceResult compute(List<String> query, List<String> caseSeq,
                                          Map<String, Map<String, Double>> substitutionSimilarities)
```

The DP table is always `double[][]`, even for uniform costs (where match = 0.0, substitution = 1.0). At the scales involved (max 50×50 = 2,500 cells), the performance difference between `int` and `double` is negligible, and a single code path eliminates conditional branching.

When `substitutionSimilarities` is non-null and non-empty:
- Substitution cost: `1.0 - similarity(a, b)` where similarity is looked up from the table (default 0.0 for unspecified pairs, meaning cost 1.0 — same as uniform Levenshtein)
- Insert/delete costs remain 1.0
- Similarity formula: `1.0 - (weightedEditDistance / max(n, m))`

When `substitutionSimilarities` is null or empty, uniform costs apply: `query[i].equals(case[j]) ? 0.0 : 1.0`.

The substitution similarities map uses the same mirror semantics as `CategoricalTable`: `similarity(A, B) == similarity(B, A)`. Validated at `EditDistanceSpec` construction time.

**Alignment path extraction:**

Backtrace from `(n, m)` to `(0, 0)`, tagging each step with its `EditOp`:

```
path = []
i = n, j = m
while i > 0 or j > 0:
    if i > 0 and j > 0 and diagonal is best predecessor:
        op = query[i-1] == case[j-1] ? MATCH : SUBSTITUTE
        path.add(EditStep(i-1, j-1, op))
        i--, j--
    else if i > 0 and up is best (or j == 0):
        path.add(EditStep(i-1, -1, DELETE))
        i--
    else:
        path.add(EditStep(-1, j-1, INSERT))
        j--
reverse(path)
```

Unlike DTW (where every step aligns two elements), edit distance DELETE and INSERT steps involve only one sequence. The uninvolved index is `-1` — see §1 for full index semantics.

Edge cases:
- Both empty → empty path, score 1.0 (early return — avoids division by zero in `max(n, m)`)
- One empty → **non-empty path**, score 0.0. When case is empty, the path consists entirely of DELETE steps (`EditStep(0, -1, DELETE)`, ..., `EditStep(n-1, -1, DELETE)`). When query is empty, the path consists entirely of INSERT steps (`EditStep(-1, 0, INSERT)`, ..., `EditStep(-1, m-1, INSERT)`). The backtracing pseudocode above handles this naturally (the `j == 0` and fallback branches fire exclusively). **This differs from DTW**, where one-empty produces an empty path because DTW alignment is undefined when one sequence has no elements. For edit distance, the transformation is well-defined — every element in the non-empty sequence is inserted or deleted.

The current `EditDistanceSimilarity.compute()` early-returns `0.0` for one-empty cases. With the return type changing to `EditDistanceResult`, this early return is removed — the DP + backtracing produces the correct non-empty path. The both-empty early return is retained.

### 4. SimilaritySpec — new sealed permits

```java
public sealed interface SimilaritySpec permits
    CategoricalTable, GaussianDecay, StepDecay, ExponentialDecay,
    DtwSpec, EditDistanceSpec {

    // ... existing permits unchanged ...

    record DtwSpec(Integer windowSize) implements SimilaritySpec {
        public DtwSpec {
            if (windowSize != null && windowSize < 1)
                throw new IllegalArgumentException("windowSize must be >= 1, got: " + windowSize);
        }
    }

    record EditDistanceSpec(Map<String, Map<String, Double>> substitutionSimilarities)
            implements SimilaritySpec {
        public EditDistanceSpec {
            Objects.requireNonNull(substitutionSimilarities, "substitutionSimilarities");
            substitutionSimilarities = mirrorAndValidate(substitutionSimilarities);
        }
        // mirrorAndValidate: same logic as CategoricalTable — scores in [0,1],
        // symmetric, self-pairs ignored, conflict detection
    }
}
```

**Extensibility rationale:** `DtwSpec(null)` (no window) and `EditDistanceSpec(Map.of())` (no custom costs) produce the same behavior as having no spec at all today. The distinction is intentional: these specs are extension points. `DtwSpec` will gain additional fields — step pattern constraints (#138 Itakura parallelogram), distance functions, normalization modes. `EditDistanceSpec` will gain insert/delete cost configuration (#139). A `DtwSpec(null)` is "DTW with all defaults, explicitly configured" — semantically distinct from "no spec" because it declares the intent to use DTW-specific configuration, even when all parameters are currently at their defaults.

`EditDistanceSpec.mirrorAndValidate()` reuses the same validation pattern as `CategoricalTable.mirrorAndValidate()` — extract the shared logic into a `private static` method on `SimilaritySpec` (e.g., `validateAndMirrorSimilarityMap`) that both records call from their compact constructors. The validation rules are identical: scores must be finite and in [0,1] (reject `NaN` and `Infinity`), self-pairs ignored, asymmetric entries mirrored, conflicting scores rejected. The score check uses `Double.isNaN(score) || score < 0.0 || score > 1.0` — the explicit NaN check is required because `NaN < 0.0` and `NaN > 1.0` both evaluate to `false` in IEEE 754. This also fixes a pre-existing bug in `CategoricalTable.mirrorAndValidate()` where NaN scores passed validation silently.

### 5. FeatureField — temporal fields gain SimilaritySpec

**TimeSeries:**

```java
record TimeSeries(String name, List<FeatureField> innerFields,
                  String timestampField, SimilaritySpec similaritySpec) implements FeatureField {
    public TimeSeries {
        // ... existing validation unchanged ...
        if (similaritySpec != null) {
            switch (similaritySpec) {
                case SimilaritySpec.DtwSpec ds -> {}
                case SimilaritySpec.CategoricalTable ct -> throw ...
                case SimilaritySpec.GaussianDecay gd -> throw ...
                case SimilaritySpec.StepDecay sd -> throw ...
                case SimilaritySpec.ExponentialDecay ed -> throw ...
                case SimilaritySpec.EditDistanceSpec es -> throw ...
            }
        }
    }
}
```

**DiscreteSequence:**

```java
record DiscreteSequence(String name, SimilaritySpec similaritySpec) implements FeatureField {
    public DiscreteSequence {
        Objects.requireNonNull(name, "name");
        if (similaritySpec != null) {
            switch (similaritySpec) {
                case SimilaritySpec.EditDistanceSpec es -> {}
                case SimilaritySpec.CategoricalTable ct -> throw ...
                case SimilaritySpec.GaussianDecay gd -> throw ...
                case SimilaritySpec.StepDecay sd -> throw ...
                case SimilaritySpec.ExponentialDecay ed -> throw ...
                case SimilaritySpec.DtwSpec ds -> throw ...
            }
        }
    }
}
```

**Factory methods — backward-compatible overloads:**

```java
// Existing (delegate to new canonical constructor with null spec)
static FeatureField timeSeries(String name, String timestampField, FeatureField... innerFields)
static FeatureField discreteSequence(String name)

// New
static FeatureField timeSeries(String name, String timestampField,
                                SimilaritySpec spec, FeatureField... innerFields)
static FeatureField discreteSequence(String name, SimilaritySpec spec)
```

### 6. CbrSimilarityScorer integration

The scorer's existing private helper methods (`dtwSimilarity()` and `editDistanceSimilarity()`) are updated to read `SimilaritySpec` from the field and pass configuration to the algorithms:

```java
@SuppressWarnings("unchecked")
private static double dtwSimilarity(FeatureField.TimeSeries ts,
                                     Object queryVal, Object caseVal) {
    Integer windowSize = ts.similaritySpec() instanceof SimilaritySpec.DtwSpec ds
        ? ds.windowSize() : null;
    return DtwSimilarity.compute(
        (List<Map<String, Object>>) queryVal,
        (List<Map<String, Object>>) caseVal, ts, windowSize).score();
}

@SuppressWarnings("unchecked")
private static double editDistanceSimilarity(FeatureField.DiscreteSequence ds,
                                              Object queryVal, Object caseVal) {
    Map<String, Map<String, Double>> subSim = ds.similaritySpec()
        instanceof SimilaritySpec.EditDistanceSpec es
        ? es.substitutionSimilarities() : null;
    return EditDistanceSimilarity.compute(
        (List<String>) queryVal, (List<String>) caseVal, subSim).score();
}
```

Note: `editDistanceSimilarity()` gains a `DiscreteSequence` parameter (currently it only receives the values). The `localSimilarity()` switch is updated to pass the field: `case FeatureField.DiscreteSequence ds -> editDistanceSimilarity(ds, queryVal, caseVal)`.

Three-level precedence is preserved — caller-provided `LocalSimilarityFunction` override still bypasses the spec entirely.

### 7. Exhaustive switch sites

Adding two new `SimilaritySpec` permits and modifying two `FeatureField` records triggers compiler errors at all exhaustive switch sites. Every site must handle the new cases.

**SimilaritySpec switches (add DtwSpec + EditDistanceSpec cases):**

| File | Method | Change |
|------|--------|--------|
| `FeatureField.Categorical` | constructor | Reject DtwSpec, EditDistanceSpec |
| `FeatureField.Numeric` | constructor | Reject DtwSpec, EditDistanceSpec |
| `FeatureField.TimeSeries` | constructor | Accept DtwSpec, reject others |
| `FeatureField.DiscreteSequence` | constructor | Accept EditDistanceSpec, reject others |
| `CbrSimilarityScorer` | `categoricalSimilarity()` | Reject DtwSpec, EditDistanceSpec |
| `CbrSimilarityScorer` | `numericSimilarity()` | Reject DtwSpec, EditDistanceSpec |

**FeatureField switches (TimeSeries/DiscreteSequence record changes):**

No new sealed permits on `FeatureField` — `TimeSeries` and `DiscreteSequence` already exist. Record parameter changes do not affect switch exhaustiveness. No FeatureField switch sites need updating.

### 8. Performance

For 1000 cases × 50 time steps × 5 numeric fields:
- Full DTW: 50×50 = 2,500 cells × 1000 candidates = 2.5M cells. Estimate: < 50ms.
- Windowed DTW (w=10): 50×10 = 500 cells × 1000 candidates = 500K cells. Estimate: < 10ms.
- Backtrace: ~100 steps per candidate × 1000 = 100K steps. Estimate: < 1ms.
- Allocation: one `List<AlignmentPair>` (~100 elements) per candidate × 1000 = 100K small objects. Short-lived, GC handles trivially.

The performance criterion (1000×50 < 500ms) is met with margin.

**Normalization note:** DTW uses asymptotic normalization (`1.0 / (1.0 + normalizedDistance)`) while edit distance uses linear normalization (`1.0 - editDistance / max(n, m)`). The formulas differ because the underlying distance spaces have different properties — DTW distance is unbounded (sum of per-observation Euclidean distances can exceed sequence length), requiring an asymptotic formula that maps [0, ∞) → (0, 1]. Edit distance is bounded by `max(n, m)`, so linear normalization correctly maps [0, max(n,m)] → [0, 1]. Both formulas are monotonically decreasing with respect to actual distance, producing valid similarity scores in [0, 1]. These normalization formulas were established in #91 and are unchanged by this spec.

## Module impact

| Module | Changes |
|--------|---------|
| `memory-api` | `SimilaritySpec` +2 sealed permits (DtwSpec, EditDistanceSpec). `FeatureField.TimeSeries` +SimilaritySpec field. `FeatureField.DiscreteSequence` +SimilaritySpec field. New factory overloads. `DtwSimilarity` → returns `DtwResult`, gains windowed support. `EditDistanceSimilarity` → returns `EditDistanceResult`, gains weighted substitution, always `double[][]` DP table. `CbrSimilarityScorer` → reads spec from temporal fields, passes to algorithms; `categoricalSimilarity()` and `numericSimilarity()` gain reject cases for DtwSpec/EditDistanceSpec. New types: `AlignmentPair`, `DtwResult`, `EditOp`, `EditStep`, `EditDistanceResult`. |
| `memory-api` tests | DTW: windowed computation, alignment path correctness, window clamping for unequal lengths, edge cases. EditDistance: weighted substitution costs, fractional distances, alignment path correctness. SimilaritySpec: DtwSpec/EditDistanceSpec validation. FeatureField: spec acceptance/rejection on temporal fields. |
| `memory-testing` | Contract test additions: windowed DTW retrieval, weighted edit distance retrieval, SimilaritySpec on temporal fields affects ranking. |

No changes to: `memory-cbr-inmem`, `memory-qdrant`, `memory-cbr-embedding`, `memory-cbr-crossencoder`, `CbrQuery`, `ScoredCbrCase`, `CbrCaseMemoryStore` SPI.

## Out of scope

- **`CbrSimilarityScorer.scoreDetailed()` → `SimilarityBreakdown`** — surfacing per-field breakdowns + alignment paths through the scorer to consumers. Belongs with #84 (ExplanationRenderer SPI).
- **Approximate DTW (LB_Keogh lower bound + early abandonment)** — scalability optimization for >5000 candidates. Not needed at current scale. → #137
- **Itakura parallelogram constraint** — alternative to Sakoe-Chiba with different warping shape. Can be added as another DtwSpec variant or a separate spec. → #138
- **Sequence embeddings** — encoding sequences into fixed-length vectors for Qdrant-native search. Future enhancement, ties to ONNX infrastructure (#77).
- **Insert/delete cost configuration for edit distance** — currently fixed at 1.0. Domain need unclear. Can be added to EditDistanceSpec later. → #139

## Contract tests

~15 new tests in `CbrCaseMemoryStoreContractTest`:

**Windowed DTW:**
- `temporal_timeSeries_windowedDtw_similarResult`
- `temporal_timeSeries_windowedDtw_veryNarrowWindow_lowerSimilarity`
- `temporal_timeSeries_windowedDtw_windowClampedForUnequalLengths`

**Weighted edit distance:**
- `temporal_discreteSequence_weightedSubstitution_closerLabelsHigherScore`
- `temporal_discreteSequence_weightedSubstitution_unspecifiedPairsUniformCost`
- `temporal_discreteSequence_weightedSubstitution_symmetricCosts`

**SimilaritySpec on temporal fields:**
- `temporal_timeSeries_dtwSpec_acceptedBySchema`
- `temporal_timeSeries_editDistanceSpec_rejectedBySchema`
- `temporal_discreteSequence_editDistanceSpec_acceptedBySchema`
- `temporal_discreteSequence_dtwSpec_rejectedBySchema`
- `temporal_timeSeries_dtwSpec_affectsRetrieval`
- `temporal_discreteSequence_editDistanceSpec_affectsRetrieval`

Unit tests in `memory-api`:

**DtwSimilarityTest additions:**
- `windowedDtw_sameResultAsFullWhenWindowLargerThanSequence`
- `windowedDtw_constrainedAlignment`
- `windowedDtw_windowClampedForUnequalLengths`
- `alignmentPath_identicalSequences_diagonal`
- `alignmentPath_stretchedAlignment`
- `alignmentPath_lengthBounds`

**EditDistanceSimilarityTest additions:**
- `weightedSubstitution_closerLabelsLowerCost`
- `weightedSubstitution_symmetricLookup`
- `weightedSubstitution_unspecifiedPairsDefaultToUniform`
- `weightedSubstitution_fractionalDistance`
- `alignmentPath_identicalSequences_allDiagonal`
- `alignmentPath_insertionAndDeletion`
- `alignmentPath_substitution`
- `weightedSubstitution_changesAlignmentPath`
- `alignmentPath_deleteAtStart_queryIndexValid_caseIndexMinusOne`

**SimilaritySpecTest additions:**
- `dtwSpec_nullWindowSize_accepted`
- `dtwSpec_positiveWindowSize_accepted`
- `dtwSpec_zeroWindowSize_rejected`
- `dtwSpec_negativeWindowSize_rejected`
- `editDistanceSpec_emptyMap_accepted`
- `editDistanceSpec_mirrorValidation`
- `editDistanceSpec_nanScore_rejected`
- `editDistanceSpec_outOfRangeScore_rejected`
- `editDistanceSpec_conflictingScores_rejected`
