# Temporal Case Representation — Design Spec

**Issue:** casehubio/neocortex#91, casehubio/neocortex#92 (algorithms)
**Date:** 2026-07-10
**Status:** Draft

## Problem

CBR cases currently represent point-in-time snapshots — flat features (Categorical, Numeric, Text) and structured fields (CategoricalList, NestedObject, ObjectList). Real-world cases often involve sequences over time: an economy trajectory across a game, a patient's vitals over days, a manufacturing process's sensor readings. "Find cases where the trajectory evolved like this" requires comparing ordered sequences, not fixed values.

## Decision

Extend the `FeatureField` sealed hierarchy with two new variants for temporal data. Temporal sequences are features — they live in `CbrCase.features()`, are schema-validated, and participate in weighted similarity scoring alongside flat features. No new modules, no new SPIs, no new `CbrCase` subtypes.

### Alternatives considered

**New CbrCase subtype (`TemporalCbrCase`):** Rejected — temporal data is a kind of feature, not a kind of case. A game case naturally has both flat features (race, mmr) and temporal features (economy curve). Forcing a choice between "feature-vector case" and "temporal case" is a false dichotomy.

**Sequence embeddings:** Deferred — requires a trained encoder per domain, ties to ONNX infrastructure (#77), and is lossy. Can be added later as an additional retrieval leg. The hybrid fusion infrastructure already supports multiple legs.

**Qdrant server-side sequence filtering:** Deferred — marginal benefit when candidate sets are already filtered by tenant/caseType/features/notBefore. Adds complexity for limited gain.

## Design

### 1. New FeatureField variants

Two new sealed permits on `FeatureField`:

#### TimeSeries

```java
record TimeSeries(String name, List<FeatureField> innerFields,
                  String timestampField) implements FeatureField
```

An ordered sequence of observations over time. Each observation is a `Map<String, Object>` validated against `innerFields` — reusing the same flat-field whitelist that `NestedObject` and `ObjectList` enforce (only Categorical, Numeric, Text inner fields; no nesting, no SimilaritySpec on inner fields, no semantic Text).

`timestampField` names one of the `innerFields` that serves as the ordering axis. Must refer to a `Numeric` inner field. The timestamp field is excluded from DTW distance computation — it serves only as the ordering axis for store-time validation. DTW's time-warping handles temporal misalignment; including an explicit time dimension would defeat that purpose by double-counting temporal distance.

Observations are stored in ascending timestamp order — enforced at store-time validation.

Store value: `List<Map<String, Object>>`.

**Constructor validation:**
1. `name` must not be null
2. `innerFields` must not be null or empty
3. `innerFields` validated via `validateFlatFields()` — same whitelist as NestedObject/ObjectList (only Categorical, Numeric, Text; no nesting, no SimilaritySpec, no semantic Text). TimeSeries and DiscreteSequence are rejected as inner fields.
4. `timestampField` must not be null and must reference an existing inner field by name
5. The referenced inner field must be `Numeric`
6. At least one non-timestamp `Numeric` inner field must exist — otherwise DTW computes distance over zero dimensions and always returns similarity 1.0

#### DiscreteSequence

```java
record DiscreteSequence(String name) implements FeatureField
```

An ordered `List<String>` of categorical labels. No inner schema — just a sequence of tokens.

Store value: `List<String>`.

#### Relationship to ObjectList

`ObjectList` is an unordered set queried via `HasMatch` filters (any-element-matching). `TimeSeries` is ordered — position matters, queried via sequence similarity (DTW). `DiscreteSequence` is ordered — queried via edit distance. Different semantics, different query paths.

#### Schema example

```java
CbrFeatureSchema.of("starcraft-game",
    FeatureField.categorical("race"),
    FeatureField.numeric("mmr", 0, 8000),
    FeatureField.timeSeries("economyCurve", "minute",
        FeatureField.numeric("minute", 0, 30),
        FeatureField.numeric("economy", 0, 500),
        FeatureField.numeric("army", 0, 200),
        FeatureField.categorical("posture")
    ),
    FeatureField.discreteSequence("phaseProgression")
);
```

### 2. Validation

#### Store-time (`CbrFeatureValidator.validateStoreFeatures`)

**TimeSeries:**
- Value must be `List<Map<String, Object>>`
- Each observation validated against `innerFields` (same logic as NestedObject/ObjectList inner validation)
- Timestamp field must be present in every observation
- Observations must be in ascending timestamp order — reject if any observation has a timestamp ≤ its predecessor
- Empty list is valid (no observations yet)

**DiscreteSequence:**
- Value must be `List<String>`
- Empty list is valid

#### Query-time (`CbrFeatureValidator.validateQueryFeatures`)

Both temporal field types are allowed in query features (unlike structured fields which throw). The query value has the same shape as the store value — a full sequence compared via similarity algorithms.

- TimeSeries query: `List<Map<String, Object>>`, same validation as store (ascending timestamps)
- DiscreteSequence query: `List<String>`

#### Filter validation (`CbrFeatureValidator.validateFilters`)

Temporal fields do not support `CbrFilter` predicates. Attempting to use Contains/ContainsAll/ContainsAny/HasMatch on a TimeSeries or DiscreteSequence field throws `IllegalArgumentException`.

### 3. Similarity algorithms

Two pure-Java implementations in `memory-api`. No external dependencies.

#### DTW (Dynamic Time Warping) — for TimeSeries

Standard DTW with Euclidean distance on multi-dimensional observations.

- Only `Numeric` inner fields participate in distance computation, **excluding the timestamp field** (which serves only as the ordering axis — see §1). Categorical and Text inner fields are structural metadata — ignored by DTW.
- Distance between two observations: Euclidean distance across all non-timestamp numeric inner fields, each normalized by the field's `[min, max]` range (fields with different scales contribute equally).
- Cost matrix: standard O(n×m) dynamic programming. No windowing constraint (full matrix).
- Normalization: DTW distance divided by `max(n, m)` for length independence. This normalization favors partial matches — a short query matched against a long case uses the longer length as denominator, giving higher similarity than `/(n+m)` would. This is the right default for a domain-agnostic system: a 5-step partial trajectory should match against the corresponding portion of a 50-step case without being penalized for the unmatched suffix. Alternative normalizations (`/(n+m)`, `/pathLength`) can be added via SimilaritySpec in #92.
- Similarity: `1.0 / (1.0 + normalizedDtwDistance)` — maps distance ∈ [0, ∞) to similarity ∈ (0, 1]. Zero distance → 1.0.

#### Edit Distance — for DiscreteSequence

Standard Levenshtein distance with uniform costs (insert = delete = substitute = 1).

- O(n×m) dynamic programming.
- Similarity: `1.0 - (editDistance / max(n, m))`. Zero edits → 1.0. Both empty → 1.0.

#### Integration into CbrSimilarityScorer

**Skip-logic in `score()`:** The scorer currently skips structured fields (CategoricalList, NestedObject, ObjectList) with explicit `instanceof` checks before reaching `localSimilarity()`. Temporal fields are score-participating and must NOT be skipped. The guard must be updated to explicitly list only the non-scorable types, or switched to a positive check for scorable types, to make the intent clear. Temporal fields that happen to pass through via negative matching is fragile.

**Missing temporal features in query:** If a query omits a temporal feature defined in the schema, it is excluded from the weighted sum — consistent with flat field behavior. If the query includes a temporal feature but the stored case doesn't have it, similarity is 0.0. This is intentional: missing data is treated as maximally dissimilar, same as all other field types. No special "unknown" handling for temporal fields.

New branches in `localSimilarity()`:

```java
case FeatureField.TimeSeries ts -> dtwSimilarity(ts, queryVal, caseVal, overrides)
case FeatureField.DiscreteSequence ds -> editDistanceSimilarity(queryVal, caseVal, overrides)
```

Three-level precedence applies:
1. Caller-provided `LocalSimilarityFunction` override (if present)
2. No SimilaritySpec for temporal fields in this iteration (reserved for future: windowed DTW, weighted edit distance)
3. Type default: DTW for TimeSeries, edit distance for DiscreteSequence

#### Performance

For 1000 cases × 50 time steps × 5 numeric fields per observation:
- DTW: ~50×50 = 2,500 cells × 1000 candidates = 2.5M cells. Each cell: 5 normalized subtractions + sqrt. Estimate: < 50ms.
- Pre-filtering by tenant, caseType, flat features, notBefore reduces candidates before temporal scoring runs.

**Worst case:** If temporal features are the primary discriminator and flat features provide little filtering, the full candidate set reaches DTW scoring. Client-side DTW remains acceptable up to ~5,000 candidates. Beyond that, approximate DTW (LB_Keogh, Sakoe-Chiba band) or sequence embeddings are needed — both deferred to #92 and future work respectively. The current architecture scores all features together in `CbrSimilarityScorer.score()`; a two-pass approach (flat features first, temporal scoring on top-N survivors) would bound DTW invocations but changes scoring semantics. This optimization is deferred.

### 4. CbrQuery interaction

Temporal features go in `CbrQuery.features()` alongside flat features. No new fields on `CbrQuery`.

```java
CbrQuery.of(tenantId, domain, "starcraft-game", Map.of(
    "race", "Terran",
    "mmr", 4500,
    "economyCurve", List.of(
        Map.of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"),
        Map.of("minute", 3, "economy", 45, "army", 5, "posture", "MACRO"),
        Map.of("minute", 5, "economy", 62, "army", 12, "posture", "AGGRESSIVE")
    ),
    "phaseProgression", List.of("MACRO", "MACRO", "AGGRESSIVE", "ALL_IN")
), 10);
```

Weights apply normally:
```java
query.withWeight("economyCurve", 3.0).withWeight("race", 0.5)
```

Retrieval modes:
- `FEATURE_ONLY`: DTW/edit distance run client-side against matching candidates. Primary path for temporal similarity.
- `SEMANTIC_ONLY`: Temporal fields irrelevant — pure vector search on `problem()`.
- `HYBRID`: Temporal fields participate in the composite feature score, fused with semantic vector score.

### 5. Storage

#### Qdrant payload (`CbrPointBuilder`)

No changes needed. `CbrPointBuilder` already handles `List<Map>` via `toListValue`/`toStructValue` and `List<String>` via `toListValue`. Values flow through the existing `features()` map serialization path as prefixed payload fields (`f_<name>`).

#### Qdrant reconstruction (`QdrantCbrCaseMemoryStore`)

`reconstructCase` must handle temporal feature values — deserializing `List<Map>` and `List<String>` from Qdrant payload back into `features()`. The `_features_json` payload field already stores the full features map as JSON; reconstruction reads from that, so no structural changes needed.

`CbrQueryTranslator` — reject filters on temporal fields (same validation as `CbrFeatureValidator.validateFilters`).

#### In-memory backend

No code changes. Delegates to `CbrSimilarityScorer` and `CbrFeatureValidator` which gain the new field type handling.

### 6. Module impact

| Module | Changes |
|--------|---------|
| `memory-api` | `FeatureField` — add TimeSeries, DiscreteSequence sealed permits. `FeatureField.validateFlatFields()` — reject temporal types as inner fields. `CbrFeatureValidator` — store/query/filter validation for new types. `CbrSimilarityScorer` — update skip-logic in `score()`, add DTW/edit distance branches in `localSimilarity()`. New: `DtwSimilarity`, `EditDistanceSimilarity` utility classes. |
| `memory-testing` | `CbrCaseMemoryStoreContractTest` — ~25 new tests. Unit tests for DTW and edit distance. |
| `memory-cbr-inmem` | No code changes (delegates to scorer/validator). |
| `memory-qdrant` | `CbrQueryTranslator` — reject temporal fields in filter translation (exhaustive switch update). Reconstruction handles temporal values via existing `_features_json` path. |

No new modules.

**Exhaustive switch sites** (compiler will enforce — enumerated here for implementation clarity):

| File | Method | Required change |
|------|--------|----------------|
| `FeatureField` | `validateFlatFields()` | Reject TimeSeries and DiscreteSequence as inner fields |
| `CbrSimilarityScorer` | `score()` | Update skip-logic — don't skip temporal fields |
| `CbrSimilarityScorer` | `localSimilarity()` | Add DTW and edit distance branches |
| `CbrFeatureValidator` | `validateStoreFeatures()` | Add temporal validation cases |
| `CbrFeatureValidator` | `validateQueryFeatures()` | Allow temporal fields (unlike structured) |
| `CbrFeatureValidator` | `validateFilters()` | Reject filters on temporal fields |
| `CbrQueryTranslator` | filter translation | Reject temporal fields (throw like structured) |

## Out of scope

- **SimilaritySpec for temporal fields** — windowed DTW, weighted edit distance, approximate DTW. Deferred to #92.
- **Sequence embeddings** — encoding sequences into vectors for Qdrant-native search. Future enhancement.
- **Temporal-specific CbrFilter variants** — "sequence contains subsequence". Model via flat features if needed.
- **Qdrant payload indexing for temporal fields** — no per-observation indexes. Temporal fields scored client-side.
- **Alignment path extraction** — DTW can produce the optimal alignment path for explainability. Listed in #92 acceptance criteria. DTW implementation here computes cost matrix; path extraction adds without API change. Since this spec delivers the core DTW/edit distance algorithms that #92 tracks, a separate issue should be filed for alignment path extraction (the remaining #92 acceptance criterion that this spec does not deliver).

## Contract tests

~25 new tests in `CbrCaseMemoryStoreContractTest`:

**Schema and validation:**
- `temporal_timeSeries_schemaCreation`
- `temporal_discreteSequence_schemaCreation`
- `temporal_timeSeries_validation_timestampFieldMustExist`
- `temporal_timeSeries_validation_timestampFieldMustBeNumeric`
- `temporal_timeSeries_validation_requiresNonTimestampNumericField`
- `temporal_timeSeries_validation_rejectsTemporalInnerFields`
- `temporal_timeSeries_validation_storeAscendingTimestamps`
- `temporal_timeSeries_validation_storeNonAscending_rejected`
- `temporal_timeSeries_validation_missingTimestampField_rejected`
- `temporal_timeSeries_validation_innerFieldTypes`
- `temporal_timeSeries_validation_emptyList_accepted`
- `temporal_discreteSequence_validation_storeListOfStrings`
- `temporal_discreteSequence_validation_storeWrongType_rejected`
- `temporal_filter_onTemporalField_rejected`

**Similarity and retrieval:**
- `temporal_timeSeries_identicalSequences_scorePerfect`
- `temporal_timeSeries_differentSequences_scoredByDtw`
- `temporal_timeSeries_variableLength_handledByDtw`
- `temporal_timeSeries_singleObservation_degenerateDtw`
- `temporal_timeSeries_dtwExcludesTimestampField`
- `temporal_discreteSequence_identicalSequences_scorePerfect`
- `temporal_discreteSequence_oneSubstitution_scoreLessThanPerfect`
- `temporal_discreteSequence_completelyDifferent_scoreNearZero`
- `temporal_discreteSequence_emptyVsNonEmpty_scoreZero`
- `temporal_discreteSequence_bothEmpty_scorePerfect`

**Integration:**
- `temporal_mixedFlatAndTemporal_weightedScoring`
- `temporal_weightOverride_temporalFieldDominates`
- `temporal_flatFeatureFilter_reducesCandidatesBeforeDtw`

**Round-trip:**
- `temporal_timeSeries_storeAndRetrieve_roundTrip`
- `temporal_discreteSequence_storeAndRetrieve_roundTrip`
- `temporal_coexistsWithStructuredFields`

## Design Review Log

Adversarial review: 1 round, 11 issues raised. Workspace: `~/adr/casehub-neocortex/temporal-case-representation-20260710-192419/`

| ID | Issue | Resolution |
|----|-------|------------|
| R1-02 | DTW must exclude timestamp field from distance | **Accepted** — spec updated §1 and §3 |
| R1-03 | TimeSeries with zero scorable Numeric fields undefined | **Accepted** — constructor validation added to §1 |
| R1-04 | Spec delivers #92's algorithms, should reference both | **Accepted** — issue reference updated, alignment path noted for separate issue |
| R1-05 | Scorer skip-logic not addressed | **Accepted** — explicit guidance added to §3 |
| R1-06 | TimeSeries constructor validation not specified | **Accepted** — full validation list added to §1 |
| R1-07 | Exhaustive switch sites not enumerated | **Accepted** — switch site table added to §6 |
| R1-08 | DTW performance worst-case analysis | **Accepted** — worst-case bound and deferred optimization documented |
| R1-09 | Missing temporal feature behavior unspecified | **Accepted** — confirmed 0.0 consistent with flat fields, documented in §3 |
| R1-10 | DTW normalization rationale not documented | **Accepted** — rationale added to §3 |
| R1-11 | DiscreteSequence optional SimilaritySpec for future | **Deferred** — pre-release, record change is free; explicitly deferred to #92 |
