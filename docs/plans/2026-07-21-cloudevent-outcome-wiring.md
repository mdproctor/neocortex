# CloudEvent Outcome Wiring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #142 — wire CbrOutcomeConsumer to platform CloudEvent routing
**Issue group:** #142

**Goal:** Connect `CbrOutcomeConsumer` to the platform's CloudEvent dispatch
infrastructure so `io.casehub.cbr.outcome` events automatically update CBR
case confidence via `recordOutcome()`.

**Architecture:** Add `@ObservesAsync @CloudEventType(CbrEventTypes.CBR_OUTCOME)`
observer method to the existing `CbrOutcomeConsumer` bean. The platform's
`CloudEventTypeDispatcher` re-fires incoming CloudEvents with a type qualifier;
the consumer deserializes the CloudEvent data payload to `CbrOutcomeData` and
delegates to the existing domain logic.

**Tech Stack:** Jakarta CDI (`@ObservesAsync`), CloudEvents SDK (`CloudEvent`,
`CloudEventBuilder`), `@CloudEventType` (platform-api), Jackson (`ObjectMapper`),
Quarkus (`@QuarkusTest`)

## Global Constraints

- IntelliJ MCP mandatory for all `.java` edits
- `CbrEventTypes.CBR_OUTCOME` constant from `casehub-desiredstate-api` — never hardcode `"io.casehub.cbr.outcome"`
- `@ObservesAsync` not `@Observes` — `CloudEventTypeDispatcher` uses `fireAsync()`
- CDI-managed `ObjectMapper` — Quarkus registers `JavaTimeModule` for `Instant` deserialization
- `@RequestScoped` beans must not be injected in the observer chain
- platform-api SNAPSHOT with `@CloudEventType` (commit `c9fcc74`) already installed

---

### Task 1: Add jackson-databind dependency and wire CloudEvent observer

**Files:**
- Modify: `memory/pom.xml`
- Modify: `memory/src/main/java/io/casehub/neocortex/memory/cbr/runtime/CbrOutcomeConsumer.java`
- Test: `memory/src/test/java/io/casehub/neocortex/memory/cbr/runtime/CbrOutcomeConsumerTest.java`

**Interfaces:**
- Consumes: `CbrCaseMemoryStore.recordOutcome(String caseId, String tenantId, CbrOutcome outcome)`, `CbrEventTypes.CBR_OUTCOME`, `@CloudEventType` annotation, `CloudEvent` (CloudEvents SDK), `ObjectMapper` (Jackson)
- Produces: `CbrOutcomeConsumer.onCloudEvent(CloudEvent)` — CDI observer method dispatched by `CloudEventTypeDispatcher`

- [ ] **Step 1: Add jackson-databind to memory/pom.xml**

Add to `<dependencies>` section:

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <scope>provided</scope>
</dependency>
```

Version is managed by `quarkus-bom`. `provided` scope — available at runtime in all Quarkus applications.

- [ ] **Step 2: Write the failing unit tests**

Add four new test methods to `CbrOutcomeConsumerTest.java`. The tests construct CloudEvents directly and call `onCloudEvent()` — no CDI container needed.

Tests need an `ObjectMapper` with `JavaTimeModule` (same as Quarkus-managed). Add a shared field and update `setUp()`:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import java.net.URI;
```

Add a field and helper:

```java
private static final ObjectMapper MAPPER = new ObjectMapper()
    .registerModule(new JavaTimeModule());

private static CloudEvent outcomeEvent(CbrOutcomeData data) throws Exception {
    return CloudEventBuilder.v1()
        .withId("test-1")
        .withSource(URI.create("/casehub-desiredstate"))
        .withType(CbrEventTypes.CBR_OUTCOME)
        .withDataContentType("application/json")
        .withData(MAPPER.writeValueAsBytes(data))
        .build();
}
```

Import `CbrEventTypes`:

```java
import io.casehub.desiredstate.api.CbrEventTypes;
```

Test 1 — happy path:

```java
@Test
void onCloudEvent_deserializesAndDelegates() throws Exception {
    var recorded = new ArrayList<RecordedOutcome>();
    var consumer = new CbrOutcomeConsumer(new CapturingStore(recorded), MAPPER);

    var data = new CbrOutcomeData(
        "tenant-1", "case-42", CbrPath.FAULT,
        Map.of("node-a", "SUCCEEDED", "node-b", "FAILED"),
        1, 1, 2, 0.5,
        Instant.parse("2026-07-13T09:00:00Z"),
        Instant.parse("2026-07-13T10:00:00Z"));

    consumer.onCloudEvent(outcomeEvent(data));

    assertThat(recorded).hasSize(1);
    var r = recorded.getFirst();
    assertThat(r.caseId).isEqualTo("case-42");
    assertThat(r.tenantId).isEqualTo("tenant-1");
    assertThat(r.outcome.successRate()).isEqualTo(0.5);
}
```

Test 2 — null data:

```java
@Test
void onCloudEvent_nullData_skips() {
    var recorded = new ArrayList<RecordedOutcome>();
    var consumer = new CbrOutcomeConsumer(new CapturingStore(recorded), MAPPER);

    var event = CloudEventBuilder.v1()
        .withId("test-null")
        .withSource(URI.create("/test"))
        .withType(CbrEventTypes.CBR_OUTCOME)
        .build();

    consumer.onCloudEvent(event);

    assertThat(recorded).isEmpty();
}
```

Test 3 — invalid JSON:

```java
@Test
void onCloudEvent_invalidJson_skips() {
    var recorded = new ArrayList<RecordedOutcome>();
    var consumer = new CbrOutcomeConsumer(new CapturingStore(recorded), MAPPER);

    var event = CloudEventBuilder.v1()
        .withId("test-bad")
        .withSource(URI.create("/test"))
        .withType(CbrEventTypes.CBR_OUTCOME)
        .withDataContentType("application/json")
        .withData("not valid json".getBytes())
        .build();

    consumer.onCloudEvent(event);

    assertThat(recorded).isEmpty();
}
```

Test 4 — store exception does not kill observer:

```java
@Test
void onCloudEvent_storeThrows_propagates() throws Exception {
    var consumer = new CbrOutcomeConsumer(new ThrowingStore(), MAPPER);

    var data = new CbrOutcomeData(
        "t1", "case-99", CbrPath.SITUATION,
        Map.of(), 1, 0, 1, 1.0,
        Instant.parse("2026-07-13T09:00:00Z"),
        Instant.parse("2026-07-13T10:00:00Z"));

    assertThatThrownBy(() -> consumer.onCloudEvent(outcomeEvent(data)))
        .isInstanceOf(RuntimeException.class);
}
```

Add `ThrowingStore` inner class:

```java
static class ThrowingStore extends NoOpCbrCaseMemoryStore {
    @Override
    public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
        throw new RuntimeException("store failure");
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory -Dtest=CbrOutcomeConsumerTest -DfailIfNoTests=false`
Expected: FAIL — `CbrOutcomeConsumer` constructor does not accept `ObjectMapper`, no `onCloudEvent` method

- [ ] **Step 4: Implement the CloudEvent observer**

Modify `CbrOutcomeConsumer.java`:

1. Add `ObjectMapper` as a constructor parameter
2. Add `onCloudEvent` method with `@ObservesAsync @CloudEventType(CbrEventTypes.CBR_OUTCOME)`
3. Add error handling for null data and deserialization failure

Updated class:

```java
package io.casehub.neocortex.memory.cbr.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.CbrEventTypes;
import io.casehub.desiredstate.api.CbrOutcomeData;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.platform.api.event.CloudEventType;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class CbrOutcomeConsumer {

    private static final Logger LOG = Logger.getLogger(CbrOutcomeConsumer.class);

    private final CbrCaseMemoryStore store;
    private final ObjectMapper objectMapper;

    @Inject
    public CbrOutcomeConsumer(CbrCaseMemoryStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public void onCloudEvent(
            @ObservesAsync @CloudEventType(CbrEventTypes.CBR_OUTCOME) CloudEvent event) {
        if (event.getData() == null) {
            LOG.warnf("CloudEvent %s has no data payload — skipping", event.getId());
            return;
        }
        CbrOutcomeData data;
        try {
            data = objectMapper.readValue(event.getData().toBytes(), CbrOutcomeData.class);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to deserialize CloudEvent %s — skipping", event.getId());
            return;
        }
        onCbrOutcome(data);
    }

    public void onCbrOutcome(CbrOutcomeData data) {
        CbrOutcome outcome = CbrOutcome.of(
            data.successRate(),
            summarize(data.nodeOutcomes()),
            data.observedAt());
        store.recordOutcome(data.sourceId(), data.tenancyId(), outcome);
    }

    private static String summarize(Map<String, String> nodeOutcomes) {
        if (nodeOutcomes == null || nodeOutcomes.isEmpty()) return null;
        return nodeOutcomes.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(", "));
    }
}
```

Use `ide_edit_member` to replace the class declaration (the entire class body changes — new constructor parameter, new method, new imports).

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory -Dtest=CbrOutcomeConsumerTest`
Expected: ALL PASS (7 tests — 3 existing + 4 new)

- [ ] **Step 6: Run diagnostics**

Run `ide_diagnostics` on `CbrOutcomeConsumer.java` to check for errors.

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/neocortex add memory/pom.xml memory/src/main/java/io/casehub/neocortex/memory/cbr/runtime/CbrOutcomeConsumer.java memory/src/test/java/io/casehub/neocortex/memory/cbr/runtime/CbrOutcomeConsumerTest.java
git -C /Users/mdproctor/claude/casehub/neocortex commit -m "feat(#142): wire CbrOutcomeConsumer to @CloudEventType dispatch — observer + unit tests"
```

---

### Task 2: CDI wiring integration test

**Files:**
- Create: `memory/src/test/java/io/casehub/neocortex/memory/cbr/runtime/CbrOutcomeConsumerCdiTest.java`

**Interfaces:**
- Consumes: `CbrOutcomeConsumer.onCloudEvent(CloudEvent)` from Task 1, `CloudEventTypeLiteral` (platform), `Event<CloudEvent>` (CDI)
- Produces: `@QuarkusTest` verifying CDI dispatch reaches the observer

- [ ] **Step 1: Write the CDI wiring test**

This test verifies that CDI annotation-based dispatch reaches the observer. The failure mode is silent — wrong qualifier or `@Observes` instead of `@ObservesAsync` means no delivery, no error.

Create `CbrOutcomeConsumerCdiTest.java`:

```java
package io.casehub.neocortex.memory.cbr.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.CbrEventTypes;
import io.casehub.desiredstate.api.CbrOutcomeData;
import io.casehub.desiredstate.api.CbrPath;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.platform.event.CloudEventTypeLiteral;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
class CbrOutcomeConsumerCdiTest {

    @Inject
    Event<CloudEvent> cloudEventBus;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    CapturingCbrStore capturingStore;

    @Test
    void observer_receives_typed_event() throws Exception {
        var data = new CbrOutcomeData(
            "tenant-cdi", "case-cdi-1", CbrPath.FAULT,
            Map.of("n1", "SUCCEEDED"),
            1, 0, 1, 1.0,
            Instant.parse("2026-07-21T09:00:00Z"),
            Instant.parse("2026-07-21T10:00:00Z"));

        CloudEvent event = CloudEventBuilder.v1()
            .withId("cdi-test-1")
            .withSource(URI.create("/test"))
            .withType(CbrEventTypes.CBR_OUTCOME)
            .withDataContentType("application/json")
            .withData(objectMapper.writeValueAsBytes(data))
            .build();

        cloudEventBus.select(new CloudEventTypeLiteral(CbrEventTypes.CBR_OUTCOME))
            .fireAsync(event)
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(capturingStore.recorded).hasSize(1));

        var r = capturingStore.recorded.getFirst();
        assertThat(r.caseId()).isEqualTo("case-cdi-1");
        assertThat(r.tenantId()).isEqualTo("tenant-cdi");
        assertThat(r.outcome().result()).isEqualTo(CbrOutcome.Outcome.SUCCESS);
    }

    public record RecordedOutcome(String caseId, String tenantId, CbrOutcome outcome) {}

    @Alternative
    @Singleton
    @jakarta.annotation.Priority(100)
    public static class CapturingCbrStore extends NoOpCbrCaseMemoryStore {
        final List<RecordedOutcome> recorded = new ArrayList<>();

        @Override
        public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
            recorded.add(new RecordedOutcome(caseId, tenantId, outcome));
        }
    }
}
```

- [ ] **Step 2: Check test dependencies**

The CDI test needs `awaitility` (for async assertion) and `quarkus-junit5`. Check if `awaitility` is available in the memory module:

```bash
grep -n "awaitility" /Users/mdproctor/claude/casehub/neocortex/memory/pom.xml /Users/mdproctor/claude/casehub/neocortex/pom.xml
```

If not present, add to `memory/pom.xml`:

```xml
<dependency>
  <groupId>org.awaitility</groupId>
  <artifactId>awaitility</artifactId>
  <scope>test</scope>
</dependency>
```

Also check if `casehub-platform` (runtime, containing `CloudEventTypeLiteral`) is available. If not, add:

```xml
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-platform</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 3: Run the CDI test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory -Dtest=CbrOutcomeConsumerCdiTest`
Expected: PASS — CDI dispatches the typed CloudEvent to the observer

- [ ] **Step 4: Run full module tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl memory`
Expected: ALL PASS — no regressions

- [ ] **Step 5: Run diagnostics**

Run `ide_diagnostics` on the new test file.

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/neocortex add memory/pom.xml memory/src/test/java/io/casehub/neocortex/memory/cbr/runtime/CbrOutcomeConsumerCdiTest.java
git -C /Users/mdproctor/claude/casehub/neocortex commit -m "test(#142): CDI wiring integration test — verifies @CloudEventType dispatch reaches observer"
```

---

### Task 3: CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: completed Tasks 1-2
- Produces: updated module documentation

- [ ] **Step 1: Update CLAUDE.md memory/ module description**

In CLAUDE.md, find the `memory/` module line and add the CloudEvent observation note. The line currently starts with `memory/             — MemoryEmitter`. Add to the description after `CbrOutcomeConsumer (bridges io.casehub.cbr.outcome CloudEvents to CbrCaseMemoryStore.recordOutcome — depends on casehub-desiredstate-api)`:

Update to: `CbrOutcomeConsumer (@ObservesAsync @CloudEventType(CbrEventTypes.CBR_OUTCOME) — deserializes CloudEvent data to CbrOutcomeData, bridges to CbrCaseMemoryStore.recordOutcome; depends on casehub-desiredstate-api + jackson-databind provided)`

- [ ] **Step 2: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/neocortex add CLAUDE.md
git -C /Users/mdproctor/claude/casehub/neocortex commit -m "docs(#142): update CLAUDE.md — CbrOutcomeConsumer CloudEvent wiring"
```

---

## Task Dependencies

```
Task 1 (observer + unit tests) → Task 2 (CDI wiring test) → Task 3 (CLAUDE.md)
```

All tasks are sequential — Task 2 depends on the observer from Task 1, and Task 3 documents the completed work.
