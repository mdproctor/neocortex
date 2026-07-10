# Structured Case Fields — Nested Objects and List Containment in CbrQuery

**Issue:** casehubio/neocortex#89
**Date:** 2026-07-10
**Status:** Approved

## Context

CBR cases currently store features as `Map<String, Object>` with only flat values
(String, Number) handled end-to-end. `CbrPointBuilder` silently drops Lists and
Maps. The schema (`FeatureField` sealed interface) has three variants: `Categorical`,
`Numeric`, `Text`. This limits case representation to flat key-value pairs.

Real-world cases need richer structure. A game case might have:

```json
{
  "opponent_posture": "ALL_IN",
  "game_phases": ["EARLY_AGGRESSION", "MID_SKIRMISH", "LATE_PUSH"],
  "key_moments": [
    {"type": "FIRST_CONTACT", "minute": 3.2},
    {"type": "BATTLE_WON", "minute": 5.1}
  ],
  "economy_trajectory": {"minute_3": 45, "minute_5": 62, "minute_8": 80}
}
```

Qdrant supports nested object payloads and filtering on nested fields natively.
This work exposes that capability through the neocortex SPI.

## Design Decisions

1. **Structured fields are filter-only** — they narrow the candidate set via hard
   pre-filters but do not participate in weighted similarity scoring. Graded
   similarity over lists/nested objects is domain-specific and poorly generalised.
   Callers who need custom scoring can still use `LocalSimilarityFunction` overrides.

2. **Separate `filters` field on CbrQuery** — structural filter predicates live in
   `Map<String, CbrFilter> filters`, distinct from `Map<String, Object> features`
   (which remains for scored flat fields). Clean separation of scored vs. filtered.

3. **One level of nesting** — inner fields of nested objects and object lists must
   be flat (`Categorical`, `Numeric`, `Text`). Covers all current requirements.
   Recursive nesting can be added later (#91 temporal representation may need it)
   without breaking the sealed hierarchy.

## Data Model Changes

### FeatureField Extensions

Three new variants added to the `FeatureField` sealed interface in `memory-api`:

```java
sealed interface FeatureField permits
    Categorical, Numeric, Text,
    CategoricalList, NestedObject, ObjectList {

    // Existing variants unchanged

    record CategoricalList(String name) implements FeatureField {
        // Stores List<String> values. Filter-only.
        public CategoricalList {
            Objects.requireNonNull(name, "name");
        }
    }

    record NestedObject(String name, List<FeatureField> innerFields) implements FeatureField {
        // Stores Map<String, Object>. Inner fields must be flat.
        public NestedObject {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(innerFields, "innerFields");
            innerFields = List.copyOf(innerFields);
            validateFlatFields(innerFields);
        }
    }

    record ObjectList(String name, List<FeatureField> innerFields) implements FeatureField {
        // Stores List<Map<String, Object>>. Inner fields must be flat.
        public ObjectList {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(innerFields, "innerFields");
            innerFields = List.copyOf(innerFields);
            validateFlatFields(innerFields);
        }
    }

    private static void validateFlatFields(List<FeatureField> fields) {
        Set<String> names = new HashSet<>();
        for (FeatureField f : fields) {
            if (!names.add(f.name()))
                throw new IllegalArgumentException("Duplicate inner field name: '" + f.name() + "'");
            switch (f) {
                case Categorical c -> {
                    if (c.similaritySpec() != null) throw new IllegalArgumentException(
                        "Inner field '" + c.name() + "': SimilaritySpec not supported — inner fields are filter-only");
                }
                case Numeric n -> {
                    if (n.similaritySpec() != null) throw new IllegalArgumentException(
                        "Inner field '" + n.name() + "': SimilaritySpec not supported — inner fields are filter-only");
                }
                case Text t -> {
                    if (t.semantic()) throw new IllegalArgumentException(
                        "Inner field '" + t.name() + "': semantic matching not supported — inner fields are filter-only");
                }
                case CategoricalList cl -> throw new IllegalArgumentException(
                    "Inner fields must be flat (Categorical/Numeric/Text), got: CategoricalList");
                case NestedObject no -> throw new IllegalArgumentException(
                    "Inner fields must be flat (Categorical/Numeric/Text), got: NestedObject");
                case ObjectList ol -> throw new IllegalArgumentException(
                    "Inner fields must be flat (Categorical/Numeric/Text), got: ObjectList");
            }
        }
    }
}
```

Factory methods: `FeatureField.categoricalList(name)`,
`FeatureField.nestedObject(name, innerFields...)`,
`FeatureField.objectList(name, innerFields...)`.

### CbrFilter Sealed Hierarchy

New type in `memory-api`, package `io.casehub.neocortex.memory.cbr`:

```java
public sealed interface CbrFilter {

    record Contains(String value) implements CbrFilter {
        // "list contains this element"
        // Qdrant: matchKeyword("f_field", value)
        public Contains {
            Objects.requireNonNull(value, "value");
        }
    }

    record ContainsAll(List<String> values) implements CbrFilter {
        // "list contains ALL of these"
        // Qdrant: multiple must conditions with matchKeyword on same field
        public ContainsAll {
            Objects.requireNonNull(values, "values");
            if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
            values = List.copyOf(values);
        }
    }

    record ContainsAny(List<String> values) implements CbrFilter {
        // "list contains ANY of these"
        // Qdrant: matchKeywords("f_field", values)
        public ContainsAny {
            Objects.requireNonNull(values, "values");
            if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
            values = List.copyOf(values);
        }
    }

    record HasMatch(Map<String, Object> subFields) implements CbrFilter {
        // "nested object (or any element in object list) matches these sub-field values"
        // Sub-field values: String (categorical match), Number (exact), NumericRange (range)
        // Qdrant: nested() for ObjectList, dot-notation for NestedObject
        public HasMatch {
            Objects.requireNonNull(subFields, "subFields");
            if (subFields.isEmpty()) throw new IllegalArgumentException("subFields must not be empty");
            for (Map.Entry<String, Object> e : subFields.entrySet()) {
                Object v = e.getValue();
                if (!(v instanceof String) && !(v instanceof Number) && !(v instanceof NumericRange))
                    throw new IllegalArgumentException(
                        "Sub-field '" + e.getKey() + "' value must be String, Number, or NumericRange, got: "
                        + v.getClass().getSimpleName());
            }
            subFields = Map.copyOf(subFields);
        }
    }

    // Factory methods
    static Contains contains(String value) { return new Contains(value); }
    static ContainsAll containsAll(List<String> values) { return new ContainsAll(values); }
    static ContainsAny containsAny(List<String> values) { return new ContainsAny(values); }
    static HasMatch hasMatch(Map<String, Object> subFields) { return new HasMatch(subFields); }
}
```

### CbrQuery Changes

New `filters` field added to the record:

```java
public record CbrQuery(
    String tenantId,
    MemoryDomain domain,
    String caseType,
    Map<String, Object> features,        // scored flat fields (unchanged)
    Map<String, CbrFilter> filters,      // structural filters (filter-only)
    Map<String, Double> weights,
    int topK,
    double minSimilarity,
    Instant notBefore,
    String problem,
    double vectorWeight,
    RetrievalMode retrievalMode,
    FusionStrategy fusionStrategy
) { ... }
```

Compact constructor: `Objects.requireNonNull(filters)`, `filters = Map.copyOf(filters)`.

Factory method `CbrQuery.of(...)` passes `Map.of()` for filters (backward compatible).

New builder methods: `withFilter(String field, CbrFilter filter)`,
`withFilters(Map<String, CbrFilter> filters)`.

All existing `with*` methods updated to thread `filters` through.

**Breaking change**: Record constructor gains a parameter. Pre-release — direct
constructor callers (tests, backends) need updating. Factory method callers unaffected.

### CbrFeatureSchema — Field Name Uniqueness

With mixed flat and structured field types sharing the same `FeatureField` sealed
interface, duplicate field names cause cross-type shadowing: every `findField`
implementation (in `CbrSimilarityScorer`, `CbrQueryTranslator`, and
`CbrFeatureValidator`) uses linear scan returning the first match. A schema with
both `categorical("posture")` and `categoricalList("posture")` would silently
shadow the list variant, causing filter validation to check against the wrong
field type.

Add uniqueness validation to the compact constructor:

```java
public CbrFeatureSchema {
    Objects.requireNonNull(caseType, "caseType required");
    if (caseType.isBlank()) throw new IllegalArgumentException("caseType must not be blank");
    Objects.requireNonNull(fields, "fields required");
    fields = List.copyOf(fields);
    Set<String> names = new HashSet<>();
    for (FeatureField f : fields) {
        if (!names.add(f.name()))
            throw new IllegalArgumentException("Duplicate field name: '" + f.name() + "'");
    }
}
```

This is O(n) and runs once at schema construction time.

## Storage

### CbrPointBuilder — Structured Value Serialization

The feature serialization loop (currently lines 77-85) expands to handle structured values:

- `List<String>` (CategoricalList) → Qdrant list value via `ValueFactory`
- `List<Map<String, Object>>` (ObjectList) → Qdrant list of struct values
- `Map<String, Object>` (NestedObject) → Qdrant struct value

The `_features_json` blob (Jackson-serialized) already handles nested Maps and Lists
natively. No change needed there. Individual `f_<name>` fields are written for
Qdrant's filter engine.

### Case Reconstruction

No changes needed. `_features_json` round-trips structured values through Jackson's
`ObjectMapper` with `TypeReference<Map<String, Object>>`. Lists and Maps deserialize
naturally.

## Query Translation

### CbrQueryTranslator — Structural Filter Conditions

New method `applyStructuralFilters(Filter baseFilter, Map<String, CbrFilter> filters, CbrFeatureSchema schema)`:

| CbrFilter variant | Qdrant mapping |
|---|---|
| `Contains(value)` | `ConditionFactory.matchKeyword("f_field", value)` |
| `ContainsAll(values)` | Multiple `must` conditions: `matchKeyword` per value on same field |
| `ContainsAny(values)` | `ConditionFactory.matchKeywords("f_field", values)` |
| `HasMatch(subFields)` on ObjectList | `ConditionFactory.nested("f_field", innerFilter)` where innerFilter has sub-field conditions |
| `HasMatch(subFields)` on NestedObject | Dot-notation: conditions on `"f_field.subFieldName"` |

Sub-field value mapping in `HasMatch`: `String` → `matchKeyword`, `Number` →
`range(gte=val, lte=val)`, `NumericRange` → `range(gte=min, lte=max)`.

### Integration Point — QdrantCbrCaseMemoryStore.retrieveSimilar()

Structural filters are combined with the identity filter **once**, before
dispatching to any retrieval path. This ensures consistent filtering regardless
of retrieval mode:

```java
Filter filter = CbrQueryTranslator.toIdentityFilter(query);
if (!query.filters().isEmpty()) {
    if (schema == null) {
        throw new IllegalStateException(
            "Cannot apply structural filters: no schema registered for caseType '"
            + query.caseType() + "'");
    }
    filter = CbrQueryTranslator.applyStructuralFilters(filter, query.filters(), schema);
}
return switch (effectiveMode) {
    case FEATURE_ONLY -> retrieveFeatureOnly(..., filter, ...);
    case SEMANTIC_ONLY -> retrieveSemanticOnly(..., filter, ...);
    case HYBRID -> retrieveHybrid(..., filter, ...);
};
```

Structural filters are hard pre-filters — silently ignoring them when a schema is
missing is a correctness violation. Callers providing filters expect them to be
applied. The same guard applies to `InMemoryCbrCaseMemoryStore.retrieveSimilar()`:
structural filters are evaluated after identity filters, before scoring, and
require a registered schema.

Validation: filter type must match schema field type. `Contains`/`ContainsAll`/`ContainsAny`
only on `CategoricalList`. `HasMatch` only on `NestedObject` or `ObjectList`.
Unknown field → `IllegalArgumentException`.

For `HasMatch` filters, sub-field validation against the inner field schema ensures
the value-type-driven condition mapping is always consistent with the inner field's
index type:

- Sub-field name must exist in the target field's inner schema. Unknown sub-field →
  `IllegalArgumentException`. Without this, Qdrant queries a payload path with no
  data and no index, producing silent empty results.
- Sub-field value type must match inner field type:
  - `Categorical`/`Text` inner field → value must be `String`
  - `Numeric` inner field → value must be `Number` or `NumericRange`

  Mismatched types produce wrong Qdrant conditions (e.g., `range()` on a Keyword
  index, or `matchKeyword()` on a Float index). In the in-memory backend, the same
  mismatch silently returns no match via `Number.equals(String)` → false. Both
  backends diverge on the same invalid query.

### CbrCollectionManager — Payload Indexes

`registerSchemaIndexes` switch adds cases for new variants:

- `CategoricalList` → `PayloadSchemaType.Keyword` index on `f_<name>` (Qdrant
  auto-handles array-contains on keyword-indexed array fields)
- `NestedObject` → per-inner-field indexes with dot-notation path:
  `f_<name>.<innerFieldName>` with appropriate `PayloadSchemaType` (Keyword for
  Categorical/Text inner fields, Float for Numeric). Without indexes, dot-notation
  filtering causes full payload scans — unacceptable for production collections.
- `ObjectList` → per-inner-field indexes with array path syntax:
  `f_<name>[].<innerFieldName>` with appropriate `PayloadSchemaType`

## Similarity Scoring

### CbrSimilarityScorer — Skip Structured Fields

Structured fields are invisible to scoring. `CbrFeatureValidator.validateQueryFeatures()`
rejects any feature whose schema field is a structured type with a clear message:

```java
throw new IllegalArgumentException(
    "Structured field '" + name + "' must be queried via filters, not features");
```

This catches caller bugs at validation time rather than silently ignoring them.

The scorer's `localSimilarity()` switch adds explicit cases for the three new
variants that throw `IllegalStateException` — these are unreachable (validation
prevents it) but required for exhaustive switch compilation on the sealed type.

## In-Memory Backend

### InMemoryCbrCaseMemoryStore — Filter Evaluation

New `matchesFilters(CbrCase, Map<String, CbrFilter>, CbrFeatureSchema)` method
applied after identity filters, before scoring:

| CbrFilter variant | In-memory evaluation |
|---|---|
| `Contains(value)` | `storedList.contains(value)` |
| `ContainsAll(values)` | `storedList.containsAll(values)` |
| `ContainsAny(values)` | `values.stream().anyMatch(storedList::contains)` |
| `HasMatch(subFields)` on NestedObject | All sub-fields match stored object's values |
| `HasMatch(subFields)` on ObjectList | Any element in stored list satisfies all sub-field predicates |

`HasMatch` sub-field matching: `String` → equals, `Number` → equals (via
`doubleValue()`), `NumericRange` → stored value within range bounds.

Missing field in stored case → no match (returns false).

### Jackson Type Mapping Contract

Case reconstruction uses `_features_json` with `TypeReference<Map<String, Object>>`.
Jackson's default deserialization produces specific Java types that in-memory filter
evaluation must account for:

- JSON arrays → `ArrayList<Object>`
- JSON strings → `String`
- JSON fractional numbers → `Double`
- JSON integer numbers → `Integer` (or `Long` for large values)
- JSON objects → `LinkedHashMap<String, Object>`

`HasMatch` sub-field matching uses `doubleValue()` for numeric comparisons, which
handles both `Integer` and `Double` correctly. String matching uses `equals()`.
Contract tests must verify round-trip correctness for each structured value type
to ensure in-memory and Qdrant backends produce identical results.

## Validation

### CbrFeatureValidator — Shared Utility

New class in `memory-api` consolidating validation logic currently duplicated
between `CbrQueryTranslator.validateQueryFeatures()` and
`InMemoryCbrCaseMemoryStore.validateQueryFeatures()`:

- `validateStoreFeatures(features, schema)` — store-time value type checking,
  including structured types: `CategoricalList` requires `List<String>`,
  `NestedObject` requires `Map<String, Object>` with inner values matching inner
  schema, `ObjectList` requires `List<Map<String, Object>>` with same inner validation.
- `validateFilters(filters, schema)` — query-time filter-field compatibility checking.
  Top-level: filter type vs schema field type (Contains on CategoricalList, HasMatch
  on NestedObject/ObjectList). For `HasMatch`: sub-field names validated against the
  target field's inner schema (unknown → `IllegalArgumentException`), and sub-field
  value types validated against inner field types (`Categorical`/`Text` → String,
  `Numeric` → Number/NumericRange). This ensures the value-type-driven Qdrant
  condition mapping (String → `matchKeyword`, Number → `range`) is always consistent
  with the inner field's payload index type (Keyword for Categorical/Text, Float for
  Numeric).
- `validateQueryFeatures(features, schema)` — existing flat feature validation,
  extracted from the two current duplicate implementations.

### Store-Time Validation Integration Point

Both store implementations call `validateStoreFeatures` when a schema is registered:

```java
// In store() — after schema lookup, before serialization/storage
CbrFeatureSchema schema = schemas.get(caseType);
if (schema != null) {
    CbrFeatureValidator.validateStoreFeatures(cbrCase.features(), schema);
}
```

When no schema is registered, store-time validation is skipped. This is intentional:
`CbrPointBuilder` serializes structured values to `_features_json` regardless of
schema, ensuring data is persisted. Without a schema, the values are stored but not
indexed for filtering — a subsequent `registerSchema()` + reconciliation makes them
filterable. This matches the existing pattern where schemas are optional for storage
but required for filtered queries.

## Unchanged Components

- **CbrReconciliationService** — re-creates points from CbrCase; structured features
  flow through `features()` → `CbrPointBuilder` automatically.
- **Cross-encoder reranking decorator** — operates on `problem()` text for
  reranking. Both `RerankingCbrCaseMemoryStore` and `ReactiveRerankingCbrCaseMemoryStore`
  construct overfetch `CbrQuery` instances with positional arguments. These must
  pass through `query.filters()` to the new constructor parameter — otherwise
  structural filters would be silently dropped during reranking overfetch. This is
  a **correctness** requirement, not just a compile fix.
- **Reactive bridge** (`BlockingToReactiveCbrBridge`) — `filters` passes through
  CbrQuery record. No new reactive methods needed.

## Contract Tests

New tests in `CbrCaseMemoryStoreContractTest` (~23 tests):

1. CategoricalList store and retrieve (with and without Contains filter)
2. ContainsAll — matches subset, rejects missing element
3. ContainsAny — matches any present, rejects all absent
4. NestedObject store and HasMatch — categorical sub-field match
5. NestedObject HasMatch with NumericRange — range sub-field match
6. NestedObject HasMatch with exact numeric sub-field — `Number` match via `doubleValue()`
7. ObjectList HasMatch — any-element matching
8. ObjectList HasMatch with multiple sub-fields — same-element constraint
9. ObjectList HasMatch with no matching element — verify empty result
10. Mixed flat + structured — scoring on flat features, filtering on structured
11. Multiple filters on different fields — verify AND semantics across filters
12. Filter on field not present in stored case — verify no match (returns false)
13. Empty CategoricalList stored, then filtered with Contains — verify no match
14. Validation: wrong filter type on wrong field type → IllegalArgumentException
15. Validation: store-time type mismatch → IllegalArgumentException
16. Validation: structured field name in features map → IllegalArgumentException
17. Validation: query with non-empty filters and no registered schema → IllegalStateException
18. Validation: inner Categorical field with SimilaritySpec → IllegalArgumentException
19. Validation: inner Text field with semantic=true → IllegalArgumentException
20. Validation: duplicate field names in CbrFeatureSchema → IllegalArgumentException
21. Validation: HasMatch with non-existent sub-field name → IllegalArgumentException
22. Validation: HasMatch with Number value on Categorical inner field → IllegalArgumentException
23. Validation: HasMatch with String value on Numeric inner field → IllegalArgumentException

## Sealed Switch Site Updates

Adding three new variants to the `FeatureField` sealed interface requires updating
all exhaustive switch expressions/statements. There are **5 switch sites** across
the codebase:

| # | Location | File | Treatment |
|---|----------|------|-----------|
| 1 | `CbrSimilarityScorer.localSimilarity()` | memory-api | Unreachable: `validateQueryFeatures()` rejects structured fields in features before scoring. Cases throw `IllegalStateException` for compiler exhaustiveness. |
| 2 | `CbrQueryTranslator.toFilter()` | memory-qdrant | Unreachable for same reason. Cases throw `IllegalStateException`. |
| 3 | `CbrQueryTranslator.validateQueryFeatures()` | memory-qdrant | Active: new cases reject structured field types in features with `IllegalArgumentException`. |
| 4 | `CbrCollectionManager.registerSchemaIndexes()` | memory-qdrant | Active: new cases create appropriate payload indexes (see §Storage). |
| 5 | `QdrantCbrCaseMemoryStore.buildTextOverrides()` | memory-qdrant | No-op: structured fields are not `Text`, so new cases are empty `-> {}`. |

Additionally, `InMemoryCbrCaseMemoryStore.validateQueryFeatures()` uses `instanceof`
chains (not a switch expression) — it needs the same structured field rejection logic
as site #3. This validation is consolidated in `CbrFeatureValidator`.

## Module Impact

| Module | Changes |
|---|---|
| `memory-api` | FeatureField (3 variants + `validateFlatFields`), CbrFilter (new type), CbrQuery (filters field), CbrFeatureSchema (field name uniqueness), CbrFeatureValidator (new), CbrSimilarityScorer (switch update) |
| `memory-cbr-inmem` | InMemoryCbrCaseMemoryStore (filter evaluation, validation via CbrFeatureValidator) |
| `memory-qdrant` | CbrPointBuilder (structured serialization), CbrQueryTranslator (structural filters, switch updates), CbrCollectionManager (payload indexes incl. NestedObject), QdrantCbrCaseMemoryStore (wire filters, switch update in buildTextOverrides) |
| `memory-testing` | CbrCaseMemoryStoreContractTest (~23 new tests) |
| `memory` | NoOpCbrCaseMemoryStore (compile fix — CbrQuery constructor) |
| `memory-cbr-crossencoder` | Correctness fix — CbrQuery overfetch must pass through `query.filters()` |
| `memory-cbr-embedding` | No changes |

## Deferred Items

| Item | Rationale | Issue |
|---|---|---|
| `NumericList` (`List<Number>`) | No concrete use case in current requirements. Game phases are strings (CategoricalList), moment sequences are objects (ObjectList). Numeric trajectories are naturally keyed by time point (NestedObject). | To be filed |
| `NotContains` / `NotContainsAny` filter variants | Negation filters are a natural extension but not needed for initial structured field support. | To be filed |
| Compound same-field filters (`AllOf`) | `Map<String, CbrFilter>` allows one filter per field. Compound conditions on the same field (e.g., two `HasMatch` on one `ObjectList`) require an `AllOf(List<CbrFilter>)` wrapper. Expressible at Qdrant level but not in the Java API. Single-filter-per-field covers all current use cases. | To be filed |
| Graded similarity over structured fields | Domain-specific and poorly generalised (Design Decision 1). Callers use `LocalSimilarityFunction` overrides. | To be filed |

Recursive nesting beyond one level is already tracked in #91.

## Implementation Notes

- ARC42STORIES.MD does not currently cover CBR memory modules. As part of this
  implementation, update §5 Building Block View with memory module documentation.
