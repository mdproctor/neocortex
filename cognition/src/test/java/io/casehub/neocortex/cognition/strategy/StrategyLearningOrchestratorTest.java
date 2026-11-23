package io.casehub.neocortex.cognition.strategy;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.cognitive.index.CognitiveMemoryRetriever;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.memory.engagement.EngagementEvent;
import io.casehub.neocortex.memory.reflection.ReflectionOrchestrator;
import io.casehub.platform.agent.AgentEvent;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.casehub.neocortex.session.testing.StubPipelines;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Uni;
import java.util.Optional;

class StrategyLearningOrchestratorTest {

    private StrategyMemory strategyMemory;
    private ReflectionOrchestrator reflectionOrchestrator;
    private StrategyLearningOrchestrator orchestrator;
    private Clock clock;

    @BeforeEach
    void setUp() {
        strategyMemory         = mock(StrategyMemory.class);
        reflectionOrchestrator = mock(ReflectionOrchestrator.class);
        clock                  = Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneId.of("UTC"));
        orchestrator           = new StrategyLearningOrchestrator(
                strategyMemory, reflectionOrchestrator,
                StrategyLearningConfig.defaults(), clock, null);
    }

    // --- record + tick: tier 1 ---

    @Test void tick_noSignals_returnsNoChange() {
        var result = orchestrator.tick("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyLearningTick.NoChange.class);
    }

    @Test void tick_withTurnOutcomes_belowThreshold_returnsObserved() {
        orchestrator.record(turnOutcome("case-1", true, 0.5, 100), "agent-1", "user-1", "tenant-1");
        orchestrator.record(turnOutcome("case-1", false, -0.5, 200), "agent-1", "user-1", "tenant-1");

        var result = orchestrator.tick("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyLearningTick.Observed.class);
        var observed = (StrategyLearningTick.Observed) result;
        assertThat(observed.signalsProcessed()).isEqualTo(2);
        assertThat(observed.engagementRate()).isCloseTo(0.5, within(0.01));
        assertThat(observed.meanSentiment()).isCloseTo(0.0, within(0.01));
    }

    @Test void tick_secondTickAccumulatesCounters() {
        orchestrator.record(turnOutcome("case-1", true, 0.5, 100), "agent-1", "user-1", "tenant-1");
        orchestrator.tick("agent-1", "tenant-1");

        orchestrator.record(turnOutcome("case-1", false, -0.5, 200), "agent-1", "user-1", "tenant-1");
        var result = orchestrator.tick("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyLearningTick.Observed.class);
        var observed = (StrategyLearningTick.Observed) result;
        assertThat(observed.signalsProcessed()).isEqualTo(1);
        assertThat(observed.engagementRate()).isCloseTo(0.5, within(0.01));
    }

    @Test void tick_afterDrain_secondTickWithNoSignals_returnsNoChange() {
        orchestrator.record(turnOutcome("case-1", true, 0.5, 100), "agent-1", "user-1", "tenant-1");
        orchestrator.tick("agent-1", "tenant-1");

        var result = orchestrator.tick("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyLearningTick.NoChange.class);
    }

    // --- record + tick: tier 2 ---

    @Test void tick_withConversationOutcome_returnsLearned() {
        orchestrator.record(turnOutcome("case-1", true, 0.3, 150), "agent-1", "user-1", "tenant-1");
        orchestrator.record(turnOutcome("case-1", true, 0.5, 200), "agent-1", "user-1", "tenant-1");
        orchestrator.record(turnOutcome("case-1", true, 0.1, 100), "agent-1", "user-1", "tenant-1");
        orchestrator.record(
                new EngagementSignal.ConversationOutcome("case-1", "summary", 3),
                "agent-1", "user-1", "tenant-1");

        var result = orchestrator.tick("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyLearningTick.Learned.class);
        var learned = (StrategyLearningTick.Learned) result;
        assertThat(learned.signalsProcessed()).isEqualTo(3);
        assertThat(learned.casesStored()).isEqualTo(1);
        assertThat(learned.conversationsStored()).contains("case-1");
        verify(strategyMemory).storeEvidence(any(EngagementEvidence.class));
    }

    @Test void tick_conversationCorrelation_matchesByCaseId() {
        orchestrator.record(turnOutcome("case-1", true, 0.3, 100), "agent-1", "user-1", "tenant-1");
        orchestrator.record(turnOutcome("case-2", true, 0.5, 200), "agent-1", "user-1", "tenant-1");
        orchestrator.record(turnOutcome("case-1", true, 0.1, 150), "agent-1", "user-1", "tenant-1");

        orchestrator.record(
                new EngagementSignal.ConversationOutcome("case-1", "summary", 2),
                "agent-1", "user-1", "tenant-1");

        var result = orchestrator.tick("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyLearningTick.Learned.class);
        assertThat(((StrategyLearningTick.Learned) result).casesStored()).isEqualTo(1);

        var captor = ArgumentCaptor.forClass(EngagementEvidence.class);
        verify(strategyMemory).storeEvidence(captor.capture());
        assertThat(captor.getValue().turnCount()).isEqualTo(2);
    }

    @Test void tick_featureExtraction_avgResponseLength() {
        orchestrator.record(turnOutcome("case-1", true, 0.0, 100), "agent-1", "user-1", "tenant-1");
        orchestrator.record(turnOutcome("case-1", true, 0.0, 300), "agent-1", "user-1", "tenant-1");
        orchestrator.record(
                new EngagementSignal.ConversationOutcome("case-1", "summary", 2),
                "agent-1", "user-1", "tenant-1");

        orchestrator.tick("agent-1", "tenant-1");

        var captor = ArgumentCaptor.forClass(EngagementEvidence.class);
        verify(strategyMemory).storeEvidence(captor.capture());
        assertThat(captor.getValue().avgResponseLength())
                .isCloseTo(200.0, within(0.01));
        assertThat(captor.getValue().continuationRate())
                .isEqualTo(1.0);
    }

    @Test void tick_featureExtraction_dimensionalSnapshotAveraging() {
        var snap1 = Map.of("verbosity", 0.3, "formality", 0.7);
        var snap2 = Map.of("verbosity", 0.5, "formality", 0.9);
        orchestrator.record(
                new EngagementSignal.TurnOutcome(engagementEvent("case-1", true, 0.0, 100), snap1, null),
                "agent-1", "user-1", "tenant-1");
        orchestrator.record(
                new EngagementSignal.TurnOutcome(engagementEvent("case-1", true, 0.0, 100), snap2, null),
                "agent-1", "user-1", "tenant-1");
        orchestrator.record(
                new EngagementSignal.ConversationOutcome("case-1", "summary", 2),
                "agent-1", "user-1", "tenant-1");

        orchestrator.tick("agent-1", "tenant-1");

        var captor = ArgumentCaptor.forClass(EngagementEvidence.class);
        verify(strategyMemory).storeEvidence(captor.capture());
        assertThat(captor.getValue().dimensionSnapshots().get("verbosity"))
                .isCloseTo(0.4, within(0.01));
        assertThat(captor.getValue().dimensionSnapshots().get("formality"))
                .isCloseTo(0.8, within(0.01));
    }

    @Test void tick_nullEngagementFields_handledGracefully() {
        var event = new EngagementEvent("agent-1", "user-1", "tenant-1", "case-1",
                                        "turn-1", null, "test", null, Map.of(),
                                        null, null, null, null, null, null);
        orchestrator.record(
                new EngagementSignal.TurnOutcome(event, Map.of(), null),
                "agent-1", "user-1", "tenant-1");

        var result = orchestrator.tick("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyLearningTick.Observed.class);
    }

    // --- turn accumulation across ticks ---

    @Test void tick_turnsAccumulateAcrossTicks_andMatchConversationOutcome() {
        for (int i = 0; i < 4; i++) {
            orchestrator.record(turnOutcome("conv-1", true, 0.1 * i, 100 + i * 50),
                    "agent-1", "user-1", "tenant-1");
            var result = orchestrator.tick("agent-1", "tenant-1");
            assertThat(result).isInstanceOf(StrategyLearningTick.Observed.class);
        }

        orchestrator.record(
                new EngagementSignal.ConversationOutcome("conv-1", "summary", 4),
                "agent-1", "user-1", "tenant-1");
        var result = orchestrator.tick("agent-1", "tenant-1");

        assertThat(result).isInstanceOf(StrategyLearningTick.Learned.class);
        var learned = (StrategyLearningTick.Learned) result;
        assertThat(learned.casesStored()).isEqualTo(1);
        assertThat(learned.conversationsStored()).contains("conv-1");

        var captor = ArgumentCaptor.forClass(EngagementEvidence.class);
        verify(strategyMemory).storeEvidence(captor.capture());
        assertThat(captor.getValue().turnCount()).isEqualTo(4);
    }

    @Test void tick_turnsWithDifferentCaseIds_accumulateSeparately() {
        orchestrator.record(turnOutcome("conv-A", true, 0.3, 100), "agent-1", "user-1", "tenant-1");
        orchestrator.tick("agent-1", "tenant-1");
        orchestrator.record(turnOutcome("conv-B", true, 0.5, 200), "agent-1", "user-1", "tenant-1");
        orchestrator.tick("agent-1", "tenant-1");
        orchestrator.record(turnOutcome("conv-A", true, 0.1, 150), "agent-1", "user-1", "tenant-1");
        orchestrator.tick("agent-1", "tenant-1");

        orchestrator.record(
                new EngagementSignal.ConversationOutcome("conv-A", "summary-A", 2),
                "agent-1", "user-1", "tenant-1");
        var result = orchestrator.tick("agent-1", "tenant-1");

        assertThat(result).isInstanceOf(StrategyLearningTick.Learned.class);
        var captor = ArgumentCaptor.forClass(EngagementEvidence.class);
        verify(strategyMemory).storeEvidence(captor.capture());
        assertThat(captor.getValue().turnCount()).isEqualTo(2);
    }

    @Test void tick_turnsWithNullCaseId_notAccumulated() {
        orchestrator.record(turnOutcome(null, true, 0.3, 100), "agent-1", "user-1", "tenant-1");
        orchestrator.tick("agent-1", "tenant-1");
        orchestrator.record(turnOutcome(null, true, 0.5, 200), "agent-1", "user-1", "tenant-1");
        orchestrator.tick("agent-1", "tenant-1");

        orchestrator.record(
                new EngagementSignal.ConversationOutcome("conv-1", "summary", 2),
                "agent-1", "user-1", "tenant-1");
        var result = orchestrator.tick("agent-1", "tenant-1");

        assertThat(result).isInstanceOf(StrategyLearningTick.Observed.class);
    }

    // --- auto-reflect ---

    @SuppressWarnings("unchecked")
    @Test
    void tick_autoTriggersReflect_whenEnoughCasesStored() throws Exception {
        var storedEvidence = new CopyOnWriteArrayList<EngagementEvidence>();
        var config = new StrategyLearningConfig(
                1, 2, 50, 10, 0.5, 100, Duration.ofSeconds(1),
                new MemoryDomain("test"),
                "engagement-evidence", "strategy-profile",
                0.6, 0.4, 0.2);
        var storedProfile = new AtomicReference<StrategyProfile>();
        var testStrategyMemory = mock(StrategyMemory.class);
        doAnswer(inv -> {
            storedProfile.set(inv.getArgument(0));
            return null;
        })
                .when(testStrategyMemory).store(any(StrategyProfile.class));
        doAnswer(inv -> {
            storedEvidence.add(inv.getArgument(0));
            return null;
        })
                .when(testStrategyMemory).storeEvidence(any(EngagementEvidence.class));
        when(testStrategyMemory.lookup("agent-1", "tenant-1"))
                .thenAnswer(inv -> Optional.ofNullable(storedProfile.get()));
        when(testStrategyMemory.evidenceCount("agent-1", "tenant-1"))
                .thenAnswer(inv -> storedEvidence.size());
        when(testStrategyMemory.recentEvidence(any(), any(), anyInt()))
                .thenAnswer(inv -> new ArrayList<>(storedEvidence));
        var testOrchestrator = new StrategyLearningOrchestrator(
                testStrategyMemory,
                (a, t, s, m) -> List.of(), config, clock,
                StubPipelines.create(
            init -> new AgentSession() {
                @Override public Multi<AgentEvent> query(String prompt) {
                    return Multi.createFrom().item(new AgentEvent.TextDelta(
                        "{\"guidelines\":[\"use specific examples\"],\"dimensionDeltas\":{}}"));
                }
                @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
                @Override public void close(Duration timeout) {}
            }));

        for (int i = 0; i < 2; i++) {
            storedEvidence.add(new EngagementEvidence(
                    "agent-1", "user", "tenant-1",
                    null, "case-" + i,
                    3, 0.7, 100.0, 0.1,
                    Map.of("verbosity", 0.5), Instant.ofEpochMilli(1000 + i * 100)));
        }

        for (int j = 0; j < 3; j++) {
            testOrchestrator.record(turnOutcome("conv-x", true, 0.1, 100),
                                    "agent-1", "user-1", "tenant-1");
        }
        testOrchestrator.record(
                new EngagementSignal.ConversationOutcome("conv-x", "summary", 3),
                "agent-1", "user-1", "tenant-1");
        testOrchestrator.tick("agent-1", "tenant-1");

        Thread.sleep(3000);

        var strategy = testOrchestrator.currentStrategy("agent-1", "tenant-1");
        assertThat(strategy).isPresent();
        assertThat(strategy.get().guidelines()).isNotEmpty();
        assertThat(strategy.get().guidelines()).contains("use specific examples");
    }

    // --- currentStrategy ---

    @Test void currentStrategy_returnsEmpty_whenNoProfile() {
        when(strategyMemory.lookup("agent-1", "tenant-1")).thenReturn(Optional.empty());
        assertThat(orchestrator.currentStrategy("agent-1", "tenant-1")).isEmpty();
    }

    @Test void currentStrategy_returnsFromStore_whenNoInMemoryState() {
        var profile = new StrategyProfile("agent-1", "tenant-1",
                Map.of("verbosity", 0.3), List.of("Be concise"), Instant.now(), 5);
        when(strategyMemory.lookup("agent-1", "tenant-1"))
                .thenReturn(Optional.of(profile));
        var result = orchestrator.currentStrategy("agent-1", "tenant-1");
        assertThat(result).isPresent();
        assertThat(result.get().dimensions().get("verbosity")).isEqualTo(0.3);
    }

    // --- reflect ---

    @Test void reflect_insufficientCases_returnsNoChange() {
        when(strategyMemory.recentEvidence(any(), any(), anyInt())).thenReturn(List.of());
        var result = orchestrator.reflect("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyReflection.NoChange.class);
        assertThat(((StrategyReflection.NoChange) result).reason()).contains("insufficient");
    }

    @Test void reflect_withSufficientCases_producesReflected() {
        var cases = buildEngagementCases(5, "agent-1");
        when(strategyMemory.recentEvidence(any(), any(), anyInt())).thenReturn(cases);
        when(reflectionOrchestrator.reflect(any(), any(), any(), anyInt()))
                .thenReturn(List.of("Agent tends to be verbose"));

        mockLlmResponse("{\"guidelines\":[\"Be more concise\",\"Ask follow-up questions\"]," +
                "\"dimensionDeltas\":{\"verbosity\":-0.1}}");

        var result = orchestrator.reflect("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyReflection.Reflected.class);
        var reflected = (StrategyReflection.Reflected) result;
        assertThat(reflected.newGuidelines()).contains("Be more concise");
        assertThat(reflected.profile().dimensions().get("verbosity")).isCloseTo(0.4, within(0.01));
        assertThat(reflected.profile().dimensions().get("formality")).isCloseTo(0.5, within(0.01));
        assertThat(reflected.evidenceCases()).isEqualTo(5);
        verify(strategyMemory).store(any(StrategyProfile.class));
    }

    @Test void reflect_malformedLlmOutput_returnsNoChange() {
        var cases = buildEngagementCases(5, "agent-1");
        when(strategyMemory.recentEvidence(any(), any(), anyInt())).thenReturn(cases);
        when(reflectionOrchestrator.reflect(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        mockLlmResponse("not valid json at all");

        var result = orchestrator.reflect("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyReflection.NoChange.class);
        verify(strategyMemory, never()).store(any());
    }

    @Test void reflect_clampsDeltas_toRange() {
        var profile = new StrategyProfile("agent-1", "tenant-1",
                Map.of("verbosity", 0.95, "formality", 0.05,
                        "initiative", 0.5, "directness", 0.5, "questionRate", 0.5),
                List.of(), Instant.now(), 0);
        when(strategyMemory.lookup("agent-1", "tenant-1"))
                .thenReturn(Optional.of(profile));

        var cases = buildEngagementCases(5, "agent-1");
        when(strategyMemory.recentEvidence(any(), any(), anyInt())).thenReturn(cases);
        when(reflectionOrchestrator.reflect(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        mockLlmResponse("{\"guidelines\":[\"Test\"]," +
                "\"dimensionDeltas\":{\"verbosity\":0.2,\"formality\":-0.2}}");

        var result = orchestrator.reflect("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyReflection.Reflected.class);
        var reflected = (StrategyReflection.Reflected) result;
        assertThat(reflected.profile().dimensions().get("verbosity")).isEqualTo(1.0);
        assertThat(reflected.profile().dimensions().get("formality")).isEqualTo(0.0);
    }

    @Test void reflect_ignoresUnknownDimensionKeys() {
        var cases = buildEngagementCases(5, "agent-1");
        when(strategyMemory.recentEvidence(any(), any(), anyInt())).thenReturn(cases);
        when(reflectionOrchestrator.reflect(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        mockLlmResponse("{\"guidelines\":[\"Test\"]," +
                "\"dimensionDeltas\":{\"verbosity\":-0.1,\"unknown_dim\":0.5}}");

        var result = orchestrator.reflect("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyReflection.Reflected.class);
        var reflected = (StrategyReflection.Reflected) result;
        assertThat(reflected.profile().dimensions()).doesNotContainKey("unknown_dim");
        assertThat(reflected.profile().dimensions().get("verbosity")).isCloseTo(0.4, within(0.01));
    }

    @Test void reflect_emptyGuidelines_retainsPrevious() {
        var profile = new StrategyProfile("agent-1", "tenant-1",
                Map.of("verbosity", 0.5, "formality", 0.5,
                        "initiative", 0.5, "directness", 0.5, "questionRate", 0.5),
                List.of("Existing guideline"), Instant.now(), 3);
        when(strategyMemory.lookup("agent-1", "tenant-1"))
                .thenReturn(Optional.of(profile));

        var cases = buildEngagementCases(5, "agent-1");
        when(strategyMemory.recentEvidence(any(), any(), anyInt())).thenReturn(cases);
        when(reflectionOrchestrator.reflect(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        mockLlmResponse("{\"guidelines\":[],\"dimensionDeltas\":{}}");

        var result = orchestrator.reflect("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyReflection.Reflected.class);
        var reflected = (StrategyReflection.Reflected) result;
        assertThat(reflected.newGuidelines()).containsExactly("Existing guideline");
    }

    @Test
    void reflect_filtersOtherAgentsCases() {
        var ownCases = buildEngagementCases(3, "agent-1");
        when(strategyMemory.recentEvidence(any(), any(), anyInt())).thenReturn(ownCases);

        var result = orchestrator.reflect("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyReflection.NoChange.class);
        assertThat(((StrategyReflection.NoChange) result).reason()).contains("insufficient");
    }

    @Test void reflect_llmWithCodeFences_stripsAndParses() {
        var cases = buildEngagementCases(5, "agent-1");
        when(strategyMemory.recentEvidence(any(), any(), anyInt())).thenReturn(cases);
        when(reflectionOrchestrator.reflect(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        mockLlmResponse("```json\n{\"guidelines\":[\"Fenced output\"],\"dimensionDeltas\":{}}\n```");

        var result = orchestrator.reflect("agent-1", "tenant-1");
        assertThat(result).isInstanceOf(StrategyReflection.Reflected.class);
        var reflected = (StrategyReflection.Reflected) result;
        assertThat(reflected.newGuidelines()).contains("Fenced output");
    }

    @Test void reflect_storesUpdatedProfile() {
        var cases = buildEngagementCases(5, "agent-1");
        when(strategyMemory.recentEvidence(any(), any(), anyInt())).thenReturn(cases);
        when(reflectionOrchestrator.reflect(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        mockLlmResponse("{\"guidelines\":[\"New guideline\"],\"dimensionDeltas\":{\"formality\":0.15}}");

        orchestrator.reflect("agent-1", "tenant-1");

        var captor = ArgumentCaptor.forClass(StrategyProfile.class);
        verify(strategyMemory).store(captor.capture());
        assertThat(captor.getValue().agentId()).isEqualTo("agent-1");
        assertThat(captor.getValue().tenantId()).isEqualTo("tenant-1");
        assertThat(captor.getValue().guidelines()).contains("New guideline");
        assertThat(captor.getValue().dimensions().get("formality")).isCloseTo(0.65, within(0.01));
    }

    // --- helpers ---


    @Test
    void reflect_withMemoryRetriever_includesMemoriesInPrompt() {
        var capturedPrompt = new AtomicReference<String>();
        var pipeline = StubPipelines.create(
                init -> new AgentSession() {
                    @Override
                    public Multi<AgentEvent> query(String prompt) {
                        capturedPrompt.set(prompt);
                        return Multi.createFrom().item(new AgentEvent.TextDelta(
                                "{\"guidelines\":[\"test\"],\"dimensionDeltas\":{}}"));
                    }

                    @Override
                    public Uni<Void> interrupt() {return Uni.createFrom().voidItem();}

                    @Override
                    public void close(Duration timeout)   {}
                });

        var mockStore = mock(CaseMemoryStore.class);
        when(mockStore.query(any())).thenReturn(List.of(
                new Memory("m1", Subject.of("unknown", "agent-1"), new MemoryDomain("experience"),
                           "tenant-1", null, "Observed user frustration with slow response times",
                           Map.of(), Instant.now(), Confidence.unknown(0.8), null, null, null, null, null),
                new Memory("m2", Subject.of("unknown", "agent-1"), new MemoryDomain("experience"),
                           "tenant-1", null, "User engaged positively with concise summaries",
                           Map.of(), Instant.now(), Confidence.unknown(0.9), null, null, null, null, null)
                                                       ));
        var retriever = new CognitiveMemoryRetriever(mockStore, List.of());

        var testOrchestrator = new StrategyLearningOrchestrator(
                strategyMemory, reflectionOrchestrator,
                StrategyLearningConfig.defaults(), clock, pipeline, retriever);

        var cases = buildEngagementCases(5, "agent-1");
        when(strategyMemory.recentEvidence(any(), any(), anyInt())).thenReturn(cases);
        when(reflectionOrchestrator.reflect(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        testOrchestrator.reflect("agent-1", "tenant-1");

        assertThat(capturedPrompt.get()).contains("Relevant experience memories:");
        assertThat(capturedPrompt.get()).contains("Observed user frustration with slow response times");
        assertThat(capturedPrompt.get()).contains("User engaged positively with concise summaries");
    }

    private EngagementSignal.TurnOutcome turnOutcome(String caseId, boolean responded,
                                                      double sentiment, int responseLength) {
        return new EngagementSignal.TurnOutcome(
                engagementEvent(caseId, responded, sentiment, responseLength),
                Map.of("verbosity", 0.5, "formality", 0.5),
                "excerpt");
    }

    private EngagementEvent engagementEvent(String caseId, boolean responded,
                                             double sentiment, int responseLength) {
        return new EngagementEvent("agent-1", "user-1", "tenant-1", caseId,
                                   "turn-1", null, "test description", null, Map.of(),
                                   responded, null, responseLength, sentiment, null, responded);
    }

    private List<EngagementEvidence> buildEngagementCases(int count, String agentId) {
        var cases = new ArrayList<EngagementEvidence>();
        for (int i = 0; i < count; i++) {
            cases.add(new EngagementEvidence(
                    agentId, "user-" + (i % 2), "tenant-1",
                    null, null,
                    3, 0.7 + i * 0.02, 200.0 + i * 10, 0.1 + i * 0.05,
                    Map.of("verbosity", 0.5, "formality", 0.5),
                    Instant.ofEpochMilli(1000 + i * 100)));
        }
        return cases;
    }

    private void mockLlmResponse(String text) {
        var pipeline = StubPipelines.create(
            init -> new AgentSession() {
                @Override public Multi<AgentEvent> query(String prompt) {
                    return Multi.createFrom().item(new AgentEvent.TextDelta(text));
                }
                @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
                @Override public void close(Duration timeout) {}
            });
        orchestrator = new StrategyLearningOrchestrator(
                strategyMemory, reflectionOrchestrator,
                StrategyLearningConfig.defaults(), clock, pipeline);
    }

    @Test void evictIdleRemovesStaleEntries() {
        orchestrator.record(turnOutcome("case-1", true, 0.5, 100), "agent-1", "user-1", "tenant-1");
        var tick = orchestrator.tick("agent-1", "tenant-1");
        assertThat(tick).isInstanceOf(StrategyLearningTick.Observed.class);

        orchestrator.evictIdle(Duration.ZERO);

        var afterEviction = orchestrator.tick("agent-1", "tenant-1");
        assertThat(afterEviction).isInstanceOf(StrategyLearningTick.NoChange.class);
    }
}
