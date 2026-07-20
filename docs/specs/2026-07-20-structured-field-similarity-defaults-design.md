# Structured Field Similarity Defaults

**Issue:** casehubio/neocortex#169
**Date:** 2026-07-20
**Status:** approved

## Problem

`CbrSimilarityScorer.scoreDetailed()` contains a `continue` guard that silently
skips the four structured field types — `CategoricalList`, `NumericList`,
`NestedObject`, `ObjectList` — when no `LocalSimilarityFunction` override is
provided. Structured fields are excluded from both `weightedSum` and
`totalWeight` — they contribute nothing to the score. The `return 0.0` branches
in `localSimilarity()` are unreachable dead code behind this guard.

Additionally, `CbrFeatureValidator.validateQueryFeatures()` rejects structured
fields in query features entirely ("must be queried via filters, not features"),
preventing them from reaching the scorer through any store backend.

Both enforcement points — the validator rejection and the scorer's `continue`
guard — implement the original "filter-only" policy from #128. Both must be
removed for structured fields to participate in scoring.

## Design Principle

The existing three-level precedence chain applies:

1. Caller override (`LocalSimilarityFunction`) — trumps everything
2. Schema-attached `SimilaritySpec` — configures algorithm shape
3. **Type default** — built into `CbrSimilarityScorer`

Flat field types already have type defaults: Categorical → exact match,
Numeric → linear decay, Text → exact match. Structured fields should follow
the same pattern: if a field is in the schema and has values, it participates
in scoring with a sensible default.

**Divergence from #169:** The issue suggests "opt-in SimilaritySpec variants."
This spec provides always-on type defaults instead — consistent with how flat
fields work. SimilaritySpec variants for structured fields (e.g., configurable
Jaccard thresholds) are deferred until need arises. Callers who want different
behavior can provide `LocalSimilarityFunction` overrides.

## Algorithms

### CategoricalList — Jaccard similarity

```
sim(q, c) = |intersection(q, c)| / |union(q, c)|
```

Values are `StringListVal`. Treated as sets (duplicate-insensitive).

**Pre-algorithm guards** (applied before the formula):
- Both lists empty → 1.0 (union is empty; Jaccard undefined — convention: identical)
- Query empty, case non-empty → 1.0 (vacuously satisfied — no query values to match)

These are design choices, not Jaccard consequences.

### NumericList — Average nearest-neighbor (linear decay)

```
For each element q_i in query list:
    find case element c_j minimizing |q_i - c_j|
    sim_i = max(0, 1 - |q_i - c_j| / range)
result = average(sim_i)
```

Values are `NumberListVal`. Uses the field's `min`/`max` for normalization —
same decay function as the Numeric type default. Query-centric: measures how
well the case covers the query's values.

**Pre-algorithm guards:**
- Query list empty → 1.0 (vacuously satisfied)
- `range == 0` (min equals max) → exact-match fallback per element
  (same as Numeric type default: 1.0 if equal, 0.0 otherwise)

### NestedObject — Recursive scoring with uniform weights

```
Extract inner values from StructVal for both query and case.
For each inner field present in query:
    localSimilarity(innerField, queryVal, caseVal, Map.of())
    (caseVal missing → 0.0)
result = average(similarities)    // empty → 1.0
```

Scores inner fields directly via `localSimilarity()` — no sub-schema needed.
Inner fields use type defaults only — `SimilaritySpec` is not supported on
inner fields (enforced by `validateFlatFields`). Outer-level caller overrides
are **not** propagated to inner field scoring (`Map.of()` for overrides) —
inner field names occupy a separate namespace from outer fields, and
propagation would create implicit cross-level coupling. To customise inner
field scoring, override the entire structured field with a
`LocalSimilarityFunction`.

### ObjectList — Greedy best-match with reuse

```
For each query object in StructListVal:
    For each case object:
        score(queryObj, caseObj) using per-inner-field localSimilarity
    Take max score across case objects
result = average(maxScores)
```

Best-match with reuse — a single case object can match multiple query objects.
The question is "how well does this case cover my query?" not "can they be
paired 1:1?"

Inner field scoring follows the same rules as NestedObject: type defaults only,
no override propagation.

## Edge Cases

All structured field types share these pre-algorithm guards — explicit design
choices applied before any formula, not mathematical consequences of the
algorithms:

| Condition | Result | Rationale |
|-----------|--------|-----------|
| Both values empty | 1.0 | Identical (no values to differ on) |
| Query empty, case non-empty | 1.0 | Vacuously satisfied |
| Query non-empty, case empty | 0.0 | Case satisfies nothing |
| Identical values | 1.0 | All algorithms produce 1.0 |

## Scope

### In scope

- Four private methods in `CbrSimilarityScorer` (one per structured field type)
- Remove the `continue` guard in both `scoreDetailed()` overloads that skips
  structured fields when no override is present
- Four switch branches updated (`return 0.0` → algorithm call) in `localSimilarity()`
- Update `CbrFeatureValidator.validateQueryFeatures()` to accept structured
  field types with type validation (matching `validateStoreFeatures()`:
  `StringListVal` for CategoricalList, `NumberListVal` for NumericList,
  `StructVal` for NestedObject, `StructListVal` for ObjectList)
- Update class-level Javadoc to list all seven type defaults
- Unit tests for all four defaults, edge cases, and precedence chain

### Not in scope

- New `SimilaritySpec` variants for structured fields — deferred until need
- Changes to `FeatureField` records
- Changes to store implementations (the validator and scorer are in memory-api;
  stores call the validator unchanged)
- `LocalSimilarityFunction` documentation patterns (separate commit)
- Update `validateFlatFields` error messages from "filter-only" to reflect
  scoring participation (#170)

## Files Changed

- `memory-api/src/main/java/.../CbrSimilarityScorer.java` (`continue` guard
  removal, algorithm methods, Javadoc update)
- `memory-api/src/main/java/.../CbrFeatureValidator.java` (accept structured
  fields in query features with type validation)
- `memory-api/src/test/java/.../CbrSimilarityScorerTest.java`
- `memory-api/src/test/java/.../CbrFeatureValidatorTest.java` (rejection tests
  become acceptance-with-validation tests)
