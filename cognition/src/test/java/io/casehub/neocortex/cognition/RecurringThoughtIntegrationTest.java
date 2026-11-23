package io.casehub.neocortex.cognition;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.cognitive.RecurringThoughtConfig;
import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.casehub.neocortex.memory.inmem.InMemoryMemoryStore;
import io.casehub.neocortex.mindmap.ContinuumPosition;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.mindmap.intelligence.RecurringThoughtScorer;
import io.casehub.platform.api.identity.CurrentPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import java.time.Duration;

class RecurringThoughtIntegrationTest {

    private InMemoryMindMapStore mindMapStore;
    private InMemoryMemoryStore memoryStore;
    private RecurringThoughtScorer scorer;
    private static final String TENANT = "test-tenant";
    private static final String AGENT = "agent-1";
    private static final Instant NOW = Instant.now();

    private final CurrentPrincipal principal = new CurrentPrincipal() {
        @Override public String actorId() { return "actor"; }
        @Override public Set<String> groups() { return Set.of(); }
        @Override public String tenancyId() { return TENANT; }
        @Override public boolean isCrossTenantAdmin() { return true; }
    };

    @BeforeEach
    void setUp() {
        mindMapStore = new InMemoryMindMapStore();
        memoryStore = new InMemoryMemoryStore(principal);
        scorer = new RecurringThoughtScorer(mindMapStore, memoryStore);
    }

    @Test
    void lowerFixationThresholdClassifiesEasier() {
        // Simulates personality differentiation: a high-neuroticism agent would have
        // lower fixation threshold (derived by CognitiveDerivationEngine, tested separately).
        // Here we verify the scorer respects different thresholds.
        var sensitiveConfig = new RecurringThoughtConfig(
                Map.of(), 0.15, 0.5, 0.75, Duration.ofDays(7), 3);
        var resilientConfig = new RecurringThoughtConfig(
                Map.of(), 0.45, 0.7, 0.85, Duration.ofDays(7), 3);

        String sgId = createSubgraph();
        String nodeId = mindMapStore.addNode(NodeInput.of("worry-topic", sgId)
                .withConfidence(Confidence.stated(0.3, NOW))
                .withPleasure(-0.5).withArousal(0.6), TENANT);
        addExperienceMemories("worry-topic", 5);

        var sensitiveResult = scorer.score(nodeId, TENANT, sensitiveConfig, null, null);
        var resilientResult = scorer.score(nodeId, TENANT, resilientConfig, null, null);

        assertThat(sensitiveResult.position().ordinal())
                .isGreaterThanOrEqualTo(resilientResult.position().ordinal())
                .as("Lower fixation threshold should classify at same or worse position");
    }

    @Test
    void scorerToRenderingPipeline() {
        var store = new InMemoryMindMapStore();
        String sgId = store.createSubgraph(
                new SubgraphInput("cognitive", SubgraphTypes.CONCEPT, null), TENANT);
        store.addNode(NodeInput.of("spiral-topic", sgId)
                               .withConfidence(Confidence.stated(0.3, NOW))
                               .withPleasure(-0.7).withArousal(0.8)
                               .withProperty("agent-id", AGENT), TENANT);

        var harness = CognitionTestHarness.builder()
                                          .withMindMapStore(store)
                                          .agentFromDefaults(AGENT, CognitiveDefaults.empty(AGENT))
                                          .withConsolidationEnabled(true)
                                          .build();

        harness.addExperienceMemories(AGENT, TENANT, "spiral-topic", 8);
        harness.tickAndConsolidate(AGENT, TENANT);

        var cmap = harness.continuumMap(AGENT);
        assertThat(cmap.values()).anySatisfy(p ->
                                                     assertThat(p).isNotEqualTo(ContinuumPosition.HEALTHY));

        var prompt = harness.renderPrompt(AGENT, TENANT);
        assertThat(prompt).isNotNull();
    }

    @Test
    void defaultThresholdsAreMonotonicallyIncreasing() {
        var config = RecurringThoughtConfig.defaults();
        assertThat(config.fixationThreshold()).isGreaterThan(0.0);
        assertThat(config.obsessionThreshold()).isGreaterThan(config.fixationThreshold());
        assertThat(config.ruminationThreshold()).isGreaterThan(config.obsessionThreshold());
    }

    // --- helpers ---

    private String createSubgraph() {
        return mindMapStore.createSubgraph(
                new SubgraphInput("cognitive", SubgraphTypes.CONCEPT, null), TENANT);
    }

    private void addExperienceMemories(String topicName, int count) {
        for (int i = 0; i < count; i++) {
            memoryStore.store(MemoryInput.of(
                    Subject.of("agent", AGENT),
                    ExperienceEvents.DOMAIN,
                    TENANT,
                    "Observation about " + topicName + " iteration " + i)
                    .withAttributes(Map.of("event-type", "observation", "subject", topicName)));
        }
    }
}
