package io.casehub.neocortex.cognitive.observability.testing;

import io.casehub.neocortex.cognitive.observability.PipelineSnapshotStore;
import io.casehub.neocortex.cognition.trace.PipelineTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public abstract class PipelineSnapshotStoreContractTest {

    protected abstract PipelineSnapshotStore createStore();

    private PipelineSnapshotStore store;

    private static final PipelineTrace EMPTY_TRACE = new PipelineTrace(
            null, Map.of(), Map.of(), null, null, null, null, List.of(), Map.of(), Map.of());

    @BeforeEach
    void setUp() {
        store = createStore();
    }

    @Test
    void shouldStoreAndFindByTick() {
        store.store("t1", "agent1", 5, Instant.now(), EMPTY_TRACE);
        var result = store.findByTick("t1", "agent1", 5);
        assertTrue(result.isPresent());
        assertEquals("agent1", result.get().agentId());
        assertEquals(5, result.get().tick());
    }

    @Test
    void shouldFindByTickRange() {
        store.store("t1", "agent1", 1, Instant.now(), EMPTY_TRACE);
        store.store("t1", "agent1", 2, Instant.now(), EMPTY_TRACE);
        store.store("t1", "agent1", 3, Instant.now(), EMPTY_TRACE);
        store.store("t1", "agent1", 5, Instant.now(), EMPTY_TRACE);

        var results = store.find("t1", "agent1", 2, 4);
        assertEquals(2, results.size());
    }

    @Test
    void shouldFilterByAgentAndTenant() {
        store.store("t1", "agent1", 1, Instant.now(), EMPTY_TRACE);
        store.store("t1", "agent2", 1, Instant.now(), EMPTY_TRACE);
        store.store("t2", "agent1", 1, Instant.now(), EMPTY_TRACE);

        var results = store.find("t1", "agent1", 0, 10);
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindLatest() {
        store.store("t1", "agent1", 1, Instant.now(), EMPTY_TRACE);
        store.store("t1", "agent1", 5, Instant.now(), EMPTY_TRACE);
        store.store("t1", "agent1", 3, Instant.now(), EMPTY_TRACE);

        var results = store.findLatest("t1", "agent1", 2);
        assertEquals(2, results.size());
        assertEquals(5, results.get(0).tick());
        assertEquals(3, results.get(1).tick());
    }

    @Test
    void shouldCountByTenant() {
        store.store("t1", "agent1", 1, Instant.now(), EMPTY_TRACE);
        store.store("t1", "agent2", 1, Instant.now(), EMPTY_TRACE);
        store.store("t2", "agent1", 1, Instant.now(), EMPTY_TRACE);

        assertEquals(2, store.count("t1"));
        assertEquals(1, store.count("t2"));
    }

    @Test
    void shouldPurgeOlderThan() {
        store.store("t1", "agent1", 1, Instant.now(), EMPTY_TRACE);
        store.store("t1", "agent1", 2, Instant.now(), EMPTY_TRACE);

        store.purge(Instant.now().plusSeconds(1));
        assertEquals(0, store.count("t1"));
    }

    @Test
    void shouldReturnEmptyForMissingTick() {
        assertTrue(store.findByTick("t1", "agent1", 99).isEmpty());
    }

    @Test
    void shouldHandleDuplicateTickByUpsert() {
        var trace1 = new PipelineTrace(null, Map.of(), Map.of(), null, null, null, null, List.of(), Map.of(), Map.of("key", "v1"));
        var trace2 = new PipelineTrace(null, Map.of(), Map.of(), null, null, null, null, List.of(), Map.of(), Map.of("key", "v2"));

        store.store("t1", "agent1", 1, Instant.now(), trace1);
        store.store("t1", "agent1", 1, Instant.now(), trace2);

        var results = store.find("t1", "agent1", 0, 10);
        assertEquals(1, results.size());
        assertEquals("v2", results.get(0).trace().promptSections().get("key"));
    }

    @Test
    void shouldPreservePipelineTraceFields() {
        var trace = new PipelineTrace(
                null, Map.of("curiosity", 0.8), Map.of("input1", 0.5),
                null, null, null, null, List.of("CURIOSITY", "AFFILIATION"),
                Map.of("CURIOSITY", 0.7, "AFFILIATION", 0.5),
                Map.of("mood", "calm and alert"));

        store.store("t1", "agent1", 10, Instant.now(), trace);
        var result = store.findByTick("t1", "agent1", 10);

        assertTrue(result.isPresent());
        assertEquals(0.8, result.get().trace().concernSalience().get("curiosity"));
        assertEquals(List.of("CURIOSITY", "AFFILIATION"), result.get().trace().activatedDriveCategories());
        assertEquals(0.7, result.get().trace().driveIntensities().get("CURIOSITY"));
        assertEquals("calm and alert", result.get().trace().promptSections().get("mood"));
    }
}
