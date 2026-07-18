# Native Reactive Backends — Design Spec

**Issue:** casehubio/neocortex#101
**Date:** 2026-07-18
**Status:** Draft

## Problem

`ReactiveCaseMemoryStore`, `ReactiveCbrCaseMemoryStore`, and `ReactiveCorpusStore` are
only implemented by `BlockingToReactive*Bridge` wrappers. No backend implements the
reactive SPI natively — the bridge wraps blocking calls in
`Uni.createFrom().item(() -> delegate.method()).runSubscriptionOn(workerPool)`.

This defeats the purpose of the reactive SPI. Consumers injecting the reactive interface
get blocking I/O dispatched to a worker thread pool. Under concurrent agent workloads the
pool saturates and the "reactive" path deadlocks the same way a blocking path would.

## Scope

Add native reactive implementations for backends whose underlying technology supports
non-blocking I/O. Backends that are inherently JDBC-bound keep the bridge.

| Backend | Module | Underlying tech | In scope |
|---------|--------|-----------------|----------|
| In-memory | memory-inmem, memory-cbr-inmem | ConcurrentHashMap | Yes |
| Mem0 | memory-mem0 | REST/HTTP | Yes |
| Graphiti | memory-graphiti | REST/HTTP | Yes |
| Qdrant | memory-qdrant | gRPC | Yes |
| JPA | memory-jpa | JDBC | No — bridge stays |
| SQLite | memory-sqlite | JDBC | No — bridge stays |
| Corpus (all) | corpus/ | File I/O | No — out of scope (bridge stays) |

## Architecture Rule

**The canonical direction follows the underlying technology.** This mirrors the pattern
established in `casehub-engine`:

- **JPA persistence (prod):** reactive is canonical (Hibernate Reactive Panache native),
  blocking delegates via `.await().indefinitely()`
- **InMemory persistence (test):** blocking is canonical (ConcurrentHashMap), reactive
  wraps via `Uni.createFrom().item(result)` — no worker pool dispatch
- **No generic bridge classes** — each class handles delegation explicitly

Applied to neocortex:

| Backend | Canonical | Other direction |
|---------|-----------|-----------------|
| In-memory | Blocking | Reactive wraps: `Uni.createFrom().item(result)` |
| Mem0 | Reactive | Blocking delegates: `.await().indefinitely()` |
| Graphiti | Reactive | Blocking delegates: `.await().indefinitely()` |
| Qdrant | Reactive | Blocking delegates: `.await().indefinitely()` |
| JPA | Blocking | Bridge stays (`BlockingToReactiveBridge @DefaultBean`) |
| SQLite | Blocking | Bridge stays (`BlockingToReactiveBridge @DefaultBean`) |

## Detailed Design

### 1. In-memory backends

**CaseMemoryStore (memory-inmem):**

- `InMemoryMemoryStore` stays as-is — blocking canonical, `@Alternative @Priority(10)`
- New `ReactiveInMemoryMemoryStore implements ReactiveCaseMemoryStore`
  - `@Alternative @Priority(10)` — displaces `BlockingToReactiveBridge @DefaultBean`
  - Injects `CaseMemoryStore` by SPI interface (not concrete class — avoids ARC
    `@Alternative` resolution issues, per engine convention)
  - Every method: `Uni.createFrom().item(delegate.method())` — no `runSubscriptionOn()`

**CbrCaseMemoryStore (memory-cbr-inmem):**

- `InMemoryCbrCaseMemoryStore` stays as-is — blocking canonical, `@Alternative @Priority(2)`
- New `ReactiveInMemoryCbrCaseMemoryStore implements ReactiveCbrCaseMemoryStore`
  - `@Alternative @Priority(2)` — displaces `BlockingToReactiveCbrBridge @DefaultBean`
  - Injects `CbrCaseMemoryStore` by SPI interface
  - Same wrapping pattern, no worker pool

**Note:** Issue #162 (`InMemoryCbrCaseMemoryStore.retrieveSimilar()` returns empty in
`@QuarkusTest`) is a pre-existing CDI wiring/initialization issue in a consumer project.
The reactive wrapper delegates to the blocking store via SPI injection — if the blocking
store has a bug, fixing the blocking store fixes both paths. Resolution is independent of
this spec.

### 2. Mem0 backend (memory-mem0)

Reactive becomes canonical. The REST client returns `Uni<>` natively — no thread blocking.

- New `ReactiveMem0Client` — `@RegisterRestClient(configKey = "mem0")` with `Uni<>` return
  types, same `@Path` annotations as `Mem0Client`. Same `configKey` reuses base URL config.
- New `ReactiveMem0CaseMemoryStore implements ReactiveCaseMemoryStore`
  - `@Alternative @Priority(1)` — matches blocking counterpart
  - Uses `ReactiveMem0Client` — native reactive HTTP
  - All query/erase/store logic lives here (canonical)
- `Mem0CaseMemoryStore` simplified to thin delegate:
  - Injects `ReactiveMem0CaseMemoryStore`
  - Reactive methods (returning `Uni<>`): `reactiveStore.method().await().indefinitely()`
  - Synchronous methods (`capabilities()`, `requireCapability()`): delegate directly, no `.await()`
  - ~250 lines of logic → ~30 lines of delegation
- `ReactiveMem0CaseMemoryStore.storeAll()` overrides the default to preserve Mem0-specific
  batch semantics:
  - Index-tagged concurrency: `Multi.createFrom().iterable(indexedInputs)` where each item
    carries its original index, then `.onItem().transformToUni(tagged -> store(tagged.input()).onItem().transform(id -> new TaggedResult(tagged.index(), id)).onFailure().recoverWithItem(e -> TaggedResult.failure(tagged.index(), e))).merge(config.storeAllConcurrency())`
  - Results collected into a list, sorted by original index, then split into stored IDs
    and `StoreFailure` entries — preserving input ordering despite concurrent completion
  - `Multi.merge(concurrency)` emits in completion order; the index tag + sort restores
    input ordering. `Uni.join().all()` would preserve order but offers no concurrency cap.
- `Mem0Client` (blocking REST client) removed — dead code once the blocking store delegates
  to the reactive store
- `Mem0AuthFilter` and `@RegisterProvider` work identically with the reactive client
- **Known issue:** `Mem0Client.getById()` has a documented implementation blocker — the
  `GET /memories/{id}` endpoint may not exist in the target Mem0 OSS version. This is
  pre-existing and orthogonal to the reactive migration; the reactive client carries the
  same method and the same risk. Tracked separately (#164)

### 3. Graphiti backend (memory-graphiti)

Same pattern as Mem0: reactive REST client, reactive canonical store, blocking thin delegate.

- New `ReactiveGraphCaseMemoryStore extends ReactiveCaseMemoryStore` interface in `memory-api`:
  - Adds `default Uni<List<Memory>> graphQuery(GraphMemoryQuery query) { return Uni.createFrom().item(List.of()); }`
  - The default method returns empty — matching `NoOpCaseMemoryStore.graphQuery()` which
    also returns `List.of()`. Concrete graph adapters override it.
- `BlockingToReactiveBridge` changes from `implements ReactiveCaseMemoryStore` to
  `implements ReactiveGraphCaseMemoryStore` — inherits the default `graphQuery()` returning
  empty. This mirrors the blocking pattern: `NoOpCaseMemoryStore @DefaultBean implements
  GraphCaseMemoryStore` satisfies both `CaseMemoryStore` and `GraphCaseMemoryStore`;
  likewise the bridge now satisfies both `ReactiveCaseMemoryStore` and
  `ReactiveGraphCaseMemoryStore` via one `@DefaultBean`. No CDI ambiguity.
- New `ReactiveGraphitiClient` — `@RegisterRestClient(configKey = "graphiti")`, `Uni<>` returns,
  `@RegisterProvider(GraphitiAuthFilter.class)` (same auth filter as blocking client)
  - Note: `addMessages()` returns `Uni<Response>` (raw Jakarta response for 202 Accepted
    handling), not a typed DTO like Mem0's `Uni<Mem0AddResponse>`. The reactive store must
    check status codes explicitly.
- New `ReactiveGraphitiCaseMemoryStore implements ReactiveGraphCaseMemoryStore`
  - `@Alternative @Priority(2)` — matches blocking counterpart
  - Canonical — all logic here, including `graphQuery()` implementation
- `GraphitiCaseMemoryStore` simplified to thin delegate:
  - Reactive methods: `reactiveStore.method().await().indefinitely()`
  - Synchronous methods (`capabilities()`, `requireCapability()`): delegate directly
- `GraphitiClient` (blocking REST client) removed

### 4. Qdrant backend (memory-qdrant)

Reactive becomes canonical. The Qdrant Java client (`io.qdrant:client` v1.18.1) exposes
async gRPC methods returning Guava `ListenableFuture<T>`. `ListenableFuture` has no
`toCompletionStage()` method — conversion to `Uni` uses a Mutiny emitter with Guava's
`Futures.addCallback()`:

```java
private static <T> Uni<T> toUni(ListenableFuture<T> future) {
    return Uni.createFrom().<T>emitter(em ->
        Futures.addCallback(future, new FutureCallback<>() {
            public void onSuccess(T result) { em.complete(result); }
            public void onFailure(Throwable t) { em.fail(t); }
        }, MoreExecutors.directExecutor()));
}
```

`Futures` and `MoreExecutors` are already on the classpath (transitive via `io.qdrant:client`).
This helper is a private method in `ReactiveQdrantCbrCaseMemoryStore`.

- New `ReactiveQdrantCbrCaseMemoryStore implements ReactiveCbrCaseMemoryStore`
  - `@ApplicationScoped` — displaces `BlockingToReactiveCbrBridge @DefaultBean`
  - All logic moves here — multi-pass search, hybrid fusion, batch operations
  - Canonical implementation
- `QdrantCbrCaseMemoryStore` simplified to thin delegate:
  - Injects `ReactiveQdrantCbrCaseMemoryStore`
  - Reactive methods: `reactiveStore.method().await().indefinitely()`
  - Synchronous methods (`capabilities()`, `requireCapability()`): delegate directly

**Reactive search flow:**

The current synchronous `retrieveSimilar` runs search legs sequentially. The reactive
version fires them in parallel with per-leg error recovery preserving graceful degradation:

1. Resolve effective retrieval mode (same logic as blocking `resolveEffectiveMode()`):
   - `FEATURE_ONLY`: no embeddings — skip to step 2 with empty dense/sparse results
   - `SEMANTIC_ONLY`: dense embedding only
   - `HYBRID`: dense + sparse embeddings
   - Null guards: `embeddingModel` and `sparseEmbedder` are nullable (`Instance<>` optional
     injection). When `embeddingModel == null`, degrade `HYBRID`/`SEMANTIC_ONLY` →
     `FEATURE_ONLY`. When `sparseEmbedder == null`, skip sparse embedding.
   Then compute only the embeddings needed for the resolved mode (hoisted before parallel
   fan-out):
   - Dense: `Uni.createFrom().item(() -> embeddingModel.embed(text)).runSubscriptionOn(workerPool)`
   - Sparse (if HYBRID + sparseEmbedder != null): `Uni.createFrom().item(() -> sparseEmbedder.embed(text)).runSubscriptionOn(workerPool)`
   - These are the only legitimate worker pool dispatches — embedding models are blocking
     (LangChain4j ONNX inference or remote API calls). Hoisting avoids duplicate embedding
     per-leg and keeps the parallel search path free of worker pool dispatch.
2. Build and fire search legs concurrently with per-leg error recovery:
   ```java
   var spladeUni = executeSpladeSearchAsync(...)
       .onFailure().recoverWithItem(e -> {
           LOG.warn("SPLADE search failed — skipping", e);
           return List.of();
       });
   // same pattern for BM25
   ```
   Then `Uni.join().all(denseUni, spladeUni, bm25Uni, filterUni).andFailFast()` — each
   optional leg recovers individually, so the join never fails due to a single leg timeout.
   This preserves the current blocking behavior where SPLADE/BM25 failures are logged and
   skipped.
3. Fuse scores and filter in `.map()` (CPU, no I/O)

Parallel leg dispatch is a concrete throughput improvement — the legs no longer wait
for each other.

**Supporting classes:**

| Class | Change needed |
|-------|--------------|
| `CbrCollectionManager` | Add async methods returning `Uni<>` (see below) |
| `CbrReconciliationService` | None — `@Scheduled` background job, injects blocking SPI |
| `CbrPointBuilder` | None — pure CPU utility |
| `CbrQueryTranslator` | None — pure CPU utility |

**`CbrCollectionManager` async migration:**

`CbrCollectionManager` (269 lines) uses blocking `.get()` on `ListenableFuture` throughout.
When the reactive store becomes canonical, all `CbrCollectionManager` usage moves from
`QdrantCbrCaseMemoryStore` (now a thin delegate) to `ReactiveQdrantCbrCaseMemoryStore`.
The manager gets async method variants using the `toUni()` helper:

- `ensureCollectionAsync(caseType, vectorDimension)` → `Uni<Void>` — chains
  `collectionExistsAsync`, `getCollectionInfoAsync`, `createCollectionAsync` via `toUni()`.
  Short-circuits on `knownCollections` cache (same as blocking version).
- `registerSchemaIndexesAsync(schema, vectorDimension)` → `Uni<Void>` — chains index
  creation calls via `toUni()` sequentially (order matters for nested fields).
- `deleteByFilterAsync(collection, filter)` → `Uni<Integer>` — chains scroll + delete
  via `toUni()`.

Original blocking methods remain until `QdrantCbrCaseMemoryStore` is verified as a thin
delegate (then dead code, removable). `createBasePayloadIndexes` becomes a private async
helper chaining `toUni()` calls.

### 5. Metrics preservation

Existing blocking stores use Micrometer `@Timed` annotations (e.g.
`@Timed(value = "casehub.memory.inmem", ...)`, `@Timed(value = "casehub.memory.mem0", ...)`).
When reactive becomes canonical, `@Timed` annotations transfer to the reactive store methods.
Micrometer `@Timed` works with Mutiny `Uni` return types in Quarkus — the timer starts on
subscription and completes when the `Uni` emits.

The non-canonical direction does NOT carry `@Timed` — it would double-count:
- **Mem0/Graphiti/Qdrant:** blocking thin delegates have no `@Timed` (the reactive canonical
  store they delegate to is already timed)
- **In-memory:** reactive wrappers have no `@Timed` (the blocking canonical store they
  delegate to is already timed)

### 6. What stays unchanged

- `BlockingToReactiveBridge` — changes from `implements ReactiveCaseMemoryStore` to
  `implements ReactiveGraphCaseMemoryStore` (see §3), remains as `@DefaultBean` fallback
  for JPA and SQLite
- `BlockingToReactiveCbrBridge` — remains as `@DefaultBean` fallback for JPA and SQLite
- All reactive decorators (temporal decay, scope decay, trend enrichment, outcome
  weighting, tracking, reranking) — untouched, they decorate `ReactiveCbrCaseMemoryStore`
  regardless of which implementation sits underneath
- `BridgedCbrStore` marker interface and double-recording guards in tracking decorators —
  still needed for JPA/SQLite bridge path
- `CorpusStore` / `ReactiveCorpusStore` — excluded from this issue

### 7. CDI priority strategy

Each reactive implementation matches the priority of its blocking counterpart:

| Reactive bean | Priority | Displaces |
|---------------|----------|-----------|
| `ReactiveInMemoryMemoryStore` | `@Alternative @Priority(10)` | `BlockingToReactiveBridge @DefaultBean` |
| `ReactiveInMemoryCbrCaseMemoryStore` | `@Alternative @Priority(2)` | `BlockingToReactiveCbrBridge @DefaultBean` |
| `ReactiveMem0CaseMemoryStore` | `@Alternative @Priority(1)` | `BlockingToReactiveBridge @DefaultBean` |
| `ReactiveGraphitiCaseMemoryStore` | `@Alternative @Priority(2)` | `BlockingToReactiveBridge @DefaultBean` (for both `ReactiveCaseMemoryStore` and `ReactiveGraphCaseMemoryStore`) |
| `ReactiveQdrantCbrCaseMemoryStore` | `@ApplicationScoped` | `BlockingToReactiveCbrBridge @DefaultBean` |

Classpath-based activation: test modules include `memory-cbr-inmem`, production includes
`memory-qdrant`. The `@Alternative` with any `@Priority` beats a `@DefaultBean`.

## Testing Strategy

**Contract tests:**

- In-memory reactive: subclass `CbrCaseMemoryStoreContractTest` (142 tests), provide the
  reactive wrapper. Verifies behavioral parity through the reactive path.
- Mem0/Graphiti reactive: existing tests retargeted to the reactive canonical
  implementation. Blocking delegate tests verify delegation only.
- Qdrant reactive: existing `QdrantCbrCaseMemoryStoreTest` (Testcontainers) migrated to
  test the reactive store directly.

**Threading verification:**

Each new reactive implementation gets a thread-assertion test:
- In-memory: `Uni` resolves on the caller's thread (no worker pool dispatch)
- Mem0/Graphiti: no `runSubscriptionOn` in the chain (reactive REST client native)
- Qdrant: async gRPC stubs used, not blocking stubs

**CDI wiring tests:**

Per-module `@QuarkusTest` verifying:
- `ReactiveFooStore` injection → native implementation, not the bridge
- `FooStore` (blocking) injection → delegate wrapping the reactive one (Mem0/Graphiti/Qdrant)
  or the original blocking (in-memory)
- Bridge stays dormant when a native reactive bean is present

## Implementation Order

1. **In-memory** — simplest, proves the pattern, unblocks testing of later stages
2. **Mem0** — reactive REST client pattern, moderate complexity
3. **Graphiti** — same pattern as Mem0
4. **Qdrant** — heaviest, benefits from patterns established in 1–3

## Cross-Repo Impact

Engine consumes neocortex memory SPIs via Maven SNAPSHOT. Once reactive backends are
native, engine callers injecting `ReactiveCbrCaseMemoryStore` get true non-blocking I/O
without code changes — the bridge disappears from the chain transparently.

No API changes to `ReactiveCaseMemoryStore` or `ReactiveCbrCaseMemoryStore` interfaces.
One new interface: `ReactiveGraphCaseMemoryStore extends ReactiveCaseMemoryStore` in
`memory-api` (see §3). Existing consumers injecting `ReactiveCaseMemoryStore` are unaffected.
Consumers needing reactive `graphQuery()` inject the new subtype.
