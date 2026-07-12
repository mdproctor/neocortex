# CBR Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #125 — NumericList field type for CbrQuery structured fields
**Issue group:** #125, #126, #127, #138, #139

**Goal:** Five CBR enhancements — Itakura parallelogram DTW constraint, configurable edit distance costs, NumericList field type, negation filters, and compound AllOf filters.

**Architecture:** All changes root in `memory-api` sealed interfaces (FeatureField, CbrFilter, SimilaritySpec). Implementations propagate through `memory-cbr-inmem` (in-memory filter evaluation), `memory-qdrant` (Qdrant filter/index translation), and `memory-testing` (contract tests). Breaking changes to DtwSpec and EditDistanceSpec are intentional (pre-release).

**Tech Stack:** Java 21, sealed interfaces, Qdrant Java client (ConditionFactory), AssertJ, JUnit 5

## Global Constraints

- Java 21 language features (sealed interfaces, records, pattern matching)
- All new sealed interface variants must be added to every exhaustive switch in the codebase
- All new CbrFilter variants need: validator rules, in-memory evaluation, Qdrant translation, contract tests
- Use `ide_edit_member` / `ide_insert_member` / `ide_replace_member` for structural edits
- Build command: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -pl memory-api,memory-cbr-inmem,memory-qdrant,memory-testing,memory`

---

### Task 1: WarpingConstraint sealed interface + DtwSpec migration (#138)

**Files:**
- Create: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/WarpingConstraint.java`
- Create: `memory-api/src/test/java/io/casehub/neocortex/memory/cbr/WarpingConstraintTest.java`
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/SimilaritySpec.java` (DtwSpec record)
- Modify: `memory-api/src/test/java/io/casehub/neocortex/memory/cbr/SimilaritySpecTest.java`
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/FeatureField.java` (TimeSeries validation switch)
- Modify: `memory-api/src/test/java/io/casehub/neocortex/memory/cbr/FeatureFieldTest.java`
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/DtwSimilarity.java`
- Modify: `memory-api/src/test/java/io/casehub/neocortex/memory/cbr/DtwSimilarityTest.java`
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/CbrSimilarityScorer.java`
- Modify: `memory-testing/src/main/java/io/casehub/neocortex/memory/cbr/testing/CbrCaseMemoryStoreContractTest.java`

**Interfaces:**
- Produces: `WarpingConstraint` sealed interface with `Unconstrained`, `SakoeChibaBand(int windowSize)`, `ItakuraParallelogram(double maxSlope)`
- Produces: `DtwSpec(WarpingConstraint constraint)` — non-null, replaces `DtwSpec(Integer windowSize)`
- Produces: `DtwSimilarity.compute(List<Map<String,Object>>, List<Map<String,Object>>, FeatureField.TimeSeries, WarpingConstraint)` — replaces `Integer windowSize` parameter

- [ ] **Step 1: Write WarpingConstraint tests**

Create `memory-api/src/test/java/io/casehub/neocortex/memory/cbr/WarpingConstraintTest.java`:

```java
package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WarpingConstraintTest {

    @Test
    void unconstrained_creates() {
        var uc = new WarpingConstraint.Unconstrained();
        assertThat(uc).isNotNull();
    }

    @Test
    void sakoeChibaBand_validWindowSize() {
        var sc = new WarpingConstraint.SakoeChibaBand(5);
        assertThat(sc.windowSize()).isEqualTo(5);
    }

    @Test
    void sakoeChibaBand_windowSizeZero_rejected() {
        assertThatThrownBy(() -> new WarpingConstraint.SakoeChibaBand(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sakoeChibaBand_negativeWindowSize_rejected() {
        assertThatThrownBy(() -> new WarpingConstraint.SakoeChibaBand(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itakuraParallelogram_validMaxSlope() {
        var ip = new WarpingConstraint.ItakuraParallelogram(2.0);
        assertThat(ip.maxSlope()).isEqualTo(2.0);
    }

    @Test
    void itakuraParallelogram_slopeAtOne_rejected() {
        assertThatThrownBy(() -> new WarpingConstraint.ItakuraParallelogram(1.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itakuraParallelogram_slopeBelowOne_rejected() {
        assertThatThrownBy(() -> new WarpingConstraint.ItakuraParallelogram(0.5))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Write WarpingConstraint implementation**

Create `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/WarpingConstraint.java`:

```java
package io.casehub.neocortex.memory.cbr;

public sealed interface WarpingConstraint {

    record Unconstrained() implements WarpingConstraint {}

    record SakoeChibaBand(int windowSize) implements WarpingConstraint {
        public SakoeChibaBand {
            if (windowSize < 1) {
                throw new IllegalArgumentException("windowSize must be >= 1, got: " + windowSize);
            }
        }
    }

    record ItakuraParallelogram(double maxSlope) implements WarpingConstraint {
        public ItakuraParallelogram {
            if (maxSlope <= 1.0) {
                throw new IllegalArgumentException("maxSlope must be > 1.0, got: " + maxSlope);
            }
        }
    }
}
```

- [ ] **Step 3: Run WarpingConstraint tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory-api -Dtest=WarpingConstraintTest`
Expected: all 7 tests PASS

- [ ] **Step 4: Migrate DtwSpec from Integer to WarpingConstraint**

In `SimilaritySpec.java`, replace the `DtwSpec` record:

```java
record DtwSpec(WarpingConstraint constraint) implements SimilaritySpec {
    public DtwSpec {
        java.util.Objects.requireNonNull(constraint, "constraint");
    }
}
```

- [ ] **Step 5: Update all DtwSpec construction sites**

These compile errors will guide you — every `new DtwSpec(Integer)` call must become `new DtwSpec(WarpingConstraint)`:

1. `FeatureField.java` — TimeSeries validation switch: `case SimilaritySpec.DtwSpec ds -> {}` — no change needed (validation accepts DtwSpec regardless of constraint type)
2. `CbrSimilarityScorer.java` `dtwSimilarity()` — change from extracting `ds.windowSize()` to extracting `ds.constraint()`
3. `DtwSimilarity.java` `compute()` — change parameter from `Integer windowSize` to `WarpingConstraint constraint`
4. `DtwSimilarityTest.java` — all `new DtwSpec(N)` → `new DtwSpec(new WarpingConstraint.SakoeChibaBand(N))`; `new DtwSpec(null)` → `new DtwSpec(new WarpingConstraint.Unconstrained())`
5. `SimilaritySpecTest.java` — same DtwSpec migrations
6. `FeatureFieldTest.java` — same
7. `CbrCaseMemoryStoreContractTest.java` — `new SimilaritySpec.DtwSpec(5)` → `new SimilaritySpec.DtwSpec(new WarpingConstraint.SakoeChibaBand(5))`; `new SimilaritySpec.DtwSpec(3)` similarly

In `CbrSimilarityScorer.java`, update `dtwSimilarity()`:

```java
@SuppressWarnings("unchecked")
private static double dtwSimilarity(FeatureField.TimeSeries ts,
                                    Object queryVal, Object caseVal) {
    WarpingConstraint constraint = ts.similaritySpec() instanceof SimilaritySpec.DtwSpec ds
                                   ? ds.constraint() : new WarpingConstraint.Unconstrained();
    return DtwSimilarity.compute(
            (java.util.List<java.util.Map<String, Object>>) queryVal,
            (java.util.List<java.util.Map<String, Object>>) caseVal, ts, constraint).score();
}
```

In `DtwSimilarity.java`, update `compute()` signature and dispatch:

```java
public static DtwResult compute(List<Map<String, Object>> query,
                                List<Map<String, Object>> caseSeq,
                                FeatureField.TimeSeries schema) {
    return compute(query, caseSeq, schema, new WarpingConstraint.Unconstrained());
}

public static DtwResult compute(List<Map<String, Object>> query,
                                List<Map<String, Object>> caseSeq,
                                FeatureField.TimeSeries schema,
                                WarpingConstraint constraint) {
    int n = query.size();
    int m = caseSeq.size();
    if (n == 0 && m == 0) {return new DtwResult(1.0, List.of());}
    if (n == 0 || m == 0) {return new DtwResult(0.0, List.of());}

    List<FeatureField.Numeric> numericFields = scorableNumericFields(schema);

    double[][] cost = new double[n + 1][m + 1];
    for (int i = 0; i <= n; i++) {
        for (int j = 0; j <= m; j++) {
            cost[i][j] = Double.MAX_VALUE;
        }
    }
    cost[0][0] = 0.0;

    for (int i = 1; i <= n; i++) {
        int jStart = computeJStart(i, n, m, constraint);
        int jEnd = computeJEnd(i, n, m, constraint);
        if (jStart > jEnd) {return new DtwResult(0.0, List.of());}
        for (int j = jStart; j <= jEnd; j++) {
            double dist = observationDistance(query.get(i - 1), caseSeq.get(j - 1), numericFields);
            cost[i][j] = dist + Math.min(cost[i - 1][j],
                                         Math.min(cost[i][j - 1], cost[i - 1][j - 1]));
        }
    }

    double dtwDistance = cost[n][m];
    double normalized  = dtwDistance / Math.max(n, m);
    double score       = 1.0 / (1.0 + normalized);

    List<AlignmentPair> path = backtrace(cost, n, m, constraint);
    return new DtwResult(score, path);
}

private static int computeJStart(int i, int n, int m, WarpingConstraint constraint) {
    return switch (constraint) {
        case WarpingConstraint.Unconstrained u -> 1;
        case WarpingConstraint.SakoeChibaBand sc -> {
            int w = Math.max(sc.windowSize(), Math.abs(n - m));
            yield Math.max(1, i - w);
        }
        case WarpingConstraint.ItakuraParallelogram ip -> {
            double s = ip.maxSlope();
            int fromOrigin = (int) Math.ceil(i / s);
            int fromEnd = (int) Math.ceil(m - s * (n - i));
            yield Math.max(1, Math.max(fromOrigin, fromEnd));
        }
    };
}

private static int computeJEnd(int i, int n, int m, WarpingConstraint constraint) {
    return switch (constraint) {
        case WarpingConstraint.Unconstrained u -> m;
        case WarpingConstraint.SakoeChibaBand sc -> {
            int w = Math.max(sc.windowSize(), Math.abs(n - m));
            yield Math.min(m, i + w);
        }
        case WarpingConstraint.ItakuraParallelogram ip -> {
            double s = ip.maxSlope();
            int fromOrigin = (int) Math.floor(s * i);
            int fromEnd = (int) Math.floor(m - (n - i) / s);
            yield Math.min(m, Math.min(fromOrigin, fromEnd));
        }
    };
}
```

Also update `backtrace()` to pass `constraint` for window bounds checking.

- [ ] **Step 6: Write Itakura DTW tests**

Add to `DtwSimilarityTest.java`:

```java
@Test
void itakura_identicalSequences_perfectScore() {
    var schema = FeatureField.timeSeries("ts", "t",
        FeatureField.numeric("t", 0, 10), FeatureField.numeric("v", 0, 100));
    var seq = List.of(Map.<String,Object>of("t", 1, "v", 50), Map.<String,Object>of("t", 2, "v", 60));
    var result = DtwSimilarity.compute(seq, seq, schema, new WarpingConstraint.ItakuraParallelogram(2.0));
    assertThat(result.score()).isEqualTo(1.0);
    assertThat(result.alignmentPath()).hasSize(2);
}

@Test
void itakura_infeasible_lengthMismatch_returnsZero() {
    var schema = FeatureField.timeSeries("ts", "t",
        FeatureField.numeric("t", 0, 10), FeatureField.numeric("v", 0, 100));
    var query = List.of(Map.<String,Object>of("t", 1, "v", 50));
    var caseSeq = List.of(
        Map.<String,Object>of("t", 1, "v", 50), Map.<String,Object>of("t", 2, "v", 60),
        Map.<String,Object>of("t", 3, "v", 70), Map.<String,Object>of("t", 4, "v", 80));
    var result = DtwSimilarity.compute(query, caseSeq, schema, new WarpingConstraint.ItakuraParallelogram(1.5));
    assertThat(result.score()).isEqualTo(0.0);
    assertThat(result.alignmentPath()).isEmpty();
}

@Test
void itakura_ceilFloorEdgeCase_n4m3_slope1_5_infeasible() {
    var schema = FeatureField.timeSeries("ts", "t",
        FeatureField.numeric("t", 0, 10), FeatureField.numeric("v", 0, 100));
    var query = List.of(
        Map.<String,Object>of("t", 1, "v", 10), Map.<String,Object>of("t", 2, "v", 20),
        Map.<String,Object>of("t", 3, "v", 30), Map.<String,Object>of("t", 4, "v", 40));
    var caseSeq = List.of(
        Map.<String,Object>of("t", 1, "v", 10), Map.<String,Object>of("t", 2, "v", 20),
        Map.<String,Object>of("t", 3, "v", 30));
    var result = DtwSimilarity.compute(query, caseSeq, schema, new WarpingConstraint.ItakuraParallelogram(1.5));
    assertThat(result.score()).isEqualTo(0.0);
    assertThat(result.alignmentPath()).isEmpty();
}

@Test
void itakura_feasible_similarSequences_positiveScore() {
    var schema = FeatureField.timeSeries("ts", "t",
        FeatureField.numeric("t", 0, 10), FeatureField.numeric("v", 0, 100));
    var query = List.of(
        Map.<String,Object>of("t", 1, "v", 10), Map.<String,Object>of("t", 2, "v", 20),
        Map.<String,Object>of("t", 3, "v", 30));
    var caseSeq = List.of(
        Map.<String,Object>of("t", 1, "v", 12), Map.<String,Object>of("t", 2, "v", 22),
        Map.<String,Object>of("t", 3, "v", 28));
    var result = DtwSimilarity.compute(query, caseSeq, schema, new WarpingConstraint.ItakuraParallelogram(2.0));
    assertThat(result.score()).isGreaterThan(0.5);
    assertThat(result.alignmentPath()).isNotEmpty();
}
```

- [ ] **Step 7: Run all memory-api tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory-api`
Expected: all PASS

- [ ] **Step 8: Run contract tests via memory-cbr-inmem**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory-cbr-inmem`
Expected: all PASS (the DtwSpec construction changes in contract tests must compile and pass)

- [ ] **Step 9: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/neocortex add memory-api/ memory-testing/ memory-cbr-inmem/
git -C /Users/mdproctor/claude/casehub/neocortex commit -m "feat(#138): WarpingConstraint sealed interface — Itakura parallelogram + DtwSpec migration"
```

---

### Task 2: Configurable insert/delete costs for edit distance (#139)

**Files:**
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/SimilaritySpec.java` (EditDistanceSpec record)
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/EditDistanceSimilarity.java`
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/CbrSimilarityScorer.java`
- Modify: `memory-api/src/test/java/io/casehub/neocortex/memory/cbr/EditDistanceSimilarityTest.java`
- Modify: `memory-api/src/test/java/io/casehub/neocortex/memory/cbr/SimilaritySpecTest.java`

**Interfaces:**
- Produces: `EditDistanceSpec(Map<String, Map<String, Double>> substitutionSimilarities, Double insertCost, Double deleteCost)` — insertCost/deleteCost nullable, default 1.0
- Produces: `EditDistanceSimilarity.compute(List<String>, List<String>, Map<...>, Double insertCost, Double deleteCost)` — variable costs + correct normalization

- [ ] **Step 1: Write insert/delete cost tests**

Add to `EditDistanceSimilarityTest.java`:

```java
@Test
void variableCosts_symmetricHighCost_lowerScore() {
    var result1 = EditDistanceSimilarity.compute(
        List.of("A", "B", "C"), List.of("A", "X", "C"), null, null, null);
    var result2 = EditDistanceSimilarity.compute(
        List.of("A", "B", "C"), List.of("A", "X", "C"), null, 2.0, 2.0);
    assertThat(result1.score()).isEqualTo(result2.score());
}

@Test
void variableCosts_highInsertCost_penalizesInsertions() {
    var baseline = EditDistanceSimilarity.compute(
        List.of("A"), List.of("A", "B", "C"), null, null, null);
    var highInsert = EditDistanceSimilarity.compute(
        List.of("A"), List.of("A", "B", "C"), null, 3.0, 1.0);
    assertThat(highInsert.score()).isLessThan(baseline.score());
}

@Test
void variableCosts_highDeleteCost_penalizesDeletions() {
    var baseline = EditDistanceSimilarity.compute(
        List.of("A", "B", "C"), List.of("A"), null, null, null);
    var highDelete = EditDistanceSimilarity.compute(
        List.of("A", "B", "C"), List.of("A"), null, 1.0, 3.0);
    assertThat(highDelete.score()).isLessThan(baseline.score());
}

@Test
void variableCosts_unitCosts_backwardCompatible() {
    var withNull = EditDistanceSimilarity.compute(
        List.of("A", "B"), List.of("X", "Y"), null, null, null);
    var withExplicit = EditDistanceSimilarity.compute(
        List.of("A", "B"), List.of("X", "Y"), null, 1.0, 1.0);
    assertThat(withNull.score()).isEqualTo(withExplicit.score());
}

@Test
void variableCosts_cheapDelIns_preferOverSub() {
    var result = EditDistanceSimilarity.compute(
        List.of("A"), List.of("B"), null, 0.3, 0.3);
    assertThat(result.score()).isGreaterThan(0.0);
    assertThat(result.score()).isLessThan(1.0);
}

@Test
void variableCosts_scoreAlwaysInZeroOne() {
    var result = EditDistanceSimilarity.compute(
        List.of("A", "B", "C"), List.of("X", "Y", "Z", "W"), null, 2.5, 0.5);
    assertThat(result.score()).isBetween(0.0, 1.0);
}

@Test
void variableCosts_bothEmpty_perfectScore() {
    var result = EditDistanceSimilarity.compute(
        List.of(), List.of(), null, 2.0, 3.0);
    assertThat(result.score()).isEqualTo(1.0);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory-api -Dtest=EditDistanceSimilarityTest`
Expected: FAIL (compile error — compute() doesn't accept cost params)

- [ ] **Step 3: Update EditDistanceSpec**

In `SimilaritySpec.java`, replace the `EditDistanceSpec` record:

```java
record EditDistanceSpec(Map<String, Map<String, Double>> substitutionSimilarities,
                        Double insertCost, Double deleteCost) implements SimilaritySpec {
    public EditDistanceSpec {
        Objects.requireNonNull(substitutionSimilarities, "substitutionSimilarities");
        substitutionSimilarities = validateAndMirrorSimilarityMap(substitutionSimilarities);
        if (insertCost != null && insertCost <= 0) {
            throw new IllegalArgumentException("insertCost must be > 0, got: " + insertCost);
        }
        if (deleteCost != null && deleteCost <= 0) {
            throw new IllegalArgumentException("deleteCost must be > 0, got: " + deleteCost);
        }
    }

    public EditDistanceSpec(Map<String, Map<String, Double>> substitutionSimilarities) {
        this(substitutionSimilarities, null, null);
    }
}
```

- [ ] **Step 4: Update EditDistanceSimilarity.compute()**

Replace the `compute` method to accept costs:

```java
public static EditDistanceResult compute(List<String> query, List<String> caseSeq,
                                         java.util.Map<String, java.util.Map<String, Double>> substitutionSimilarities,
                                         Double insertCost, Double deleteCost) {
    int n = query.size();
    int m = caseSeq.size();
    double effIns = insertCost != null ? insertCost : 1.0;
    double effDel = deleteCost != null ? deleteCost : 1.0;

    if (n == 0 && m == 0) {return new EditDistanceResult(1.0, List.of());}

    double[][] dp = new double[n + 1][m + 1];
    for (int i = 0; i <= n; i++) {dp[i][0] = i * effDel;}
    for (int j = 0; j <= m; j++) {dp[0][j] = j * effIns;}

    for (int i = 1; i <= n; i++) {
        for (int j = 1; j <= m; j++) {
            String qLabel = query.get(i - 1);
            String cLabel = caseSeq.get(j - 1);
            double subCost;
            if (qLabel.equals(cLabel)) {
                subCost = 0.0;
            } else {
                subCost = 1.0 - lookupSimilarity(qLabel, cLabel, substitutionSimilarities);
            }
            dp[i][j] = Math.min(dp[i - 1][j] + effDel,
                                Math.min(dp[i][j - 1] + effIns, dp[i - 1][j - 1] + subCost));
        }
    }

    double editDistance = dp[n][m];
    double maxDist;
    if (1.0 <= effDel + effIns) {
        maxDist = Math.min(n, m) + Math.max(0, n - m) * effDel + Math.max(0, m - n) * effIns;
    } else {
        maxDist = n * effDel + m * effIns;
    }
    double score = maxDist > 0 ? Math.max(0.0, 1.0 - editDistance / maxDist) : 1.0;

    List<EditStep> path = backtrace(dp, query, caseSeq);
    return new EditDistanceResult(score, path);
}

public static EditDistanceResult compute(List<String> query, List<String> caseSeq) {
    return compute(query, caseSeq, null, null, null);
}

public static EditDistanceResult compute(List<String> query, List<String> caseSeq,
                                         java.util.Map<String, java.util.Map<String, Double>> substitutionSimilarities) {
    return compute(query, caseSeq, substitutionSimilarities, null, null);
}
```

- [ ] **Step 5: Update CbrSimilarityScorer to pass costs**

In `CbrSimilarityScorer.java`, update `editDistanceSimilarity()`:

```java
@SuppressWarnings("unchecked")
private static double editDistanceSimilarity(FeatureField.DiscreteSequence ds,
                                             Object queryVal, Object caseVal) {
    java.util.Map<String, java.util.Map<String, Double>> subSim = null;
    Double insertCost = null;
    Double deleteCost = null;
    if (ds.similaritySpec() instanceof SimilaritySpec.EditDistanceSpec es) {
        subSim = es.substitutionSimilarities();
        insertCost = es.insertCost();
        deleteCost = es.deleteCost();
    }
    return EditDistanceSimilarity.compute(
            (java.util.List<String>) queryVal,
            (java.util.List<String>) caseVal, subSim, insertCost, deleteCost).score();
}
```

- [ ] **Step 6: Run tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory-api`
Expected: all PASS

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/neocortex add memory-api/
git -C /Users/mdproctor/claude/casehub/neocortex commit -m "feat(#139): configurable insert/delete costs for edit distance"
```

---

### Task 3: NotContains / NotContainsAny filters (#126)

**Files:**
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/CbrFilter.java`
- Modify: `memory-api/src/test/java/io/casehub/neocortex/memory/cbr/CbrFilterTest.java`
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/CbrFeatureValidator.java`
- Modify: `memory-cbr-inmem/src/main/java/io/casehub/neocortex/memory/cbr/inmem/InMemoryCbrCaseMemoryStore.java`
- Modify: `memory-qdrant/src/main/java/io/casehub/neocortex/memory/cbr/qdrant/CbrQueryTranslator.java`
- Modify: `memory-testing/src/main/java/io/casehub/neocortex/memory/cbr/testing/CbrCaseMemoryStoreContractTest.java`

**Interfaces:**
- Produces: `CbrFilter.NotContains(String value)`, `CbrFilter.NotContainsAny(List<String> values)`
- Produces: `CbrFilter.notContains(String)`, `CbrFilter.notContainsAny(List<String>)` factory methods

- [ ] **Step 1: Write contract tests for negation filters**

Add to `CbrCaseMemoryStoreContractTest.java`:

```java
@Test
void structuredFields_notContains_excludesCasesWithValue() {
    registerStructuredSchema();
    storeStructuredCase("has-rush", Map.of("phases", List.of("EARLY", "RUSH", "LATE")), "c1");
    storeStructuredCase("no-rush", Map.of("phases", List.of("EARLY", "MACRO", "LATE")), "c2");

    var q = CbrQuery.of(TENANT, CBR, "structured-game", Map.of(), 10)
        .withFilter("phases", CbrFilter.notContains("RUSH"))
        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
    var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).cbrCase().problem()).isEqualTo("no-rush");
}

@Test
void structuredFields_notContainsAny_excludesCasesWithAnyValue() {
    registerStructuredSchema();
    storeStructuredCase("has-rush", Map.of("phases", List.of("EARLY", "RUSH")), "c1");
    storeStructuredCase("has-cheese", Map.of("phases", List.of("EARLY", "CHEESE")), "c2");
    storeStructuredCase("clean", Map.of("phases", List.of("EARLY", "MACRO")), "c3");

    var q = CbrQuery.of(TENANT, CBR, "structured-game", Map.of(), 10)
        .withFilter("phases", CbrFilter.notContainsAny(List.of("RUSH", "CHEESE")))
        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
    var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).cbrCase().problem()).isEqualTo("clean");
}

@Test
void structuredFields_notContains_validation_requiresCategoricalList() {
    registerStructuredSchema();
    assertThatThrownBy(() -> {
        var q = CbrQuery.of(TENANT, CBR, "structured-game", Map.of(), 10)
            .withFilter("playerRank", CbrFilter.notContains("GOLD"));
        store().retrieveSimilar(q, FeatureVectorCbrCase.class);
    }).isInstanceOf(IllegalArgumentException.class);
}
```

(This assumes `registerStructuredSchema()` and `storeStructuredCase()` already exist — check and use the existing helpers in the contract test.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory-cbr-inmem -Dtest=InMemoryCbrCaseMemoryStoreTest`
Expected: FAIL (compile error — CbrFilter.notContains doesn't exist)

- [ ] **Step 3: Add NotContains/NotContainsAny to CbrFilter**

In `CbrFilter.java`, add after the existing records:

```java
record NotContains(String value) implements CbrFilter {
    public NotContains {
        Objects.requireNonNull(value, "value");
    }
}

record NotContainsAny(List<String> values) implements CbrFilter {
    public NotContainsAny {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
        values = List.copyOf(values);
    }
}
```

Add factory methods:

```java
static NotContains notContains(String value) { return new NotContains(value); }
static NotContainsAny notContainsAny(List<String> values) { return new NotContainsAny(values); }
```

- [ ] **Step 4: Update CbrFeatureValidator.validateFilters()**

In the `switch (filter)` block, add:

```java
case CbrFilter.NotContains nc -> requireCategoricalList(name, field);
case CbrFilter.NotContainsAny nca -> requireCategoricalList(name, field);
```

- [ ] **Step 5: Update InMemoryCbrCaseMemoryStore.matchesFilters()**

In the `switch (filter)` block, add:

```java
case CbrFilter.NotContains nc ->
    storedValue instanceof List<?> list && !list.contains(nc.value());
case CbrFilter.NotContainsAny nca ->
    storedValue instanceof List<?> list && nca.values().stream().noneMatch(list::contains);
```

- [ ] **Step 6: Update CbrQueryTranslator.applyStructuralFilters()**

In the `switch (filter)` block, add:

```java
case CbrFilter.NotContains nc -> builder.addMustNot(ConditionFactory.matchKeyword(payloadKey, nc.value()));
case CbrFilter.NotContainsAny nca -> nca.values().forEach(v ->
    builder.addMustNot(ConditionFactory.matchKeyword(payloadKey, v)));
```

- [ ] **Step 7: Run tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory-api,memory-cbr-inmem,memory-testing,memory`
Expected: all PASS

- [ ] **Step 8: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/neocortex add memory-api/ memory-cbr-inmem/ memory-qdrant/ memory-testing/
git -C /Users/mdproctor/claude/casehub/neocortex commit -m "feat(#126): NotContains / NotContainsAny CbrFilter variants"
```

---

### Task 4: NumericList field type + ContainsRange filter (#125)

**Files:**
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/FeatureField.java`
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/CbrFilter.java`
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/CbrFeatureValidator.java`
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/CbrSimilarityScorer.java`
- Modify: `memory-api/src/test/java/io/casehub/neocortex/memory/cbr/FeatureFieldTest.java`
- Modify: `memory-api/src/test/java/io/casehub/neocortex/memory/cbr/CbrFilterTest.java`
- Modify: `memory-cbr-inmem/src/main/java/io/casehub/neocortex/memory/cbr/inmem/InMemoryCbrCaseMemoryStore.java`
- Modify: `memory-qdrant/src/main/java/io/casehub/neocortex/memory/cbr/qdrant/CbrQueryTranslator.java`
- Modify: `memory-qdrant/src/main/java/io/casehub/neocortex/memory/cbr/qdrant/CbrCollectionManager.java`
- Modify: `memory-qdrant/src/main/java/io/casehub/neocortex/memory/cbr/qdrant/QdrantCbrCaseMemoryStore.java`
- Modify: `memory-testing/src/main/java/io/casehub/neocortex/memory/cbr/testing/CbrCaseMemoryStoreContractTest.java`

**Interfaces:**
- Produces: `FeatureField.NumericList(String name, double min, double max)` — filter-only
- Produces: `CbrFilter.ContainsRange(NumericRange range)` — valid only on NumericList
- Produces: `FeatureField.numericList(String, double, double)` factory method
- Produces: `CbrFilter.containsRange(NumericRange)` factory method

- [ ] **Step 1: Write contract tests for NumericList**

Add to `CbrCaseMemoryStoreContractTest.java`. First add a schema helper:

```java
private void registerNumericListSchema() {
    store().registerSchema(CbrFeatureSchema.of("player-stats",
        FeatureField.categorical("region"),
        FeatureField.numericList("scores", 0, 100)));
}

private String storeNumericListCase(String problem, Map<String, Object> features, String caseId) {
    return store().store(
        new FeatureVectorCbrCase(problem, "solution", null, null, features),
        "player-stats", ENTITY, CBR, TENANT, caseId);
}
```

Then add tests:

```java
@Test
void numericList_storeAndRetrieve() {
    registerNumericListSchema();
    storeNumericListCase("high scorer", Map.of("region", "NA", "scores", List.of(85, 92, 78)), "c1");
    var q = CbrQuery.of(TENANT, CBR, "player-stats", Map.of("region", "NA"), 10)
        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
    var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
    assertThat(results).hasSize(1);
}

@Test
void numericList_containsRange_matchesElementInRange() {
    registerNumericListSchema();
    storeNumericListCase("has-90s", Map.of("region", "NA", "scores", List.of(85, 92, 78)), "c1");
    storeNumericListCase("no-90s", Map.of("region", "NA", "scores", List.of(50, 60, 70)), "c2");

    var q = CbrQuery.of(TENANT, CBR, "player-stats", Map.of(), 10)
        .withFilter("scores", CbrFilter.containsRange(new NumericRange(90, 100)))
        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
    var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).cbrCase().problem()).isEqualTo("has-90s");
}

@Test
void numericList_containsRange_noMatch() {
    registerNumericListSchema();
    storeNumericListCase("low", Map.of("region", "NA", "scores", List.of(10, 20, 30)), "c1");

    var q = CbrQuery.of(TENANT, CBR, "player-stats", Map.of(), 10)
        .withFilter("scores", CbrFilter.containsRange(new NumericRange(90, 100)))
        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
    var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
    assertThat(results).isEmpty();
}

@Test
void numericList_validation_queryFeaturesRejected() {
    registerNumericListSchema();
    assertThatThrownBy(() -> {
        var q = CbrQuery.of(TENANT, CBR, "player-stats",
            Map.of("scores", List.of(50, 60)), 10);
        store().retrieveSimilar(q, FeatureVectorCbrCase.class);
    }).isInstanceOf(IllegalArgumentException.class);
}

@Test
void numericList_validation_storeNonNumberRejected() {
    registerNumericListSchema();
    assertThatThrownBy(() -> storeNumericListCase("bad", Map.of("scores", List.of("not-a-number")), "bad"))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test
void numericList_validation_containsRangeOnCategoricalList_rejected() {
    registerStructuredSchema();
    assertThatThrownBy(() -> {
        var q = CbrQuery.of(TENANT, CBR, "structured-game", Map.of(), 10)
            .withFilter("phases", CbrFilter.containsRange(new NumericRange(1, 5)));
        store().retrieveSimilar(q, FeatureVectorCbrCase.class);
    }).isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Add NumericList to FeatureField**

In `FeatureField.java`, add the record in the permits clause and as a new record:

```java
record NumericList(String name, double min, double max) implements FeatureField {
    public NumericList {
        Objects.requireNonNull(name, "name");
        if (min > max) {
            throw new IllegalArgumentException(
                    "min must be <= max, got min=" + min + " max=" + max);
        }
    }
}
```

Add to `validateFlatFields()`:

```java
case NumericList nl -> throw new IllegalArgumentException(
        "Inner fields must be flat (Categorical/Numeric/Text), got: NumericList");
```

Add factory method:

```java
static FeatureField numericList(String name, double min, double max) {
    return new NumericList(name, min, max);
}
```

- [ ] **Step 3: Add ContainsRange to CbrFilter**

In `CbrFilter.java`:

```java
record ContainsRange(NumericRange range) implements CbrFilter {
    public ContainsRange {
        Objects.requireNonNull(range, "range");
    }
}

static ContainsRange containsRange(NumericRange range) { return new ContainsRange(range); }
```

- [ ] **Step 4: Update CbrFeatureValidator**

In `validateStoreFeatures()`, add:

```java
case FeatureField.NumericList nl -> {
    if (!(value instanceof List<?> list))
        throw new IllegalArgumentException(
            "NumericList field '" + entry.getKey() + "' requires List, got: "
            + value.getClass().getSimpleName());
    for (Object elem : list) {
        if (!(elem instanceof Number num))
            throw new IllegalArgumentException(
                "NumericList field '" + entry.getKey() + "' requires List<Number>, element is: "
                + elem.getClass().getSimpleName());
        double d = num.doubleValue();
        if (d < nl.min() || d > nl.max())
            throw new IllegalArgumentException(
                "NumericList field '" + entry.getKey() + "' element " + d
                + " outside range [" + nl.min() + ", " + nl.max() + "]");
    }
}
```

In `validateQueryFeatures()`, add:

```java
case FeatureField.NumericList nl -> throw new IllegalArgumentException(
    "Structured field '" + entry.getKey() + "' must be queried via filters, not features");
```

In `validateFilters()`, add to the switch:

```java
case CbrFilter.ContainsRange cr -> requireNumericList(name, field);
```

Add `requireNumericList()`:

```java
private static void requireNumericList(String name, FeatureField field) {
    if (field instanceof FeatureField.TimeSeries || field instanceof FeatureField.DiscreteSequence)
        throw new IllegalArgumentException(
            "Temporal field '" + name + "' does not support filters");
    if (!(field instanceof FeatureField.NumericList))
        throw new IllegalArgumentException(
            "ContainsRange filter on '" + name
            + "' requires NumericList field, got: " + field.getClass().getSimpleName());
}
```

- [ ] **Step 5: Update CbrSimilarityScorer**

In `localSimilarity()`, add:

```java
case FeatureField.NumericList nl -> throw new IllegalStateException("Structured field in scorer");
```

In `score()`, add NumericList to the skip condition:

```java
if (field instanceof FeatureField.CategoricalList
    || field instanceof FeatureField.NestedObject
    || field instanceof FeatureField.ObjectList
    || field instanceof FeatureField.NumericList) {continue;}
```

- [ ] **Step 6: Update InMemoryCbrCaseMemoryStore**

In `matchesFilters()`, add to the switch:

```java
case CbrFilter.ContainsRange cr ->
    storedValue instanceof List<?> list && list.stream()
        .filter(Number.class::isInstance)
        .map(Number.class::cast)
        .anyMatch(n -> n.doubleValue() >= cr.range().min()
                    && n.doubleValue() <= cr.range().max());
```

- [ ] **Step 7: Update CbrQueryTranslator**

In `toFilter()`, add to the FeatureField switch:

```java
case FeatureField.NumericList nl -> throw new IllegalStateException(
    "Structured field in toFilter — use applyStructuralFilters");
```

In `applyStructuralFilters()`, add to the CbrFilter switch:

```java
case CbrFilter.ContainsRange cr -> builder.addMust(ConditionFactory.range(payloadKey,
    Range.newBuilder().setGte(cr.range().min()).setLte(cr.range().max()).build()));
```

- [ ] **Step 8: Update CbrCollectionManager and QdrantCbrCaseMemoryStore**

In `CbrCollectionManager.registerSchemaIndexes()`, add NumericList to the FeatureField switch — create a float payload index.

In `QdrantCbrCaseMemoryStore.buildTextOverrides()`, add NumericList to the FeatureField switch (empty handler — no text semantics).

- [ ] **Step 9: Run tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory-api,memory-cbr-inmem,memory-testing,memory`
Expected: all PASS

- [ ] **Step 10: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/neocortex add memory-api/ memory-cbr-inmem/ memory-qdrant/ memory-testing/ memory/
git -C /Users/mdproctor/claude/casehub/neocortex commit -m "feat(#125): NumericList field type + ContainsRange filter"
```

---

### Task 5: Compound same-field filters — AllOf (#127)

**Files:**
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/CbrFilter.java`
- Modify: `memory-api/src/test/java/io/casehub/neocortex/memory/cbr/CbrFilterTest.java`
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/cbr/CbrFeatureValidator.java`
- Modify: `memory-cbr-inmem/src/main/java/io/casehub/neocortex/memory/cbr/inmem/InMemoryCbrCaseMemoryStore.java`
- Modify: `memory-qdrant/src/main/java/io/casehub/neocortex/memory/cbr/qdrant/CbrQueryTranslator.java`
- Modify: `memory-testing/src/main/java/io/casehub/neocortex/memory/cbr/testing/CbrCaseMemoryStoreContractTest.java`

**Interfaces:**
- Consumes: All CbrFilter variants from Tasks 3 and 4 (NotContains, NotContainsAny, ContainsRange)
- Produces: `CbrFilter.AllOf(List<CbrFilter> filters)` — ≥ 2 inner filters, no nested AllOf
- Produces: `CbrFilter.allOf(CbrFilter...)` factory method

- [ ] **Step 1: Write contract tests for AllOf**

Add to `CbrCaseMemoryStoreContractTest.java`:

```java
@Test
void structuredFields_allOf_twoHasMatch_onObjectList() {
    registerStructuredSchema();
    storeStructuredCase("both-match", Map.of("events", List.of(
        Map.of("eventType", "FIRST_CONTACT", "minute", 3),
        Map.of("eventType", "BATTLE_WON", "minute", 8))), "c1");
    storeStructuredCase("only-one", Map.of("events", List.of(
        Map.of("eventType", "FIRST_CONTACT", "minute", 3))), "c2");

    var q = CbrQuery.of(TENANT, CBR, "structured-game", Map.of(), 10)
        .withFilter("events", CbrFilter.allOf(
            CbrFilter.hasMatch(Map.of("eventType", "FIRST_CONTACT")),
            CbrFilter.hasMatch(Map.of("eventType", "BATTLE_WON"))))
        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
    var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).cbrCase().problem()).isEqualTo("both-match");
}

@Test
void structuredFields_allOf_containsAndNotContains() {
    registerStructuredSchema();
    storeStructuredCase("has-rush-no-cheese", Map.of("phases", List.of("RUSH", "MACRO")), "c1");
    storeStructuredCase("has-both", Map.of("phases", List.of("RUSH", "CHEESE")), "c2");
    storeStructuredCase("has-neither", Map.of("phases", List.of("MACRO", "LATE")), "c3");

    var q = CbrQuery.of(TENANT, CBR, "structured-game", Map.of(), 10)
        .withFilter("phases", CbrFilter.allOf(
            CbrFilter.contains("RUSH"),
            CbrFilter.notContains("CHEESE")))
        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
    var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).cbrCase().problem()).isEqualTo("has-rush-no-cheese");
}

@Test
void structuredFields_allOf_validation_requiresMinTwoFilters() {
    assertThatThrownBy(() -> CbrFilter.allOf(CbrFilter.contains("A")))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test
void structuredFields_allOf_validation_rejectsNestedAllOf() {
    assertThatThrownBy(() -> CbrFilter.allOf(
        CbrFilter.contains("A"),
        CbrFilter.allOf(CbrFilter.contains("B"), CbrFilter.contains("C"))))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test
void structuredFields_allOf_validation_innerFilterTypeMismatch() {
    registerStructuredSchema();
    assertThatThrownBy(() -> {
        var q = CbrQuery.of(TENANT, CBR, "structured-game", Map.of(), 10)
            .withFilter("phases", CbrFilter.allOf(
                CbrFilter.contains("A"),
                CbrFilter.hasMatch(Map.of("x", "y"))));
        store().retrieveSimilar(q, FeatureVectorCbrCase.class);
    }).isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Add AllOf to CbrFilter**

In `CbrFilter.java`:

```java
record AllOf(List<CbrFilter> filters) implements CbrFilter {
    public AllOf {
        Objects.requireNonNull(filters, "filters");
        if (filters.size() < 2) throw new IllegalArgumentException("AllOf requires at least 2 filters");
        for (CbrFilter f : filters) {
            if (f instanceof AllOf) throw new IllegalArgumentException("AllOf cannot contain nested AllOf");
        }
        filters = List.copyOf(filters);
    }
}

static AllOf allOf(CbrFilter... filters) { return new AllOf(List.of(filters)); }
```

- [ ] **Step 3: Update CbrFeatureValidator.validateFilters()**

Add to the switch:

```java
case CbrFilter.AllOf allOf -> {
    for (CbrFilter inner : allOf.filters()) {
        validateSingleFilter(name, inner, field, innerFields);
    }
}
```

This requires extracting the per-filter validation from the existing switch into a `validateSingleFilter()` helper method that handles Contains, ContainsAll, ContainsAny, NotContains, NotContainsAny, ContainsRange, and HasMatch — then calling it for each inner filter in AllOf.

- [ ] **Step 4: Update InMemoryCbrCaseMemoryStore.matchesFilters()**

Add to the switch:

```java
case CbrFilter.AllOf allOf -> {
    boolean allMatch = true;
    for (CbrFilter inner : allOf.filters()) {
        boolean innerMatch = matchesSingleFilter(storedValue, inner, field);
        if (!innerMatch) { allMatch = false; break; }
    }
    yield allMatch;
}
```

This requires extracting the per-filter evaluation from the existing switch into a `matchesSingleFilter()` helper.

- [ ] **Step 5: Update CbrQueryTranslator.applyStructuralFilters()**

Add to the switch:

```java
case CbrFilter.AllOf allOf -> {
    for (CbrFilter inner : allOf.filters()) {
        applyFilter(builder, payloadKey, inner, field);
    }
}
```

This requires extracting the per-filter Qdrant translation from the existing switch into an `applyFilter()` helper that preserves polarity (positive → `addMust`, negative → `addMustNot`).

- [ ] **Step 6: Run tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory-api,memory-cbr-inmem,memory-testing,memory`
Expected: all PASS

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/neocortex add memory-api/ memory-cbr-inmem/ memory-qdrant/ memory-testing/
git -C /Users/mdproctor/claude/casehub/neocortex commit -m "feat(#127): AllOf compound same-field filter"
```

---

### Task 6: Build verification + CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md` (module descriptions)

- [ ] **Step 1: Full build**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install`
Expected: BUILD SUCCESS

- [ ] **Step 2: Update CLAUDE.md**

Update the module descriptions to document the new types:
- `memory-api` section: add WarpingConstraint, NumericList, ContainsRange, NotContains, NotContainsAny, AllOf to the relevant descriptions
- `memory-testing` section: update test count

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/neocortex add CLAUDE.md
git -C /Users/mdproctor/claude/casehub/neocortex commit -m "docs(#125,#126,#127,#138,#139): update CLAUDE.md for CBR enhancements"
```
