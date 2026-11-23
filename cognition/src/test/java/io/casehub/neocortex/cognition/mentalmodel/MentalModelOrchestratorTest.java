package io.casehub.neocortex.cognition.mentalmodel;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.cognitive.index.CognitiveMemoryRetriever;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.memory.relationship.QualitySignal;
import io.casehub.neocortex.memory.relationship.RelationshipEvent;
import io.casehub.platform.agent.AgentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.casehub.neocortex.session.testing.StubPipelines;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.HashMap;
import org.assertj.core.data.Offset;

class MentalModelOrchestratorTest {

    @Mock MentalModelMemory memory;
    MentalModelOrchestrator orchestrator;
    Clock fixedClock;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        fixedClock = Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC);
        orchestrator = new MentalModelOrchestrator(memory,
                MentalModelConfig.defaults(), fixedClock, null);
    }

    @Test
    void tickWithNoSignalsReturnsUnchanged() {
        var tick = orchestrator.tick("agent1", "unknown", "tenant1");
        assertThat(tick).isInstanceOf(MentalModelTick.Unchanged.class);
    }

    @Test
    void tickReloadsFromStoreOnColdStart() {
        var now = fixedClock.instant();
        var snapshot = new MentalModelSnapshot("agent1", "user1", "tenant1",
                List.of(new AttributedState("k", "v", 0.8, 1, now, BdiDimension.BELIEF)),
                List.of(), List.of(), now, null, now);
        when(memory.lookup("agent1", "user1", "tenant1")).thenReturn(Optional.of(snapshot));

        var tick = orchestrator.tick("agent1", "user1", "tenant1");
        assertThat(tick).isInstanceOf(MentalModelTick.Updated.class);
        var loaded = ((MentalModelTick.Updated) tick).snapshot();
        assertThat(loaded.beliefs()).hasSize(1);
    }

    @Test
    void verbalBeliefCueExtractsBelief() {
        orchestrator.record(new MentalStateSignal.VerbalCue(
                "I think deployments are risky", CueType.BELIEF_STATEMENT),
                "agent1", "user1", "tenant1");

        var tick = orchestrator.tick("agent1", "user1", "tenant1");
        assertThat(tick).isInstanceOf(MentalModelTick.Updated.class);
        var beliefs = ((MentalModelTick.Updated) tick).snapshot().beliefs();
        assertThat(beliefs).hasSize(1);
        assertThat(beliefs.getFirst().description()).contains("deployments are risky");
        assertThat(beliefs.getFirst().confidence()).isGreaterThanOrEqualTo(0.7);
        assertThat(beliefs.getFirst().dimension()).isEqualTo(BdiDimension.BELIEF);
    }

    @Test
    void verbalDesireCueExtractsDesire() {
        orchestrator.record(new MentalStateSignal.VerbalCue(
                "I want a quick resolution", CueType.DESIRE_EXPRESSION),
                "agent1", "user1", "tenant1");

        var tick = orchestrator.tick("agent1", "user1", "tenant1");
        assertThat(tick).isInstanceOf(MentalModelTick.Updated.class);
        var desires = ((MentalModelTick.Updated) tick).snapshot().desires();
        assertThat(desires).hasSize(1);
        assertThat(desires.getFirst().dimension()).isEqualTo(BdiDimension.DESIRE);
    }

    @Test
    void verbalIntentionCueExtractsIntention() {
        orchestrator.record(new MentalStateSignal.VerbalCue(
                "I plan to escalate", CueType.INTENTION_DECLARATION),
                "agent1", "user1", "tenant1");

        var tick = orchestrator.tick("agent1", "user1", "tenant1");
        assertThat(tick).isInstanceOf(MentalModelTick.Updated.class);
        var intentions = ((MentalModelTick.Updated) tick).snapshot().intentions();
        assertThat(intentions).hasSize(1);
        assertThat(intentions.getFirst().dimension()).isEqualTo(BdiDimension.INTENTION);
    }

    @Test
    void recordAccumulatesMultipleSignals() {
        orchestrator.record(new MentalStateSignal.VerbalCue(
                "I think X", CueType.BELIEF_STATEMENT),
                "agent1", "user1", "tenant1");
        orchestrator.record(new MentalStateSignal.VerbalCue(
                "I want Y", CueType.DESIRE_EXPRESSION),
                "agent1", "user1", "tenant1");

        var tick = orchestrator.tick("agent1", "user1", "tenant1");
        assertThat(tick).isInstanceOf(MentalModelTick.Updated.class);
        var snapshot = ((MentalModelTick.Updated) tick).snapshot();
        assertThat(snapshot.beliefs()).isNotEmpty();
        assertThat(snapshot.desires()).isNotEmpty();
    }

    @Test
    void confidenceDecaysWithTime() {
        var t0 = Instant.parse("2026-08-20T12:00:00Z");
        var shortHalfLife = new MentalModelConfig(
                Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1),
                0.01, 0.3, 3, Duration.ofMinutes(5), 20, 100,
                Duration.ofHours(24), Duration.ofMinutes(1),
                "mental-model", "mental-model");

        var clock0 = Clock.fixed(t0, ZoneOffset.UTC);
        var orch = new MentalModelOrchestrator(memory, shortHalfLife, clock0, null);
        orch.record(new MentalStateSignal.VerbalCue("I think X", CueType.BELIEF_STATEMENT),
                "a", "u", "t");
        orch.tick("a", "u", "t");

        var t1 = t0.plus(Duration.ofHours(2));
        var clock1 = Clock.fixed(t1, ZoneOffset.UTC);
        var orch2 = new MentalModelOrchestrator(memory, shortHalfLife, clock1, null);

        var snapshot = new MentalModelSnapshot("a", "u", "t",
                List.of(new AttributedState("i_think_x", "I think X", 0.8, 1, t0, BdiDimension.BELIEF)),
                List.of(), List.of(), t0, null, t0);
        when(memory.lookup("a", "u", "t")).thenReturn(Optional.of(snapshot));

        var tick = orch2.tick("a", "u", "t");
        assertThat(tick).isInstanceOf(MentalModelTick.Updated.class);
        var beliefs = ((MentalModelTick.Updated) tick).snapshot().beliefs();
        assertThat(beliefs).hasSize(1);
        assertThat(beliefs.getFirst().confidence()).isLessThan(0.25);
    }

    @Test
    void evictsBelowConfidenceFloor() {
        var t0 = Instant.parse("2026-08-20T12:00:00Z");
        var fastDecay = new MentalModelConfig(
                Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1),
                0.5, 0.3, 3, Duration.ofMinutes(5), 20, 100,
                Duration.ofHours(24), Duration.ofMinutes(1),
                "mental-model", "mental-model");

        var t1 = t0.plus(Duration.ofMinutes(10));
        var clock1 = Clock.fixed(t1, ZoneOffset.UTC);
        var orch = new MentalModelOrchestrator(memory, fastDecay, clock1, null);

        var snapshot = new MentalModelSnapshot("a", "u", "t",
                List.of(new AttributedState("old_belief", "stale", 0.8, 3, t0, BdiDimension.BELIEF)),
                List.of(), List.of(), t0, null, t0);
        when(memory.lookup("a", "u", "t")).thenReturn(Optional.of(snapshot));

        var tick = orch.tick("a", "u", "t");
        assertThat(tick).isInstanceOf(MentalModelTick.Updated.class);
        var beliefs = ((MentalModelTick.Updated) tick).snapshot().beliefs();
        assertThat(beliefs).isEmpty();
    }

    @Test
    void projectReturnsMentalProjections() {
        orchestrator.record(new MentalStateSignal.VerbalCue(
                "I think the system is fragile", CueType.BELIEF_STATEMENT),
                "agent1", "user1", "tenant1");
        orchestrator.tick("agent1", "user1", "tenant1");

        var projections = orchestrator.project("agent1", "user1", "tenant1");
        assertThat(projections).isNotEmpty();
        var proj = projections.getFirst();
        assertThat(proj.value()).isTrue();
        assertThat(proj.confidence()).isGreaterThan(0.0);
        assertThat(proj.dimension()).isEqualTo(BdiDimension.BELIEF);
    }

    @Test
    void projectReturnsEmptyWhenNoState() {
        var projections = orchestrator.project("agent1", "unknown", "tenant1");
        assertThat(projections).isEmpty();
    }

    @Test
    void projectFiltersOutBelowProjectionFloor() {
        var t0 = Instant.parse("2026-08-20T12:00:00Z");
        var t1 = t0.plus(Duration.ofDays(5));
        var clock1 = Clock.fixed(t1, ZoneOffset.UTC);
        var orch = new MentalModelOrchestrator(memory,
                MentalModelConfig.defaults(), clock1, null);

        var snapshot = new MentalModelSnapshot("a", "u", "t",
                List.of(new AttributedState("old", "very old belief", 0.1, 1, t0, BdiDimension.BELIEF)),
                List.of(), List.of(), t0, null, t0);
        when(memory.lookup("a", "u", "t")).thenReturn(Optional.of(snapshot));
        orch.tick("a", "u", "t");

        var projections = orch.project("a", "u", "t");
        assertThat(projections.stream().filter(p -> p.conditionKey().equals("old"))).isEmpty();
    }

    @Test
    void observeConversationExtractsAllThreeEpistemicTiers() {
        var established = Map.of("p1", new GroundedFact(
                "p1", "system_stability", EpistemicStatus.ESTABLISHED,
                "the system is stable", Set.of("user1"), Set.of(), 1));
        var pending = Map.of("p2", new GroundedFact(
                "p2", "deadline_feasibility", EpistemicStatus.PENDING,
                "the deadline is achievable", Set.of(), Set.of(), 2));
        var disputed = Map.of("p3", new GroundedFact(
                "p3", "budget_adequacy", EpistemicStatus.DISPUTED,
                "the budget is sufficient", Set.of(), Set.of("user1"), 3));
        var commonGround = new CommonGroundState(established, pending, disputed);

        orchestrator.observeConversation(commonGround, "agent1", "user1", "tenant1");
        var tick = orchestrator.tick("agent1", "user1", "tenant1");

        assertThat(tick).isInstanceOf(MentalModelTick.Updated.class);
        var beliefs = ((MentalModelTick.Updated) tick).snapshot().beliefs();
        assertThat(beliefs).hasSize(3);

        var byKey = new HashMap<String, AttributedState>();
        beliefs.forEach(b -> byKey.put(b.key(), b));

        assertThat(byKey.get("system_stability").confidence()).isCloseTo(0.9,
                Offset.offset(0.01));
        assertThat(byKey.get("deadline_feasibility").confidence()).isCloseTo(0.5,
                Offset.offset(0.01));
        assertThat(byKey.get("budget_adequacy").confidence()).isCloseTo(0.3,
                Offset.offset(0.01));
    }

    @Test
    void relationshipCueAccumulatesSignal() {
        var event = new RelationshipEvent("agent1", "user1", "tenant1",
                                          "case1", "turn1", null, "conversation",
                                          QualitySignal.POSITIVE, "user expressed trust", 0.8, Map.of());
        orchestrator.record(new MentalStateSignal.RelationshipCue(event),
                "agent1", "user1", "tenant1");
        var tick = orchestrator.tick("agent1", "user1", "tenant1");
        assertThat(tick).isNotInstanceOf(MentalModelTick.Unchanged.class);
    }

    @Test
    void repeatedBeliefSignalsIncreaseEntrenchment() {
        orchestrator.record(new MentalStateSignal.VerbalCue(
                "I think X is true", CueType.BELIEF_STATEMENT),
                "agent1", "user1", "tenant1");
        orchestrator.tick("agent1", "user1", "tenant1");

        orchestrator.record(new MentalStateSignal.VerbalCue(
                "I think X is true", CueType.BELIEF_STATEMENT),
                "agent1", "user1", "tenant1");
        var tick2 = orchestrator.tick("agent1", "user1", "tenant1");
        assertThat(tick2).isInstanceOf(MentalModelTick.Updated.class);
        var beliefs = ((MentalModelTick.Updated) tick2).snapshot().beliefs();
        assertThat(beliefs.getFirst().entrenchment()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void normalizeKeyProducesStableSlug() {
        assertThat(MentalModelOrchestrator.normalizeKey("I think deployments are RISKY!"))
                .isEqualTo("i_think_deployments_are_risky");
        assertThat(MentalModelOrchestrator.normalizeKey("Hello, World"))
                .isEqualTo("hello_world");
    }

    @Test
    void ringBufferCapsAtMaxSize() {
        var smallBuffer = new MentalModelConfig(
                Duration.ofDays(7), Duration.ofDays(1), Duration.ofHours(4),
                0.1, 0.3, 3, Duration.ofMinutes(5), 20, 5,
                Duration.ofHours(24), Duration.ofMinutes(1),
                "mental-model", "mental-model");
        var orch = new MentalModelOrchestrator(memory, smallBuffer, fixedClock, null);

        for (int i = 0; i < 10; i++) {
            orch.record(new MentalStateSignal.BehavioralCue(
                    "signal-" + i, "action"),
                    "a", "u", "t");
        }

        orch.record(new MentalStateSignal.VerbalCue(
                "I think final thought", CueType.BELIEF_STATEMENT),
                "a", "u", "t");

        var tick = orch.tick("a", "u", "t");
        assertThat(tick).isNotInstanceOf(MentalModelTick.Unchanged.class);
        var snapshot = switch (tick) {
            case MentalModelTick.Updated u -> u.snapshot();
            case MentalModelTick.Inferred i -> i.snapshot();
            default -> null;
        };
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.beliefs()).hasSize(1);
        assertThat(snapshot.beliefs().getFirst().key()).contains("final_thought");
    }

    @Test
    void behavioralCueDoesNotExtractHeuristically() {
        orchestrator.record(new MentalStateSignal.BehavioralCue(
                "subject checked dashboard", "dashboard_check"),
                "agent1", "user1", "tenant1");

        var tick = orchestrator.tick("agent1", "user1", "tenant1");
        assertThat(tick).isInstanceOf(MentalModelTick.Updated.class);
        var snapshot = ((MentalModelTick.Updated) tick).snapshot();
        assertThat(snapshot.beliefs()).isEmpty();
        assertThat(snapshot.desires()).isEmpty();
        assertThat(snapshot.intentions()).isEmpty();
    }

    @Test
    void tick_withMemoryRetriever_includesMemoriesInLlmPrompt() {
        var capturedPrompt = new AtomicReference<String>();
        var pipeline = StubPipelines.create(
                init -> new AgentSession() {
                    @Override
                    public Multi<AgentEvent> query(String prompt) {
                        capturedPrompt.set(prompt);
                        return Multi.createFrom().item(new AgentEvent.TextDelta(
                                "{\"beliefs\":[],\"desires\":[],\"intentions\":[]}"));
                    }

                    @Override
                    public Uni<Void> interrupt() {return Uni.createFrom().voidItem();}

                    @Override
                    public void close(Duration timeout)   {}
                });

        var mockStore = mock(CaseMemoryStore.class);
        when(mockStore.query(any())).thenReturn(List.of(
                new Memory("m1", Subject.of("unknown", "agent1"), new MemoryDomain("experience"),
                           "tenant1", null, "User expressed concern about project timeline",
                           Map.of(), Instant.now(), Confidence.unknown(0.8), null, null, null, null, null),
                new Memory("m2", Subject.of("unknown", "agent1"), new MemoryDomain("experience"),
                           "tenant1", null, "User showed enthusiasm for new feature proposal",
                           Map.of(), Instant.now(), Confidence.unknown(0.9), null, null, null, null, null)
                                                       ));
        var retriever = new CognitiveMemoryRetriever(mockStore, List.of());

        var config = new MentalModelConfig(
                Duration.ofDays(7), Duration.ofDays(1), Duration.ofHours(4),
                0.1, 0.3, 3, Duration.ZERO,
                20, 100, Duration.ofHours(24), Duration.ofMinutes(1),
                "mental-model", "mental-model");
        var orch = new MentalModelOrchestrator(memory, config, fixedClock, pipeline, retriever);

        orch.record(new MentalStateSignal.VerbalCue("I think X", CueType.BELIEF_STATEMENT), "agent1", "user1", "tenant1");
        orch.record(new MentalStateSignal.VerbalCue("I want Y", CueType.DESIRE_EXPRESSION), "agent1", "user1", "tenant1");
        orch.record(new MentalStateSignal.VerbalCue("I plan Z", CueType.INTENTION_DECLARATION), "agent1", "user1", "tenant1");

        orch.tick("agent1", "user1", "tenant1");

        assertThat(capturedPrompt.get()).isNotNull();
        assertThat(capturedPrompt.get()).contains("Prior relevant experiences:");
        assertThat(capturedPrompt.get()).contains("User expressed concern about project timeline");
        assertThat(capturedPrompt.get()).contains("User showed enthusiasm for new feature proposal");
    }

    @Test
    void valenceRoundTripsSerializationCorrectly() {
        var now = Instant.now();
        var states = List.of(
                new AttributedState("hostile-key", "hostile desc", 0.9, 1, now, BdiDimension.INTENTION, IntentValence.HOSTILE),
                new AttributedState("coop-key", "cooperative desc", 0.7, 0, now, BdiDimension.DESIRE, IntentValence.COOPERATIVE),
                new AttributedState("neutral-key", "neutral desc", 0.5, 0, now, BdiDimension.BELIEF, IntentValence.NEUTRAL));

        var json         = MentalModelSchema.serializeStates(states);
        var deserialized = MentalModelSchema.deserializeStates(json);

        assertThat(deserialized).hasSize(3);
        assertThat(deserialized.get(0).valence()).isEqualTo(IntentValence.HOSTILE);
        assertThat(deserialized.get(1).valence()).isEqualTo(IntentValence.COOPERATIVE);
        assertThat(deserialized.get(2).valence()).isEqualTo(IntentValence.NEUTRAL);
    }

    @Test
    void oldSerializedDataWithoutValenceDeserializesToNeutral() {
        var json = "[{\"key\":\"old-key\",\"description\":\"old desc\",\"confidence\":0.8," +
                   "\"entrenchment\":1,\"lastReinforced\":\"2026-01-01T00:00:00Z\",\"dimension\":\"BELIEF\"}]";

        var deserialized = MentalModelSchema.deserializeStates(json);

        assertThat(deserialized).hasSize(1);
        assertThat(deserialized.get(0).key()).isEqualTo("old-key");
        assertThat(deserialized.get(0).valence()).isEqualTo(IntentValence.NEUTRAL);
    }

}
