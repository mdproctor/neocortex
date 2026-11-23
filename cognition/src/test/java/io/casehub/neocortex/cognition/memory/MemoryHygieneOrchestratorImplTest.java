package io.casehub.neocortex.cognition.memory;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrFeatureRecord;
import io.casehub.neocortex.memory.cbr.CbrMatch;
import io.casehub.neocortex.memory.cbr.CbrRecord;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.ScopeDecay;
import io.casehub.neocortex.memory.cbr.TemporalDecay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.casehub.platform.api.path.Path;

class MemoryHygieneOrchestratorImplTest {

    private CbrRecordStore store;
    private ConfidenceScorer scorer;
    @SuppressWarnings("unchecked")
    private Consumer<HygieneEvent> eventSink = mock(Consumer.class);
    private MemoryHygieneOrchestratorImpl orchestrator;

    private static final MemoryDomain DOMAIN = new MemoryDomain("agent");
    private static final RetentionConfig RETENTION = new RetentionConfig(0.5, 1.0, 1.0, 0.0, 0.0);

    @BeforeEach
    void setUp() {
        store = mock(CbrRecordStore.class);
        scorer = mock(ConfidenceScorer.class);
        var summariser = new NoOpConsolidationSummariser();
        orchestrator = new MemoryHygieneOrchestratorImpl(
                store, scorer,
                new TemporalDecay.HalfLife(Duration.ofDays(30)),
                new ScopeDecay.Step(0.5),
                summariser, DOMAIN, List.of("test-case"),
                () -> RETENTION, 100, 0.7, eventSink);
    }

    private CbrMatch<CbrRecord> makeMemory(String caseId, String agentId, Instant storedAt) {
        var cbrCase = new CbrFeatureRecord("problem text", "solution text", null, null,
                Map.of("k", FeatureValue.string("v")), null, agentId);
        return new CbrMatch<>(cbrCase, caseId, "memory", 0.5, false,
                Map.of(), storedAt, Path.root(), null);
    }

    @Test
    void tickReturnsIdleWhenNoMemories() {
        when(store.retrieveSimilar(any(), any())).thenReturn(List.of());
        orchestrator.tick("agent-1", "tenant-1");
        assertThat(orchestrator.knowledgeGaps("agent-1", "tenant-1").totalScored()).isEqualTo(0);
    }

    @Test
    void tickEvictsLowScoringMemories() {
        var memory = makeMemory("case-1", "agent-1", Instant.now().minus(Duration.ofDays(365)));
        when(store.retrieveSimilar(any(), any())).thenReturn(List.of(memory));
        when(scorer.score(any(), any())).thenReturn(0.1);

        orchestrator.tick("agent-1", "tenant-1");
        verify(store).erase(any());
        verify(eventSink).accept(any(HygieneEvent.MemoryEvicted.class));
    }

    @Test
    void tickRetainsHighScoringMemories() {
        var memory = makeMemory("case-1", "agent-1", Instant.now());
        when(store.retrieveSimilar(any(), any())).thenReturn(List.of(memory));
        when(scorer.score(any(), any())).thenReturn(0.9);

        orchestrator.tick("agent-1", "tenant-1");
        verify(store, never()).erase(any());
    }

    @Test
    void tickFiltersMemoriesByAgentId() {
        var ownMemory = makeMemory("case-1", "agent-1", Instant.now());
        var otherMemory = makeMemory("case-2", "other-agent", Instant.now());
        when(store.retrieveSimilar(any(), any())).thenReturn(List.of(ownMemory, otherMemory));
        when(scorer.score(any(), any())).thenReturn(0.9);

        orchestrator.tick("agent-1", "tenant-1");
        var gaps = orchestrator.knowledgeGaps("agent-1", "tenant-1");
        assertThat(gaps.totalScored()).isEqualTo(1);
    }

    @Test
    void doTickReturnsFailedOnException() {
        when(store.retrieveSimilar(any(), any())).thenThrow(new RuntimeException("store down"));
        var result = orchestrator.doTick("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(HygieneTick.Failed.class);
        assertThat(((HygieneTick.Failed) result).reason()).contains("store down");
    }

    @Test
    void tickSerializesPerAgent() throws Exception {
        when(store.retrieveSimilar(any(), any())).thenReturn(List.of());
        var t1 = new Thread(() -> orchestrator.tick("agent-1", "tenant-1"));
        var t2 = new Thread(() -> orchestrator.tick("agent-1", "tenant-1"));
        t1.start();
        t2.start();
        t1.join(5000);
        t2.join(5000);
        verify(store, atLeast(2)).retrieveSimilar(any(), any());
    }

    @Test
    void tenantGapSummaryAggregatesAcrossAgents() {
        var m1 = makeMemory("c1", "agent-1", Instant.now().minus(Duration.ofDays(1)));
        var m2 = makeMemory("c2", "agent-2", Instant.now().minus(Duration.ofDays(1)));
        when(store.retrieveSimilar(any(), any())).thenReturn(List.of(m1, m2));
        when(scorer.score(any(), any())).thenReturn(0.1);

        orchestrator.tick("agent-1", "tenant-1");
        orchestrator.tick("agent-2", "tenant-1");

        var summary = orchestrator.tenantGapSummary("tenant-1");
        assertThat(summary.totalScored()).isEqualTo(
                orchestrator.knowledgeGaps("agent-1", "tenant-1").totalScored()
                + orchestrator.knowledgeGaps("agent-2", "tenant-1").totalScored());
    }

    @Test
    void tenantGapSummaryReturnsEmptyForUnknownTenant() {
        assertThat(orchestrator.tenantGapSummary("unknown").totalScored()).isEqualTo(0);
    }

}
