# Event-to-Memory Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #64 — event-to-memory bridge — shared CDI observer pattern for CaseMemoryStore writes
**Issue group:** #64

**Goal:** Add a `MemoryEmitter` CDI service in the `memory/` module that wraps `CaseMemoryStore` with fire-and-forget semantics — error isolation, structured logging, SecurityException propagation.

**Architecture:** Single `@ApplicationScoped` class injecting `CaseMemoryStore` directly (not via `Instance<>` — `NoOpCaseMemoryStore @DefaultBean` is always available). Two methods: `emit(MemoryInput)` and `emitAll(List<MemoryInput>)`. Both catch `Exception` (not `Throwable`), re-throw `SecurityException`, and log failures at WARN level.

**Tech Stack:** Java 21, Quarkus 3.32.2, JUnit 5, AssertJ, Mockito

## Global Constraints

- Java 21 language features (records, sealed, pattern matching)
- Package: `io.casehub.memory.runtime` (matches `CaseEnrichmentDecorator`)
- Logger: JBoss `org.jboss.logging.Logger` (project standard)
- Tests: plain JUnit 5 + AssertJ + Mockito (no @QuarkusTest — no CDI needed)
- Build: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -pl memory`

---

### Task 1: MemoryEmitter — implementation and tests

**Files:**
- Create: `memory/src/main/java/io/casehub/memory/runtime/MemoryEmitter.java`
- Create: `memory/src/test/java/io/casehub/memory/runtime/MemoryEmitterTest.java`
- Modify: `memory-api/src/main/java/io/casehub/neocortex/memory/CaseMemoryStore.java` (javadoc only)

**Interfaces:**
- Consumes: `CaseMemoryStore.store(MemoryInput)`, `CaseMemoryStore.storeAll(List<MemoryInput>)`, `StoreAllResult.allSucceeded()`, `StoreAllResult.failures()`
- Produces: `MemoryEmitter.emit(MemoryInput)`, `MemoryEmitter.emitAll(List<MemoryInput>)` — consumed by engine#731 and devtown#150

- [ ] **Step 1: Write failing test — emit delegates to store**

```java
package io.casehub.memory.runtime;

import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.StoreAllResult;
import io.casehub.neocortex.memory.StoreFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryEmitterTest {

    private static final MemoryDomain DOMAIN = new MemoryDomain("test");
    private static final MemoryInput SAMPLE = new MemoryInput(
        "entity-1", DOMAIN, "tenant-1", null, "sample text", Map.of());

    private CaseMemoryStore store;
    private MemoryEmitter emitter;

    @BeforeEach
    void setUp() {
        store = mock(CaseMemoryStore.class);
        emitter = new MemoryEmitter(store);
    }

    @Test
    void emit_delegates_to_store() {
        when(store.store(SAMPLE)).thenReturn("mem-1");

        emitter.emit(SAMPLE);

        verify(store).store(SAMPLE);
    }
}
```

Use `ide_create_file` for the test file.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory -Dtest=MemoryEmitterTest#emit_delegates_to_store -Dsurefire.useFile=false`
Expected: compilation failure — `MemoryEmitter` does not exist

- [ ] **Step 3: Write minimal MemoryEmitter — emit() only**

```java
package io.casehub.memory.runtime;

import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class MemoryEmitter {

    private static final Logger LOG = Logger.getLogger(MemoryEmitter.class);

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

Use `ide_create_file` for the production file.

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory -Dtest=MemoryEmitterTest#emit_delegates_to_store -Dsurefire.useFile=false`
Expected: PASS

- [ ] **Step 5: Add remaining tests — all at once since the implementation is complete**

Add these tests to `MemoryEmitterTest`:

```java
@Test
void emitAll_delegates_to_storeAll() {
    var input2 = new MemoryInput("entity-2", DOMAIN, "tenant-1", null, "text 2", Map.of());
    when(store.storeAll(List.of(SAMPLE, input2)))
        .thenReturn(new StoreAllResult(List.of("m1", "m2"), List.of()));

    emitter.emitAll(List.of(SAMPLE, input2));

    verify(store).storeAll(List.of(SAMPLE, input2));
}

@Test
void emitAll_empty_list_does_not_call_store() {
    emitter.emitAll(List.of());

    verifyNoInteractions(store);
}

@Test
void emit_swallows_runtime_exception() {
    when(store.store(SAMPLE)).thenThrow(new RuntimeException("backend down"));

    assertThatCode(() -> emitter.emit(SAMPLE)).doesNotThrowAnyException();
}

@Test
void emitAll_swallows_runtime_exception() {
    when(store.storeAll(List.of(SAMPLE))).thenThrow(new RuntimeException("backend down"));

    assertThatCode(() -> emitter.emitAll(List.of(SAMPLE))).doesNotThrowAnyException();
}

@Test
void emit_propagates_security_exception() {
    when(store.store(SAMPLE)).thenThrow(new SecurityException("tenant mismatch"));

    assertThatThrownBy(() -> emitter.emit(SAMPLE))
        .isInstanceOf(SecurityException.class)
        .hasMessage("tenant mismatch");
}

@Test
void emitAll_propagates_security_exception() {
    when(store.storeAll(List.of(SAMPLE))).thenThrow(new SecurityException("tenant mismatch"));

    assertThatThrownBy(() -> emitter.emitAll(List.of(SAMPLE)))
        .isInstanceOf(SecurityException.class)
        .hasMessage("tenant mismatch");
}

@Test
void emitAll_logs_partial_failures_without_throwing() {
    var failure = new StoreFailure(1, SAMPLE, new RuntimeException("item failed"));
    when(store.storeAll(List.of(SAMPLE)))
        .thenReturn(new StoreAllResult(List.of(), List.of(failure)));

    assertThatCode(() -> emitter.emitAll(List.of(SAMPLE))).doesNotThrowAnyException();
}
```

Use `ide_insert_member` to add each test method, or `ide_edit_member` to replace the test class body.

- [ ] **Step 6: Run all tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory -Dtest=MemoryEmitterTest -Dsurefire.useFile=false`
Expected: 8 tests PASS

- [ ] **Step 7: Update CaseMemoryStore.store() javadoc**

Add the fire-and-forget paragraph to the existing `store()` javadoc on `CaseMemoryStore`, after the "Batch jobs and startup contexts" paragraph:

```java
 * <p><b>Fire-and-forget:</b> for CDI observers ({@code @ObservesAsync}) and other
 * contexts where backend failures must not propagate, inject {@link
 * io.casehub.memory.runtime.MemoryEmitter MemoryEmitter} instead — it wraps this
 * store with error isolation and structured logging. {@link SecurityException} from
 * tenant assertion still propagates through {@code MemoryEmitter}.
```

Use `ide_edit_member` on `CaseMemoryStore.store` to replace the full method declaration including its javadoc.

- [ ] **Step 8: Build the full module to verify no regressions**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -pl memory`
Expected: BUILD SUCCESS, all existing tests pass

- [ ] **Step 9: Run diagnostics**

Run `ide_diagnostics` on both new files to confirm no warnings.

- [ ] **Step 10: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/neocortex add \
  memory/src/main/java/io/casehub/memory/runtime/MemoryEmitter.java \
  memory/src/test/java/io/casehub/memory/runtime/MemoryEmitterTest.java \
  memory-api/src/main/java/io/casehub/neocortex/memory/CaseMemoryStore.java
git -C /Users/mdproctor/claude/casehub/neocortex commit -m "feat(#64): MemoryEmitter — fire-and-forget CaseMemoryStore wrapper

@ApplicationScoped CDI service in memory/ module. Wraps CaseMemoryStore
with error isolation (catch Exception, re-throw SecurityException) and
structured WARN logging. emit() for single writes, emitAll() for batch
with partial-failure logging via StoreAllResult.

Replaces per-consumer Instance lifecycle boilerplate in engine and devtown.
Consumer migration tracked as engine#731 and devtown#150."
```
