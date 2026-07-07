# Retrieval Tracking Retention + Cross-Encoder Reranking Design

**Issues:** #110 (retention), #121 (reranking)
**Date:** 2026-07-07
**Status:** Approved

---

## 1. Module Rename: `rag-crag/` → `rag-crossencoder/`

### Rationale

Both corrective RAG (CRAG) and the new reranking decorator depend on `inference-tasks`
for `CrossEncoderReranker`. The module boundary should align with the dependency boundary:
one optional dependency = one module. Two features within that module, each independently
activated via `@IfBuildProperty`.

Keeping them in separate modules (`rag-crag/` + `rag-reranking/`) would create two modules
with identical `inference-tasks` dependency and lose the opportunity for score propagation
(eliminating double cross-encoder inference when both are enabled).

**Note:** Issue #121 originally placed reranking in `rag/` alongside `HybridCaseRetriever`.
This spec places it in `rag-crossencoder/` instead because: (a) reranking shares the
`inference-tasks` dependency with CRAG — one optional dep = one module, (b) score
propagation requires `RerankingLogic` and `CrossEncoderRelevanceEvaluator` to share a
metadata key constant, which is natural within a module but requires a cross-module
dependency otherwise, (c) the bean producer serves both features. This deviation from
the issue is intentional.

### Changes

| Before | After |
|--------|-------|
| Module dir: `rag-crag/` | `rag-crossencoder/` |
| ArtifactId: `casehub-neocortex-rag-crag` | `casehub-neocortex-rag-crossencoder` |
| Package root: `io.casehub.neocortex.rag.crag` | `io.casehub.neocortex.rag.crossencoder` |

Sub-package layout within the renamed module:

```
io.casehub.neocortex.rag.crossencoder
├── CrossEncoderBeanProducer          (renamed from CragBeanProducer, shared)
├── corrective/
│   ├── CorrectiveCaseRetriever       (@Decorator @Priority(100))
│   ├── ReactiveCorrectiveCaseRetriever
│   ├── CrossEncoderRelevanceEvaluator
│   ├── CragEvaluationLogic
│   └── CragConfig                    (prefix stays casehub.rag.crag)
└── reranking/
    ├── RerankingCaseRetriever        (@Decorator @Priority(75))
    ├── ReactiveRerankingCaseRetriever
    ├── RerankingLogic
    └── RerankingConfig               (prefix: casehub.rag.reranking)
```

Config property prefixes do NOT change — `casehub.rag.crag.*` stays the same for
existing CRAG properties. No config breaking change.

### Propagation scope

The `rag-crag` artifactId is referenced only within this repo:
- Parent `pom.xml` module list
- Example modules (if any)
- `CLAUDE.md` module table
- `ARC42STORIES.MD` references

No cross-repo consumers (Hortora/engine consumes `rag-api`, `rag`, `rag-testing` — not `rag-crag`).

---

## 2. RerankingCaseRetriever — Cross-Encoder Reranking Decorator

### Decorator Chain

```
Client → Tracking(50) → Reranking(75) → CRAG(100) → Expansion(200) → HybridCaseRetriever
```

Priority 75 places reranking between Tracking (outermost, records what the caller sees)
and CRAG (quality-gates the candidate pool). The reranker overfetches from CRAG, then
sorts survivors by cross-encoder score and truncates to the caller's limit.

### Blocking Variant

```java
@Decorator
@Priority(75)
@Unremovable
@IfBuildProperty(name = "casehub.rag.reranking.enabled", stringValue = "true")
public class RerankingCaseRetriever implements CaseRetriever {

    private final CaseRetriever delegate;
    private final CrossEncoderReranker reranker;
    private final RerankingConfig config;

    @Inject
    RerankingCaseRetriever(@Delegate @Any CaseRetriever delegate,
                           CrossEncoderReranker reranker,
                           RerankingConfig config) {
        this.delegate = delegate;
        this.reranker = reranker;
        this.config = config;
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus,
                                          int maxResults, PayloadFilter filter) {
        int fetchSize = Math.max(maxResults, config.rerankPoolSize());
        List<RetrievedChunk> candidates = delegate.retrieve(query, corpus, fetchSize, filter);
        if (RerankingLogic.isAlreadyReranked(candidates)) {
            return candidates.subList(0, Math.min(candidates.size(), maxResults));
        }
        return RerankingLogic.stamp(
            RerankingLogic.rerank(reranker, query.text(), candidates, maxResults));
    }
}
```

### Reactive Variant

`ReactiveRerankingCaseRetriever implements ReactiveCaseRetriever`. Cross-encoder call
runs on `Infrastructure.getDefaultWorkerPool()` (follows CRAG's reactive pattern).

### Overfetch Strategy

`max(limit, rerankPoolSize)` — not `limit × multiplier`. Prevents compound overfetch
when callers (e.g. adaptive search) already pass inflated limits.

| Caller | Limit passed | Reranker fetches | Compound? |
|--------|-------------|-----------------|-----------|
| Direct search (limit=16) | 16 | max(16, 30) = 30 | No |
| Adaptive (limit=32) | 32 | max(32, 30) = 32 | No |
| Small request (limit=5) | 5 | max(5, 30) = 30 | No |

**Deployment prerequisite (ColBERT only):** The server-side ColBERT reranking pool
`casehub.rag.retrieval.rerankTopN` (default 10) caps the fusion prefetch limit
in `HybridCaseRetriever` — but ONLY when server-side ColBERT reranking is also
enabled (`casehub.rag.retrieval.rerankEnabled=true` with ColBERT embeddings
available). When ColBERT is NOT enabled, `HybridCaseRetriever` passes `maxResults`
directly to Qdrant without a `rerankTopN` cap — the reranker's overfetched
`fetchSize` flows through uncapped. A startup warning should be logged only when
ALL of: client-side reranking enabled, server-side ColBERT reranking enabled,
AND `rerankPoolSize > rerankTopN`. Hortora/engine#41 tracks increasing
`rerankTopN` to 30–40.

### Configuration

```java
@ConfigMapping(prefix = "casehub.rag.reranking")
public interface RerankingConfig {
    @WithDefault("false")
    boolean enabled();

    @WithDefault("30")
    int rerankPoolSize();
}
```

### RerankingLogic — Static Utility

| Method | Purpose |
|--------|---------|
| `rerank(reranker, queryText, chunks, maxResults)` | Checks for pre-computed scores, falls back to cross-encoder, sorts, truncates |
| `hasPrecomputedScores(chunks)` | Checks metadata for `_crossEncoderScore` key |
| `attachScores(chunks, scores)` | Returns new chunks with cross-encoder scores in metadata |
| `isAlreadyReranked(chunks)` | Duplicate-application guard — checks for `_reranked` metadata key (distinct from `_crossEncoderScore` to avoid false positives from CRAG score propagation) |
| `stamp(chunks)` | Returns new chunks with `_reranked=true` in metadata — prevents double-application through blocking-to-reactive bridge |

**`rerank()` contract:**

- **Text extraction:** Uses `RetrievedChunk.content()` as cross-encoder input.
- **Index mapping:** `RankedResult.originalIndex()` preserves positional correspondence
  between the input chunk list and the ranked results.
- **Score handling:** The original `relevanceScore` field on `RetrievedChunk` is NOT
  updated — it preserves the original retrieval/fusion score. The cross-encoder score
  is stored in metadata under `_crossEncoderScore`. Both signals are available to
  `TrackingCaseRetriever` (which records `relevanceScore` in `retrieved_documents`)
  and to downstream analytics.
- **Return:** Sorted by cross-encoder score descending, truncated to `maxResults`.

### Score Propagation

Score propagation eliminates double cross-encoder inference when both CRAG and
reranking are enabled. The mechanism:

1. `CrossEncoderRelevanceEvaluator` exposes a concrete-class method
   `evaluateBatchWithScores(String query, List<String> chunkContents)` returning
   `List<ScoredGrade>` where `ScoredGrade` is `record ScoredGrade(RelevanceGrade grade, float score)`.
   Both types live in the `crossencoder` package (not in the `rag-api` SPI).

2. The existing `evaluateBatch()` delegates to `evaluateBatchWithScores()` internally,
   extracting only grades. The `RelevanceEvaluator` SPI remains unchanged.

3. `CorrectiveCaseRetriever` and `ReactiveCorrectiveCaseRetriever` both inject
   `RelevanceEvaluator` as before (preserving test compatibility with
   `InMemoryRelevanceEvaluator`). At ALL call sites where `evaluator.evaluateBatch()`
   is invoked — both the initial evaluation AND the expansion evaluation — when the
   injected evaluator is a `CrossEncoderRelevanceEvaluator`, it calls
   `evaluateBatchWithScores()` to obtain both grades and scores. It passes grades to
   `CragEvaluationLogic.gradeChunks()` unchanged, then calls
   `RerankingLogic.attachScores()` on the surviving chunks to write scores into
   metadata. This ensures ALL survivors (initial and expansion) carry
   `_crossEncoderScore` — the reranker never sees a mix of scored and unscored chunks.

4. When the reranker decorator runs (priority 75), `RerankingLogic.hasPrecomputedScores()`
   checks chunk metadata for `_crossEncoderScore`. If present, it sorts by pre-computed
   scores and skips the cross-encoder call entirely.

Both classes share the metadata key constant directly within the same module.

When reranking runs WITHOUT CRAG (CRAG disabled, reranking enabled), no
pre-computed scores exist. The reranker runs the cross-encoder itself.

### CrossEncoderBeanProducer

Replaces `CragBeanProducer`. Produces `RelevanceEvaluator` for CRAG (unchanged).
Validates `CrossEncoderReranker` bean availability. Always active when the module
is on the classpath (no `@IfBuildProperty` gate — CDI `@IfBuildProperty` doesn't
support OR-gating). Uses `Instance<CrossEncoderReranker>` for graceful error if
no reranker bean is configured.

**Activation semantics when both features are disabled:** CDI `@Produces` methods
are lazy — they fire only when a bean of that type is injected. When both CRAG and
reranking are disabled, their decorators (`CorrectiveCaseRetriever`, `RerankingCaseRetriever`)
are gated by `@IfBuildProperty` and never instantiated. No injection of `RelevanceEvaluator`
or `CrossEncoderReranker` occurs, so the producer methods are never called. The producer
bean instance itself is created (it's `@ApplicationScoped`) but remains inert — no
validation, no exceptions. `Instance<CrossEncoderReranker>` is a CDI lookup handle
that does not eagerly resolve the target bean.

The `CrossEncoderReranker` bean is produced by the consuming app:
```java
@Produces @ApplicationScoped
CrossEncoderReranker reranker(@Inference("reranker") InferenceModel model) {
    return new CrossEncoderReranker(model);
}
```

---

## 3. Retrieval Tracking Data Retention (#110)

### SPI Change

Add to `RetrievalTracker`:
```java
int purgeOlderThan(Instant cutoff);
```

Add to `ReactiveRetrievalTracker`:
```java
Uni<Integer> purgeOlderThan(Instant cutoff);
```

Returns count of deleted retrieval records (parent rows only, not child rows)
for observability. Every implementation must support it.

### SqliteRetrievalTracker Implementation

Cascade delete in code — SQLite foreign keys lack CASCADE and can't be ALTERed.
Single transaction, children first:

```sql
DELETE FROM retrieval_feedback WHERE retrieval_id IN
    (SELECT retrieval_id FROM retrieval_records WHERE timestamp < ?);
DELETE FROM retrieved_documents WHERE retrieval_id IN
    (SELECT retrieval_id FROM retrieval_records WHERE timestamp < ?);
DELETE FROM retrieval_records WHERE timestamp < ?;
```

The existing composite index `idx_records_corpus_ts ON (tenant_id, corpus_name, timestamp)`
does NOT cover a bare `WHERE timestamp < ?` predicate — its leading columns are
`tenant_id` and `corpus_name`. The purge query performs a full table scan. This is
acceptable: the table is bounded by the retention period (≤90 days of data), and the
purge runs once per 24 hours on the cold path. Adding a dedicated `timestamp` index
would optimise the cold path at the cost of write overhead on every INSERT (the hot
path — every retrieval call). No Flyway migration needed.

### InMemoryRetrievalTracker Implementation

Filter the `records` list and `feedbackIndex` map by timestamp.

### BlockingToReactiveRetrievalTracker Bridge

```java
@Override
public Uni<Integer> purgeOlderThan(Instant cutoff) {
    return Uni.createFrom().item(() -> delegate.purgeOlderThan(cutoff))
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
}
```

### Contract Tests

Add to `RetrievalTrackerContractTest`:

| Test | Asserts |
|------|---------|
| `purge_deletesOldRecordsAndChildren` | Record before cutoff is purged with its documents and feedback |
| `purge_preservesRecentRecordsAndChildren` | Record after cutoff survives with its documents and feedback intact |
| `purge_returnsDeletedCount` | Return value matches deleted record count |
| `purge_emptyWhenNothingOld` | Returns 0 when nothing to purge |

### RetentionScheduler

`@ApplicationScoped` in `rag-tracking/`. Uses `ScheduledExecutorService` (pure Java,
avoids `quarkus-scheduler` extension dep — `rag-tracking/` is a library JAR per
`library-jar-annotation-only-deps` protocol).

```java
@ApplicationScoped
@IfBuildProperty(name = "casehub.rag.tracking.enabled", stringValue = "true")
public class RetentionScheduler {
    @Inject RetrievalTracker tracker;

    @ConfigProperty(name = "casehub.rag.tracking.retention.days", defaultValue = "90")
    int retentionDays;

    private ScheduledExecutorService executor;

    @PostConstruct void start() {
        if (retentionDays <= 0) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rag-retention-purge");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::purge, 1, 24, TimeUnit.HOURS);
    }

    @PreDestroy void stop() {
        if (executor != null) executor.shutdown();
    }

    void purge() {
        try {
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            int deleted = tracker.purgeOlderThan(cutoff);
            LOG.infof("Retention purge: deleted %d records older than %s", deleted, cutoff);
        } catch (Exception e) {
            LOG.error("Retention purge failed — will retry next cycle", e);
        }
    }
}
```

Configuration: `casehub.rag.tracking.retention.days=90` (default).
Set ≤0 to disable automatic purging. First purge 1 hour after startup,
then every 24 hours.

---

## 4. CLAUDE.md Updates

After implementation:
- Module table: remove `rag-crag`, add `rag-crossencoder` with both features
- Module description for `rag-tracking`: add retention scheduler
- `RetrievalTracker` SPI description: add `purgeOlderThan`
- Maven coordinates table: rename artifact

---

## 5. Files Changed Summary

### New files
- `rag-crossencoder/src/.../ScoredGrade.java`
- `rag-crossencoder/src/.../reranking/RerankingCaseRetriever.java`
- `rag-crossencoder/src/.../reranking/ReactiveRerankingCaseRetriever.java`
- `rag-crossencoder/src/.../reranking/RerankingLogic.java`
- `rag-crossencoder/src/.../reranking/RerankingConfig.java`
- `rag-crossencoder/src/test/.../reranking/RerankingCaseRetrieverTest.java`
- `rag-crossencoder/src/test/.../reranking/ReactiveRerankingCaseRetrieverTest.java`
- `rag-crossencoder/src/test/.../reranking/RerankingLogicTest.java`
- `rag-tracking/src/.../RetentionScheduler.java`
- `rag-tracking/src/test/.../RetentionSchedulerTest.java`

### Renamed/moved files (module rename)
- All files in `rag-crag/` → `rag-crossencoder/` with package change

### Modified files
- `rag-api/.../RetrievalTracker.java` — add `purgeOlderThan`
- `rag-api/.../ReactiveRetrievalTracker.java` — add `purgeOlderThan`
- `rag-tracking/.../SqliteRetrievalTracker.java` — implement `purgeOlderThan`
- `rag-tracking/.../BlockingToReactiveRetrievalTracker.java` — bridge `purgeOlderThan`
- `rag-testing/.../InMemoryRetrievalTracker.java` — implement `purgeOlderThan`
- `rag-testing/.../RetrievalTrackerContractTest.java` — add purge tests
- `rag-crossencoder/.../corrective/CrossEncoderRelevanceEvaluator.java` — add `evaluateBatchWithScores()`
- `rag-crossencoder/.../corrective/CorrectiveCaseRetriever.java` — score propagation via `RerankingLogic.attachScores()` at both initial and expansion call sites
- `rag-crossencoder/.../corrective/ReactiveCorrectiveCaseRetriever.java` — same score propagation changes as blocking variant
- `rag-crossencoder/.../CrossEncoderBeanProducer.java` — renamed from CragBeanProducer, gate on either feature
- `pom.xml` (parent) — rename module entry
- `CLAUDE.md` — module table, maven coordinates, descriptions
