package io.casehub.neocortex.cognition.narrative;

import io.casehub.neocortex.cognition.drive.DriveAxis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NarrativeOrchestratorTest {

    private static final Instant T1 = Instant.parse("2026-08-24T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-24T11:00:00Z");

    private NarrativeMemory store;
    private NarrativeOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        store = mock(NarrativeMemory.class);
        orchestrator = new NarrativeOrchestrator(store);
    }

    private NarrativeState stateWith(String agentId, Instant synthesisedAt,
                                      List<NarrativeFragment> fragments) {
        return new NarrativeState(agentId, "tenant-1",
                NarrativeScope.INDIVIDUAL, fragments, synthesisedAt, 5);
    }

    private IndividualEpisode episode(String id) {
        return new IndividualEpisode(id, T1, null, List.of("tag"),
                "desc-" + id, 0.5, List.of());
    }

    private DerivedTheme theme(String label) {
        return new DerivedTheme("t-" + label, T1, null, List.of(),
                label, 0.7, Map.of(DriveAxis.CURIOSITY, 0.3), List.of());
    }

    @Test
    void tick_firstLoad_returnsUpdated() {
        var state = stateWith("agent-1", T1, List.of(episode("e1"), theme("helper")));
        when(store.load("agent-1", "tenant-1")).thenReturn(state);

        var tick = orchestrator.tick("agent-1", "tenant-1");

        assertThat(tick).isInstanceOf(NarrativeTick.Updated.class);
        var updated = (NarrativeTick.Updated) tick;
        assertThat(updated.previous()).isNull();
        assertThat(updated.current()).isSameAs(state);
        assertThat(updated.newEpisodeIds()).containsExactly("e1");
        assertThat(updated.newThemeLabels()).containsExactly("helper");
    }

    @Test
    void tick_noStateInStore_returnsNoChange() {
        when(store.load("agent-1", "tenant-1")).thenReturn(null);

        var tick = orchestrator.tick("agent-1", "tenant-1");

        assertThat(tick).isInstanceOf(NarrativeTick.NoChange.class);
        assertThat(((NarrativeTick.NoChange) tick).reason())
                .isEqualTo("no narrative in store");
    }

    @Test
    void tick_sameTimestamp_returnsNoChange() {
        var state = stateWith("agent-1", T1, List.of(episode("e1")));
        when(store.load("agent-1", "tenant-1")).thenReturn(state);

        orchestrator.tick("agent-1", "tenant-1");
        var tick2 = orchestrator.tick("agent-1", "tenant-1");

        assertThat(tick2).isInstanceOf(NarrativeTick.NoChange.class);
        assertThat(((NarrativeTick.NoChange) tick2).reason())
                .isEqualTo("no new synthesis");
    }

    @Test
    void tick_newSynthesis_detectsNewEpisodesAndThemes() {
        var state1 = stateWith("agent-1", T1, List.of(episode("e1"), theme("helper")));
        when(store.load("agent-1", "tenant-1")).thenReturn(state1);
        orchestrator.tick("agent-1", "tenant-1");

        var state2 = stateWith("agent-1", T2, List.of(episode("e1"), episode("e2"),
                theme("helper"), theme("expert")));
        when(store.load("agent-1", "tenant-1")).thenReturn(state2);

        var tick = orchestrator.tick("agent-1", "tenant-1");

        assertThat(tick).isInstanceOf(NarrativeTick.Updated.class);
        var updated = (NarrativeTick.Updated) tick;
        assertThat(updated.previous()).isNotNull();
        assertThat(updated.newEpisodeIds()).containsExactly("e2");
        assertThat(updated.newThemeLabels()).containsExactly("expert");
    }

    @Test
    void currentNarrative_returnsEmpty_beforeTick() {
        assertThat(orchestrator.currentNarrative("agent-1", "tenant-1")).isEmpty();
    }

    @Test
    void currentNarrative_returnsCached_afterTick() {
        var state = stateWith("agent-1", T1, List.of(episode("e1")));
        when(store.load("agent-1", "tenant-1")).thenReturn(state);

        orchestrator.tick("agent-1", "tenant-1");

        assertThat(orchestrator.currentNarrative("agent-1", "tenant-1"))
                .isPresent()
                .containsSame(state);
    }

    @Test
    void tick_perAgentIsolation() {
        var state1 = stateWith("agent-1", T1, List.of(episode("e1")));
        var state2 = stateWith("agent-2", T1, List.of(episode("e2"), episode("e3")));
        when(store.load("agent-1", "tenant-1")).thenReturn(state1);
        when(store.load("agent-2", "tenant-1")).thenReturn(state2);

        orchestrator.tick("agent-1", "tenant-1");
        orchestrator.tick("agent-2", "tenant-1");

        assertThat(orchestrator.currentNarrative("agent-1", "tenant-1").get()
                .episodes()).hasSize(1);
        assertThat(orchestrator.currentNarrative("agent-2", "tenant-1").get()
                .episodes()).hasSize(2);
    }

    @Test
    void tick_threadSafety() throws InterruptedException {
        var state = stateWith("agent-1", T1, List.of(episode("e1")));
        when(store.load(anyString(), anyString())).thenReturn(state);

        var errors = new AtomicInteger(0);
        var latch = new CountDownLatch(10);
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 10; i++) {
                var agentId = "agent-" + (i % 3);
                executor.submit(() -> {
                    try {
                        orchestrator.tick(agentId, "tenant-1");
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }
        assertThat(errors.get()).isZero();
    }

    @Test
    void evictIdleRemovesStaleEntries() {
        var state = stateWith("agent-1", T1, List.of(episode("e1")));
        when(store.load("agent-1", "tenant-1")).thenReturn(state);

        orchestrator.tick("agent-1", "tenant-1");
        assertThat(orchestrator.currentNarrative("agent-1", "tenant-1")).isPresent();

        orchestrator.evictIdle(java.time.Duration.ZERO);

        assertThat(orchestrator.currentNarrative("agent-1", "tenant-1")).isEmpty();
    }
}
