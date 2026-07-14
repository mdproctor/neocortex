# Event-to-Memory Bridge Design

**Issue:** casehubio/neocortex#64
**Date:** 2026-07-14
**Status:** Approved

## Problem

Multiple repos independently implement the same "inject CaseMemoryStore, guard resolvability, isolate errors, call store()" pattern:

- **engine** `CaseMemoryObserver` — CDI async observer (`@ObservesAsync`) that stores structured memories on CaseCompleted/Cancelled/Failed events
- **devtown** `CaseMemoryEmitter` — CDI async observer (`@ObservesAsync`) that stores PR review facts on ReviewCompletedEvent
- **devtown** `FeatureVectorEmitter` — programmatic caller (no observer annotation) that stores feature vectors on demand

All three share identical boilerplate: `Instance<CaseMemoryStore>` injection, `isResolvable()` guard, try/catch error isolation, and (in engine's case) `Instance.destroy()` lifecycle cleanup. Devtown's emitters omit `destroy()` — a correctness gap that leaks `@Dependent`-scoped beans.

The domain-specific fact extraction (event → MemoryInput) is 95% of each consumer and cannot be abstracted. The shared cross-cutting concern is the store lifecycle and error isolation wrapper — regardless of whether the caller is an event observer or a programmatic service.

## Solution

A `MemoryEmitter` CDI service in the `memory/` module that wraps `CaseMemoryStore` with fire-and-forget semantics: error isolation, structured logging, and correct CDI lifecycle.

### Why not an abstract class

The issue originally proposed an abstract `CaseMemoryEventBridge`. CDI event observation is declared via `@Observes`/`@ObservesAsync` annotations per event type — it cannot be abstracted away. Each consumer must still declare its own observer method. An abstract class would impose single-inheritance for ~6 lines of shared boilerplate.

A CDI service achieves the same consolidation without inheritance: consumers `@Inject MemoryEmitter` and call `emit()` from their observer methods.

### Why direct injection (not Instance)

`MemoryEmitter` lives in `memory/`, which provides `NoOpCaseMemoryStore @DefaultBean`. The store is always resolvable — the `Instance<>` + `isResolvable()` guard pattern is unnecessary. This is a deliberate dependency change: consumers that adopt `MemoryEmitter` depend on `memory/` (not just `memory-api/`), gaining `NoOpCaseMemoryStore` on the classpath.

## API

```java
package io.casehub.memory.runtime;

@ApplicationScoped
public class MemoryEmitter {

    private final CaseMemoryStore store;

    @Inject
    MemoryEmitter(CaseMemoryStore store) {
        this.store = store;
    }

    public void emit(MemoryInput input) {
        try {
            store.store(input);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf(e, "Memory emission failed for entity=%s domain=%s tenant=%s",
                input.entityId(), input.domain().name(), input.tenantId());
        }
    }

    public void emitAll(List<MemoryInput> inputs) {
        if (inputs.isEmpty()) return;
        try {
            var result = store.storeAll(inputs);
            if (!result.allSucceeded()) {
                LOG.warnf("Memory batch partial failure: %d/%d inputs failed (first entity=%s domain=%s)",
                    result.failures().size(), inputs.size(),
                    inputs.getFirst().entityId(), inputs.getFirst().domain().name());
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf(e, "Memory batch emission failed (%d inputs, first entity=%s domain=%s)",
                inputs.size(), inputs.getFirst().entityId(), inputs.getFirst().domain().name());
        }
    }
}
```

## Design Decisions

### DD1: Catch Exception, not Throwable

`Error` subtypes (OOM, StackOverflow) propagate. Only `Exception` is swallowed. Matches the existing convention in all three consumers.

### DD2: SecurityException propagates

`SecurityException` from `MemoryPermissions.assertTenant()` is re-thrown, not swallowed. This is safe for `@ObservesAsync` callers because adapters use the 3-arg `assertTenant(tenantId, principal, requestContextActive())` form — when no CDI request scope is active (the async observer case), `assertTenant` trusts the `tenantId` directly and never throws. SecurityException only fires when a request scope IS active and the tenantId mismatches the authenticated principal — a genuine tenant violation that must not be silently absorbed.

`MemoryEmitter` does not call `assertTenant()` itself. Adapters enforce tenant assertion as part of the SPI contract (`CaseMemoryStore` javadoc: "Adapters MUST call `MemoryPermissions.assertTenant` before delegating to the backend"). The emitter's role is error isolation for backend failures, not security enforcement — duplicating the tenant check would be redundant with the adapter contract.

### DD3: Void return

All three current consumers discard `store()` return value (memoryId). `MemoryEmitter` is specifically for fire-and-forget; callers needing the memoryId inject `CaseMemoryStore` directly.

### DD4: No reactive variant

All CDI observers run on managed executor threads (blocking context). `@ObservesAsync` does not use the Vert.x event loop. No current consumer needs reactive fire-and-forget emission.

### DD5: No CDI event on successful write

Firing `MemoryEmitted` events after store would add extensibility for auditing/metrics. Deferred — decorators on `CaseMemoryStore` already serve this role, and no consumer needs it today.

## Module Placement

`memory/` module, package `io.casehub.memory.runtime` — alongside `CaseEnrichmentDecorator` (same package). Note: `NoOpCaseMemoryStore` and `BlockingToReactiveBridge` share the same filesystem directory but declare `io.casehub.neocortex.memory.runtime` — a pre-existing package/path inconsistency in the codebase. `MemoryEmitter` uses `io.casehub.memory.runtime` to match the filesystem path and `CaseEnrichmentDecorator`.

## SPI Javadoc Update

The `CaseMemoryStore.store()` javadoc's "Emission pattern" section documents direct injection as the canonical pattern. Implementation must add a fire-and-forget paragraph:

> **Fire-and-forget:** for CDI observers ({@code @ObservesAsync}) and other contexts where backend failures must not propagate, inject {@code MemoryEmitter} instead — it wraps this store with error isolation and structured logging. {@code SecurityException} from tenant assertion still propagates through {@code MemoryEmitter}.

## Testing

| Test | Verifies |
|------|----------|
| `emit()` delegates to `store.store()` | Input reaches the store unchanged |
| `emitAll()` delegates to `store.storeAll()` | Batch delegation |
| `emitAll()` with empty list | No store call, no exception |
| `emit()` — store throws RuntimeException | Not propagated, warning logged |
| `emitAll()` — store throws RuntimeException | Not propagated, warning logged |
| `emit()` — store throws SecurityException | Propagated to caller (not swallowed) |
| `emitAll()` — storeAll throws SecurityException | Propagated to caller (not swallowed) |
| `emitAll()` — storeAll returns partial failures | `StoreAllResult.failures()` logged, no exception |

## Consumer Migration (peer repos)

This session delivers `MemoryEmitter` in neocortex. Consumer migration is tracked as separate issues on peer repos:

- **casehubio/engine#731**: simplify `CaseMemoryObserver` to use `MemoryEmitter` — removes Instance lifecycle, try/catch, destroy()
- **casehubio/devtown#150**: simplify `CaseMemoryEmitter` and `FeatureVectorEmitter` — also fixes the missing `destroy()` bug

## Scope

**In scope:** `MemoryEmitter` class + tests in `memory/` module.

**Out of scope:** Consumer migration (peer repos), reactive variant, CDI event notification, CbrCaseMemoryStore equivalent.
