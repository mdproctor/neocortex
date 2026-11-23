package io.casehub.neocortex.cognition.goal;

import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognitive.PadProjection;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.memory.inmem.InMemoryMemoryStore;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.mindmap.intelligence.HeuristicGoalAppraisal;
import io.casehub.platform.api.identity.CurrentPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.neocortex.mindmap.NodeUpdate;

class CognitiveGoalOrchestratorTest {

    private InMemoryMindMapStore mindMapStore;
    private InMemoryMemoryStore memoryStore;
    private CognitiveGoalOrchestrator orchestrator;
    private String goalSubgraphId;
    private static final String TENANT = "t1";
    private static final String AGENT = "agent-1";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        mindMapStore = new InMemoryMindMapStore();
        CurrentPrincipal principal = new CurrentPrincipal() {
            @Override public String actorId() { return AGENT; }
            @Override public Set<String> groups() { return Set.of(); }
            @Override public String tenancyId() { return TENANT; }
            @Override public boolean isCrossTenantAdmin() { return true; }
        };
        memoryStore = new InMemoryMemoryStore(principal);
        goalSubgraphId = mindMapStore.createSubgraph(
                new SubgraphInput("Goals", SubgraphTypes.GOAL, null), TENANT);

        orchestrator = new CognitiveGoalOrchestrator(
                mindMapStore,
                new HeuristicGoalAppraisal(),
                memoryStore,
                CognitiveGoalConfig.defaults(),
                (a, t) -> PadProjection.NEUTRAL,
                CLOCK);
    }

    @Test
    void tick_queriesGoalsAndProducesEmotions() {
        createGoal("active", 0.7, 0.3, 0.8);
        var ctx = tickContext();

        orchestrator.tick(ctx);

        var state = orchestrator.currentState(AGENT, TENANT);
        assertThat(state).isPresent();
        assertThat(state.get().goals()).isNotEmpty();
        assertThat(state.get().goals().get(0).emotions()).isNotEmpty();
    }

    @Test
    void tick_recordsSurfacingEvent() {
        createGoal("active", 0.7, 0.3, 0.8);

        orchestrator.tick(tickContext());

        var memories = memoryStore.query(MemoryQuery.forSubject(
                Subject.of("agent", AGENT), new MemoryDomain("experience"), TENANT)
                .withLimit(10));
        assertThat(memories).anyMatch(m ->
                "goal-surfaced".equals(m.attributes().get("cognitive-event")));
    }

    @Test
    void tick_recordsAgentExperience() {
        createGoal("active", 0.7, 0.5, 0.5);

        orchestrator.tick(tickContext());

        var memories = memoryStore.query(MemoryQuery.forSubject(
                Subject.of("agent", AGENT), new MemoryDomain("experience"), TENANT)
                .withLimit(10));
        assertThat(memories).isNotEmpty();
        assertThat(memories.get(0).attributes()).containsKey("emotion-type");
    }

    @Test
    void tick_detectsDecaySignal() {
        String goalId = createGoal("active", 0.7, 0.3, 0.8);
        mindMapStore.updateNode(goalId,
                NodeUpdate.empty()
                        .withPropertiesToSet(Map.of("decay-signal", "dormant",
                                "eidos-goal-name", "explore-topic")),
                TENANT);

        orchestrator.tick(tickContext());

        var state = orchestrator.currentState(AGENT, TENANT);
        assertThat(state).isPresent();
        assertThat(state.get().revisions()).hasSize(1);
        assertThat(state.get().revisions().get(0).decaySignal()).isEqualTo("dormant");
    }

    @Test
    void tick_surfacingCooldown_preventsRepeatSurfacing() {
        createGoal("active", 0.7, 0.3, 0.8);

        orchestrator.tick(tickContext());
        int firstSurfacingCount = countSurfacingEvents();

        orchestrator.tick(tickContext());
        int secondSurfacingCount = countSurfacingEvents();

        assertThat(secondSurfacingCount).isEqualTo(firstSurfacingCount);
    }

    @Test
    void tick_nullPriority_fallsThroughToSurfacing() {
        mindMapStore.addNode(
                NodeInput.of("New goal", goalSubgraphId)
                        .withProperties(Map.of("description", "just created", "status", "active")),
                TENANT);

        orchestrator.tick(tickContext());

        var state = orchestrator.currentState(AGENT, TENANT);
        assertThat(state).isPresent();
        assertThat(state.get().goals()).isNotEmpty();
    }

    @Test
    void currentState_emptyWhenNotTicked() {
        var state = orchestrator.currentState(AGENT, TENANT);
        assertThat(state).isEmpty();
    }

    @Test
    void completedGoals_notSurfaced() {
        createGoal("completed", 0.7, 0.0, 1.0);

        orchestrator.tick(tickContext());

        int surfacingCount = countSurfacingEvents();
        assertThat(surfacingCount).isEqualTo(0);
    }

    private String createGoal(String status, double priority, double urgency, double feasibility) {
        return mindMapStore.addNode(
                NodeInput.of("Test goal", goalSubgraphId)
                         .withProperties(Map.of(
                                 "description", "test",
                                 "status", status,
                                 "priority", String.valueOf(priority),
                                 "drive-intensity", String.valueOf(priority),
                                 "urgency", String.valueOf(urgency),
                                 "feasibility", String.valueOf(feasibility))),
                TENANT);
    }

    private CognitionTickContext tickContext() {
        return new CognitionTickContext(AGENT, TENANT, null, (a, t) -> Set.of());
    }

    private int countSurfacingEvents() {
        var memories = memoryStore.query(MemoryQuery.forSubject(
                Subject.of("agent", AGENT), new MemoryDomain("experience"), TENANT)
                .withLimit(100));
        return (int) memories.stream()
                .filter(m -> "goal-surfaced".equals(m.attributes().get("cognitive-event")))
                .count();
    }
}
