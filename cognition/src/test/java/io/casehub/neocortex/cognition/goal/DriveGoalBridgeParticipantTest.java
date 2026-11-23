package io.casehub.neocortex.cognition.goal;

import io.casehub.eidos.api.GoalPriority;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.knowledge.TermNormalizer;
import io.casehub.neocortex.mindmap.MindMapSubgraph;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import io.casehub.neocortex.knowledge.ExpandedTerm;
import io.casehub.neocortex.mindmap.SubgraphInput;
import java.util.Set;

class DriveGoalBridgeParticipantTest {

    private InMemoryMindMapStore store;
    private GoalProposalOrchestrator orchestrator;
    private DriveGoalBridgeParticipant bridge;

    @BeforeEach
    void setUp() {
        store = new InMemoryMindMapStore();
        TermNormalizer normalizer = (term, domain) -> ExpandedTerm.passthrough(term);
        var driveOrchestrator = mock(DriveOrchestrator.class);
        orchestrator = new GoalProposalOrchestrator(
                driveOrchestrator, List.of(), Optional.empty(),
                GoalProposalConfig.defaults(), Clock.systemUTC());
        bridge = new DriveGoalBridgeParticipant(orchestrator, store, normalizer, enabledConfig());
    }

    @Test
    void tick_createsGoalNodeForNewRegisteredProposal() {
        var proposal = new DriveGoalProposal(
                DriveAxis.CURIOSITY, "Learn Spanish",
                "Study the Spanish language to conversational fluency",
                "Strong curiosity about languages", 0.85);
        orchestrator.registerGoals("agent1", "tenant1", List.of(proposal));

        bridge.tick(context("agent1", "tenant1"));

        var subgraph = findGoalSubgraph("tenant1");
        assertThat(subgraph).isNotNull();
        var nodes = store.nodesIn(subgraph.id(), "tenant1");
        assertThat(nodes).hasSize(1);

        var node = nodes.getFirst();
        assertThat(node.name()).isEqualTo("Learn Spanish");
        assertThat(node.properties().get("recognitionSource")).isEqualTo("drive-proposal");
        assertThat(node.properties().get("origin-drive")).isEqualTo("CURIOSITY");
        assertThat(node.properties().get("formation-reason")).isEqualTo("Strong curiosity about languages");
        assertThat(node.properties().get("status")).isEqualTo("active");
        assertThat(node.properties().get("description")).isEqualTo("Study the Spanish language to conversational fluency");
        assertThat(node.properties().get("horizon")).isEqualTo("long");
        assertThat(node.properties().get("goal-tier")).isEqualTo("STRATEGIC");
        assertThat(node.properties().get("drive-intensity")).isEqualTo("0.85");
        assertThat(node.properties().get("agent-id")).isEqualTo("agent1");
        assertThat(node.properties().get("need-tier")).isEqualTo("UNDERSTANDING");
        assertThat(node.properties().get("initial-emotion")).isEqualTo("HOPE");
        assertThat(node.properties().get("initial-emotion-intensity")).isEqualTo("0.85");
    }

    @Test
    void tick_idempotent_sameProposalsDoNotCreateDuplicates() {
        var proposal = new DriveGoalProposal(
                DriveAxis.CURIOSITY, "Learn Spanish",
                "Study Spanish", "curiosity", 0.8);
        orchestrator.registerGoals("agent1", "tenant1", List.of(proposal));

        bridge.tick(context("agent1", "tenant1"));
        bridge.tick(context("agent1", "tenant1"));

        var nodes = store.nodesIn(findGoalSubgraph("tenant1").id(), "tenant1");
        assertThat(nodes).hasSize(1);
    }

    @Test
    void tick_enrichesExistingNodeOnJaroWinklerMatch() {
        var sgId = store.createSubgraph(
                new SubgraphInput(SubgraphTypes.GOAL, SubgraphTypes.GOAL, null),
                "tenant1");
        store.addNode(NodeInput.of("Improve coding skills", sgId)
                .withProperties(Map.of("status", "active", "recognitionSource", "recognized")), "tenant1");

        var proposal = new DriveGoalProposal(
                DriveAxis.CURIOSITY, "Improve coding skill",
                "Get better at coding", "curiosity about programming", 0.8);
        orchestrator.registerGoals("agent1", "tenant1", List.of(proposal));

        bridge.tick(context("agent1", "tenant1"));

        var nodes = store.nodesIn(sgId, "tenant1");
        assertThat(nodes).hasSize(1);
        var node = nodes.getFirst();
        assertThat(node.name()).isEqualTo("Improve coding skills");
        assertThat(node.properties().get("recognitionSource")).isEqualTo("recognized");
        assertThat(node.properties().get("origin-drive")).isEqualTo("CURIOSITY");
        assertThat(node.properties().get("formation-reason")).isEqualTo("curiosity about programming");
        assertThat(node.properties().get("need-tier")).isEqualTo("UNDERSTANDING");
    }

    @Test
    void tick_crossPathDedup_matchesAgainstDescriptionProperty() {
        var sgId = store.createSubgraph(
                new SubgraphInput(SubgraphTypes.GOAL, SubgraphTypes.GOAL, null),
                "tenant1");
        store.addNode(NodeInput.of("Explore AI and machine learning technologies", sgId)
                .withProperties(Map.of("description", "Learn AI", "status", "active")), "tenant1");

        var proposal = new DriveGoalProposal(
                DriveAxis.COMPETENCE, "Learn AI",
                "Study AI fundamentals", "competence drive", 0.9);
        orchestrator.registerGoals("agent1", "tenant1", List.of(proposal));

        bridge.tick(context("agent1", "tenant1"));

        var nodes = store.nodesIn(sgId, "tenant1");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().properties().get("origin-drive")).isEqualTo("COMPETENCE");
    }

    @Test
    void tick_syncsAbandonment_marksDormant() {
        var proposal = new DriveGoalProposal(
                DriveAxis.CURIOSITY, "Learn Spanish",
                "Study Spanish", "curiosity", 0.8);
        orchestrator.registerGoals("agent1", "tenant1", List.of(proposal));
        bridge.tick(context("agent1", "tenant1"));

        orchestrator.registerGoals("agent1", "tenant1", List.of());
        bridge.tick(context("agent1", "tenant1"));

        var nodes = store.nodesIn(findGoalSubgraph("tenant1").id(), "tenant1");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().properties().get("status")).isEqualTo("dormant");
        assertThat(nodes.getFirst().properties().get("abandonment-reason"))
                .isEqualTo("drive-intensity-below-threshold");
    }

    @Test
    void tick_skipsWhenGoalsDisabled() {
        TermNormalizer noopNorm = (term, domain) -> ExpandedTerm.passthrough(term);
        var disabledBridge = new DriveGoalBridgeParticipant(
                orchestrator, store, noopNorm, disabledConfig());
        var proposal = new DriveGoalProposal(
                DriveAxis.CURIOSITY, "Learn Spanish",
                "Study Spanish", "curiosity", 0.8);
        orchestrator.registerGoals("agent1", "tenant1", List.of(proposal));

        disabledBridge.tick(context("agent1", "tenant1"));

        assertThat(store.listSubgraphs("tenant1")).isEmpty();
    }

    @Test
    void tick_createsSubgraphIfNotExists() {
        assertThat(store.listSubgraphs("tenant1")).isEmpty();

        var proposal = new DriveGoalProposal(
                DriveAxis.CURIOSITY, "Learn Spanish",
                "Study Spanish", "curiosity", 0.8);
        orchestrator.registerGoals("agent1", "tenant1", List.of(proposal));

        bridge.tick(context("agent1", "tenant1"));

        assertThat(findGoalSubgraph("tenant1")).isNotNull();
    }

    @Test
    void tick_horizonMapping_primaryMapsMedium() {
        var proposal = new DriveGoalProposal(
                DriveAxis.CURIOSITY, "Urgent Goal",
                "An urgent goal", "high drive", 0.9, GoalPriority.PRIMARY, null);
        orchestrator.registerGoals("agent1", "tenant1", List.of(proposal));

        bridge.tick(context("agent1", "tenant1"));

        var node = store.nodesIn(findGoalSubgraph("tenant1").id(), "tenant1").getFirst();
        assertThat(node.properties().get("horizon")).isEqualTo("medium");
    }

    @Test
    void mapHorizon_nullDefaultsToLong() {
        assertThat(DriveGoalBridgeParticipant.mapHorizon(null)).isEqualTo("long");
        assertThat(DriveGoalBridgeParticipant.mapHorizon(GoalPriority.PRIMARY)).isEqualTo("medium");
        assertThat(DriveGoalBridgeParticipant.mapHorizon(GoalPriority.SECONDARY)).isEqualTo("long");
    }

    private CognitionTickContext context(String agentId, String tenantId) {
        return new CognitionTickContext(agentId, tenantId, null, (a, t) -> Set.of());
    }

    private MindMapSubgraph findGoalSubgraph(String tenantId) {
        return store.listSubgraphs(tenantId).stream()
                .filter(sg -> SubgraphTypes.GOAL.equals(sg.type()))
                .findFirst().orElse(null);
    }

    private static CognitionConfig enabledConfig() {
        return CognitionConfig.none().with("goals", true);
    }

    private static CognitionConfig disabledConfig() {
        return CognitionConfig.none();
    }
}
