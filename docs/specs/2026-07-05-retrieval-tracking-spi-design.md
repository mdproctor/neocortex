# Retrieval Tracking SPI — Design Spec

**Issue:** casehubio/neocortex#105
**Date:** 2026-07-05
**Status:** Approved

## Problem

RAG consumers have no way to record which documents are returned per query
or whether those documents actually helped. Without this signal, corpus
curation is blind — there is no data to distinguish high-value entries from
dead weight.

Hortora/engine#24 needs retrieval frequency and quality tracking for a
~6,500 entry garden corpus with suspected low signal-to-noise.

## Design Decisions

1. **Multi-stage feedback loop, not counters.** A simple hit counter misses
   the critical question: was the document useful? The SPI records retrieval
   events (which documents came back for which query) and accepts explicit
   client feedback (graduated outcome assessment per document). These are
   two separate events — retrieval is observed at retrieve time; feedback
   is reported asynchronously, possibly much later.

   Note: Feedback (outcome assessment) extends beyond the explicit
   requirements of #105 and Hortora/engine#24, which request only retrieval
   frequency recording. It is included as forward-looking design because:
   (a) the schema is easier to extend now than migrate later, (b) the
   feedback types form a cohesive model with the recording types, and
   (c) with decorator-based recording, feedback adds no complexity to the
   recording path.

2. **Decorator-based recording, not explicit `record()` calls.** A
   `TrackingCaseRetriever` CDI decorator (`@Decorator @Priority(50)`)
   intercepts `retrieve()`, delegates to the next decorator in the chain,
   records the returned results via `RetrievalTracker.record()`, and fires
   a `RetrievalRecorded` CDI event carrying the generated `retrievalId`.
   This follows the pattern established by `CorrectiveCaseRetriever`
   (`@Priority(100)`) and `QueryExpandingCaseRetriever` (`@Priority(200)`).

   At `@Priority(50)`, the tracking decorator is the outermost wrapper —
   in CDI, lower priority values are called first (outermost). It captures
   the final results after CRAG filtering and query expansion have run.
   Consumers get tracking for free when `rag-tracking` is on the classpath
   with `casehub.rag.tracking.enabled=true`; consumers that forget tracking
   or don't know about it are never silently untracked.

   Consumers that need the `retrievalId` for feedback observe the
   `Event<RetrievalRecorded>` CDI event — the same mechanism CRAG uses
   for `RetrievalQuality`.

   **Double-recording guard.** When the default `BlockingToReactiveCaseRetriever`
   bridge is active, a reactive consumer's call traverses both decorator
   chains — the reactive tracking decorator wraps the bridge, which
   delegates to the decorated blocking `CaseRetriever` including the
   blocking tracking decorator. Without a guard, both decorators record
   the same retrieval. The solution follows CRAG's `isAlreadyGraded()`
   pattern: the first tracking decorator to record adds a `_trackingId`
   metadata key to the returned chunks; the second decorator detects this
   key via `isAlreadyTracked()` and skips recording. When the bridge is
   displaced by a native reactive implementation, each path (blocking,
   reactive) has exactly one decorator — no guard fires, no skipping
   needed. Correct in all deployment modes.

   **Failure isolation.** Unlike CRAG, which transforms the retrieval result
   and whose failures are semantically meaningful to the caller, tracking
   is purely observational — it never affects which chunks are returned.
   The decorator catches all exceptions from `tracker.record()` and
   `event.fire()`/`fireAsync()`, logs them, and returns the delegate's
   results unchanged. Tracking failures are never propagated to the
   caller. This is a behavioral contract, not an implementation
   suggestion — an implementor must not follow CRAG's exception
   propagation pattern here.

3. **One feedback call with graduated outcome.** Rather than separate
   "accessed" and "rated" calls, a single `feedback()` call captures the
   outcome. If a client reports feedback, they necessarily accessed the
   document. The absence of feedback after retrieval is itself a signal
   (document was returned but never engaged with).

4. **Recording SPI with minimal data access, analysis separate.** The SPI
   records events and provides basic time-windowed read methods plus a
   `findRetrievedDocumentIds()` set query for zero-retrieval identification.
   A separate analysis service (future work, tracked as a follow-up issue)
   computes aggregations, identifies low-value documents, and builds the
   query→document→outcome weight graph. The SPI does not compute
   "improvement" itself.

5. **Storage-agnostic SPI, SQLite first backend.** The SPI abstracts
   storage so that in-memory (tests), SQLite (lightweight single-node),
   and future backends (PostgreSQL, etc.) are interchangeable behind
   the CDI priority ladder — same pattern as the memory modules.

## Value Types (rag-api)

### RetrievalOutcome

Graduated enum for client feedback on document usefulness:

| Value | Meaning |
|-------|---------|
| `NOT_RELEVANT` | Document was off-topic for the query |
| `PARTIALLY_RELEVANT` | Some useful content, didn't fully meet the need |
| `RELEVANT` | Document met the need |
| `HIGHLY_RELEVANT` | Document was exactly what was needed |

Domain-appropriate categorical assessment — clients make a quick judgment
without agonising over decimal precision. The analysis layer can map to
ordinal values for aggregation.

### RetrievalRecord

Captured at retrieval time by the tracking decorator:

| Field | Type | Notes |
|-------|------|-------|
| `retrievalId` | `String` | Unique ID generated by the tracker |
| `query` | `RetrievalQuery` | Original query text + any expansion |
| `corpus` | `CorpusRef` | Tenant + corpus scoping |
| `documents` | `List<RetrievedDocumentRef>` | Slim projection: docId + score |
| `maxResults` | `int` | The `maxResults` parameter from the `retrieve()` call |
| `timestamp` | `Instant` | When the retrieval occurred |

`RetrievedDocumentRef` is a new value type carrying only `sourceDocumentId`
and `relevanceScore` — the full `RetrievedChunk` content is not stored.

When multiple chunks share the same `sourceDocumentId`, they are collapsed
into a single `RetrievedDocumentRef` with the maximum `relevanceScore`.

### RetrievalFeedback

Submitted asynchronously by the client:

| Field | Type | Notes |
|-------|------|-------|
| `retrievalId` | `String` | Links back to the retrieval event |
| `sourceDocumentId` | `String` | Which document this feedback is about |
| `outcome` | `RetrievalOutcome` | Graduated assessment |
| `timestamp` | `Instant` | When the feedback was submitted |

Feedback is idempotent per `(retrievalId, sourceDocumentId)` — a subsequent
call updates the existing outcome (UPSERT semantics, latest wins). A user
who reassesses a document's relevance can update their assessment.

### RetrievalRecorded

CDI event fired by the tracking decorator after recording a retrieval:

| Field | Type | Notes |
|-------|------|-------|
| `retrievalId` | `String` | Generated by the tracker |
| `query` | `RetrievalQuery` | Original query |
| `corpus` | `CorpusRef` | Tenant + corpus |
| `documents` | `List<RetrievedDocumentRef>` | Recorded documents |

Consumers that need the `retrievalId` for later `feedback()` calls observe
this event. The blocking decorator fires synchronously (`fire()`); the
reactive decorator fires asynchronously (`fireAsync()`).

## SPI Interfaces (rag-api)

### RetrievalTracker (blocking)

```java
public interface RetrievalTracker {
    String record(RetrievalQuery query, CorpusRef corpus,
                  List<RetrievedChunk> results, int maxResults);

    void feedback(String retrievalId, String sourceDocumentId,
                  RetrievalOutcome outcome);

    List<RetrievalRecord> findRecords(CorpusRef corpus,
                                      Instant since, Instant until);

    List<RetrievalFeedback> findFeedback(CorpusRef corpus,
                                          Instant since, Instant until);

    Set<String> findRetrievedDocumentIds(CorpusRef corpus,
                                          Instant since, Instant until);
}
```

`record()` accepts `List<RetrievedChunk>` for convenience but stores only
the slim `RetrievedDocumentRef` projection (docId + score). When multiple
chunks share the same `sourceDocumentId`, they are collapsed into a single
document ref with the maximum `relevanceScore`. Returns a `retrievalId`
used by the decorator to fire the `RetrievalRecorded` CDI event.

`record()` is called by the tracking decorator, not directly by consumers.
Consumers use `feedback()` and the `find*()` methods.

`feedback()` uses UPSERT semantics — calling it twice for the same
`(retrievalId, sourceDocumentId)` updates the existing record.

`findFeedback()` filters on `retrieval_feedback.timestamp` (when feedback
was submitted, not when the retrieval occurred).

`findRetrievedDocumentIds()` returns the distinct set of source document IDs
that appear in any retrieval record for the given corpus and time window.
Consumers can diff this against their corpus inventory (via
`CorpusReader.list()`) to identify documents with zero retrievals.

### ReactiveRetrievalTracker (non-blocking)

Mutiny `Uni` variants of all five methods. Safe to subscribe to from the
Vert.x event loop.

```java
public interface ReactiveRetrievalTracker {
    Uni<String> record(RetrievalQuery query, CorpusRef corpus,
                       List<RetrievedChunk> results, int maxResults);

    Uni<Void> feedback(String retrievalId, String sourceDocumentId,
                       RetrievalOutcome outcome);

    Uni<List<RetrievalRecord>> findRecords(CorpusRef corpus,
                                            Instant since, Instant until);

    Uni<List<RetrievalFeedback>> findFeedback(CorpusRef corpus,
                                               Instant since, Instant until);

    Uni<Set<String>> findRetrievedDocumentIds(CorpusRef corpus,
                                               Instant since, Instant until);
}
```

## Module Structure

| Module | Class | CDI Activation | Purpose |
|--------|-------|----------------|---------|
| `rag-api` | `RetrievalTracker`, `ReactiveRetrievalTracker`, value types | — | SPI + contracts |
| `rag-testing` | `InMemoryRetrievalTracker` | `@Alternative @Priority(1)` | ConcurrentHashMap, full query support |
| `rag-tracking` | `TrackingCaseRetriever` | `@Decorator @Priority(50)` | Blocking CaseRetriever tracking decorator |
| `rag-tracking` | `ReactiveTrackingCaseRetriever` | `@Decorator @Priority(50)` | Reactive CaseRetriever tracking decorator |
| `rag-tracking` | `SqliteRetrievalTracker` | `@ApplicationScoped` | SQLite + HikariCP WAL |
| `rag-tracking` | `BlockingToReactiveRetrievalTracker` | `@DefaultBean` | Bridge, displaced when reactive impl present |

New module: `rag-tracking` (artifactId: `casehub-neocortex-rag-tracking`).
Package: `io.casehub.neocortex.rag.tracking`.

Classpath-activated: adding `rag-tracking` as a dependency and setting
`casehub.rag.tracking.enabled=true` activates the tracking decorators and
SQLite storage. Same `@IfBuildProperty` activation pattern as CRAG.

No `NoOpRetrievalTracker` is needed. Without `rag-tracking` on the classpath,
no decorator exists and no tracking occurs — the same way there is no
`NoOpCorrectiveCaseRetriever`. The `BlockingToReactiveRetrievalTracker`
bridge is co-located in `rag-tracking` because it requires a
`RetrievalTracker` implementation to delegate to.

### Maven Dependencies

**rag-tracking:**
- `casehub-neocortex-rag-api` (SPI)
- `com.zaxxer:HikariCP` (connection pooling)
- `org.xerial:sqlite-jdbc` (SQLite driver)
- `io.quarkus:quarkus-arc` (CDI)
- `org.flywaydb:flyway-core` (schema migration)

## Configuration (rag-tracking)

Configuration uses `@ConfigProperty`, matching the `SqliteMemoryStore` pattern:

| Property | Default | Notes |
|----------|---------|-------|
| `casehub.rag.tracking.enabled` | `false` | Activates tracking decorators (`@IfBuildProperty`) |
| `casehub.rag.tracking.sqlite.path` | — | SQLite database file path (required) |
| `casehub.rag.tracking.sqlite.pool.max-size` | `5` | HikariCP maximum pool size |
| `casehub.rag.tracking.sqlite.busy-timeout-ms` | `5000` | SQLite busy timeout in milliseconds |

WAL journal mode and `PRAGMA synchronous=NORMAL` are set programmatically
at connection time for non-memory databases, matching `SqliteMemoryStore`.
For `:memory:` or blank paths, pool size is forced to 1.

## SQLite Schema (rag-tracking)

```sql
CREATE TABLE retrieval_records (
    retrieval_id   TEXT PRIMARY KEY,
    query_text     TEXT NOT NULL,
    expanded_text  TEXT,
    tenant_id      TEXT NOT NULL,
    corpus_name    TEXT NOT NULL,
    max_results    INTEGER NOT NULL,
    timestamp      TEXT NOT NULL
);

CREATE TABLE retrieved_documents (
    retrieval_id        TEXT NOT NULL REFERENCES retrieval_records(retrieval_id),
    source_document_id  TEXT NOT NULL,
    relevance_score     REAL NOT NULL,
    PRIMARY KEY (retrieval_id, source_document_id)
);

CREATE TABLE retrieval_feedback (
    retrieval_id        TEXT NOT NULL REFERENCES retrieval_records(retrieval_id),
    source_document_id  TEXT NOT NULL,
    outcome             TEXT NOT NULL,
    timestamp           TEXT NOT NULL,
    PRIMARY KEY (retrieval_id, source_document_id)
);

CREATE INDEX idx_records_corpus_ts
    ON retrieval_records(tenant_id, corpus_name, timestamp);

CREATE INDEX idx_feedback_retrieval
    ON retrieval_feedback(retrieval_id);

CREATE INDEX idx_feedback_ts
    ON retrieval_feedback(timestamp);
```

Timestamps stored as ISO-8601 text (SQLite has no native Instant type).
WAL mode enabled at connection time for concurrent read/write.

Chunk-to-document deduplication: `record()` collapses multiple chunks with
the same `sourceDocumentId` into a single row with `MAX(relevanceScore)`
before inserting into `retrieved_documents`.

Feedback idempotency: `feedback()` uses `INSERT OR REPLACE` (UPSERT) on
the `(retrieval_id, source_document_id)` primary key — latest outcome wins.

Schema migrations managed by Flyway (`classpath:db/rag-tracking/migration`).

## Client Usage

```java
// 1. Retrieve — tracking decorator records automatically
List<RetrievedChunk> chunks = retriever.retrieve(query, corpus, 10);
// TrackingCaseRetriever has already:
//   - called tracker.record(query, corpus, chunks, 10)
//   - fired Event<RetrievalRecorded> with the retrievalId

// 2. (Optional) Observe RetrievalRecorded to get retrievalId for feedback
// @Observes RetrievalRecorded event -> capturedId = event.retrievalId()

// 3. Client uses chunks, determines which helped
// ... (application logic) ...

// 4. Report feedback (requires rag-tracking dependency)
tracker.feedback(capturedId, "doc-42", RetrievalOutcome.HIGHLY_RELEVANT);
tracker.feedback(capturedId, "doc-17", RetrievalOutcome.NOT_RELEVANT);
```

## Testing Strategy

**InMemoryRetrievalTracker** in `rag-testing` provides full query support
backed by ConcurrentHashMap. Tests for record/feedback/find operations
run without SQLite or any external dependency.

**SqliteRetrievalTracker** tested via `@QuarkusTest` in `rag-tracking`
with a temp-file database. Tests cover: schema creation, record + feedback
round-trip, time-windowed queries, concurrent writes, WAL mode behaviour,
chunk deduplication, feedback UPSERT idempotency.

**Contract test:** Abstract `RetrievalTrackerContractTest` in `rag-testing`
defines the behavioural contract. Both InMemory and SQLite implementations
extend it — same pattern as `CbrCaseMemoryStoreContractTest`.

**Decorator integration test:** `@QuarkusTest` in `rag-tracking` verifying
that the decorator records retrievals and fires `RetrievalRecorded` events
when `casehub.rag.tracking.enabled=true`.

**Double-recording guard test:** `@QuarkusTest` with the
`BlockingToReactiveCaseRetriever` bridge active, verifying that a reactive
`retrieve()` call produces exactly one tracking record and one CDI event
despite both tracking decorators being in the call chain. Also tests the
native-reactive path (bridge displaced) to confirm each side records
independently.

**Failure isolation test:** Verifies that when `RetrievalTracker.record()`
throws, the decorator returns the delegate's chunks unchanged and logs the
failure. The caller's `retrieve()` must succeed with the correct results.

## What This Enables (future, not in scope for #105)

- Query→document→outcome weight graph for retrieval quality improvement
- Identification of never-retrieved documents (corpus dead weight) — enabled
  by `findRetrievedDocumentIds()` diffed against `CorpusReader.list()`
- Identification of frequently-retrieved-but-unhelpful documents
- Cross-validation with CRAG grades and embedding similarity scores
- Retrieval strategy A/B comparison via outcome distributions

## Out of Scope

- Analysis service (aggregation, curation recommendations) — follow-up issue
- Automatic re-ranking based on feedback
- UI for browsing tracking data
- Retention policy / data expiry (can be added to the SPI later)
- JPA/PostgreSQL backend (future module, same SPI)
