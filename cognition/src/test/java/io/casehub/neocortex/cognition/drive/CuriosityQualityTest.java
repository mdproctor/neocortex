package io.casehub.neocortex.cognition.drive;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.mindmap.EdgeInput;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.mindmap.intelligence.CuriosityChannelComputer;
import io.casehub.neocortex.mindmap.intelligence.InformationGapDetector;
import io.casehub.neocortex.mindmap.intelligence.InformationGapScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.assertj.core.data.Offset;

/**
 * Quality verification for the information-gap curiosity model.
 * Verifies literature-grounded properties:
 * <ul>
 *   <li>Personality differentiation: same gaps, different felt experience</li>
 *   <li>Loewenstein non-linearity: partial knowledge peaks curiosity</li>
 *   <li>Berlyne focus: gap distribution → specific/diversive</li>
 *   <li>Search budget integration: gap-based curiosity flows through drive pipeline</li>
 * </ul>
 */
class CuriosityQualityTest {

    private InMemoryMindMapStore store;
    private InformationGapDetector detector;
    private CuriosityChannelComputer channelComputer;
    private static final String TENANT = "test-tenant";
    private static final Instant NOW = Instant.now();

    @BeforeEach
    void setUp() {
        store = new InMemoryMindMapStore();
        detector = new InformationGapDetector(store);
        channelComputer = new CuriosityChannelComputer();
    }

    private void addGapNode(String name, double confidence, int edgeCount) {
        String sgId = ensureSubgraph();
        String nodeId = store.addNode(NodeInput.of(name, sgId)
                .withConfidence(Confidence.inferred(confidence, NOW)), TENANT);
        String ctx = store.addNode(NodeInput.of(name + "-ctx", sgId)
                .withConfidence(Confidence.stated(0.9, NOW)), TENANT);
        for (int i = 0; i < edgeCount; i++) {
            String extra = store.addNode(NodeInput.of(name + "-link-" + i, sgId)
                    .withConfidence(Confidence.stated(0.85, NOW)), TENANT);
            store.addEdge(EdgeInput.of(nodeId, extra, "relates-to"), TENANT);
        }
    }

    private String ensureSubgraph() {
        var existing = store.listSubgraphs(TENANT);
        if (!existing.isEmpty()) return existing.getFirst().id();
        return store.createSubgraph(
                new SubgraphInput("concepts", SubgraphTypes.CONCEPT, null), TENANT);
    }

    @Test
    void personalityDifferentiation_sameGaps_differentHedonic() {
        addGapNode("quantum", 0.4, 3);
        var gaps = detector.detectGaps(TENANT, null);

        var iTypeChannel = channelComputer.compute(gaps, 0.1);
        var dTypeChannel = channelComputer.compute(gaps, 0.9);

        assertThat(iTypeChannel.hedonic()).isEqualTo(CuriosityChannel.HedonicType.I_TYPE);
        assertThat(dTypeChannel.hedonic()).isEqualTo(CuriosityChannel.HedonicType.D_TYPE);
        // Same gaps, same comprehension and focus
        assertThat(iTypeChannel.comprehension()).isEqualTo(dTypeChannel.comprehension());
        assertThat(iTypeChannel.focus()).isEqualTo(dTypeChannel.focus());
    }

    @Test
    void loewensteinNonLinearity_partialKnowledgePeaks() {
        // Three nodes at different confidence levels
        String sgId = store.createSubgraph(
                new SubgraphInput("test", SubgraphTypes.CONCEPT, null), TENANT);

        // Low confidence (0.05) with 1 edge
        String lowNode = store.addNode(NodeInput.of("almost-unknown", sgId)
                .withConfidence(Confidence.speculated(0.08, NOW)), TENANT);
        String lowCtx = store.addNode(NodeInput.of("low-ctx", sgId)
                .withConfidence(Confidence.stated(0.9, NOW)), TENANT);
        store.addEdge(EdgeInput.of(lowNode, lowCtx, "relates-to"), TENANT);

        // Moderate confidence (0.4) with 3 edges — sweet spot
        String midNode = store.addNode(NodeInput.of("partial-knowledge", sgId)
                .withConfidence(Confidence.inferred(0.4, NOW)), TENANT);
        for (int i = 0; i < 3; i++) {
            String ctx = store.addNode(NodeInput.of("mid-ctx-" + i, sgId)
                    .withConfidence(Confidence.stated(0.85, NOW)), TENANT);
            store.addEdge(EdgeInput.of(midNode, ctx, "relates-to"), TENANT);
        }

        var gaps = detector.detectGaps(TENANT, null);

        var midGap = gaps.stream().filter(g -> g.nodeName().equals("partial-knowledge")).findFirst();
        var lowGap = gaps.stream().filter(g -> g.nodeName().equals("almost-unknown")).findFirst();

        assertThat(midGap).isPresent();
        assertThat(lowGap).isPresent();
        assertThat(midGap.get().gapIntensity()).isGreaterThan(lowGap.get().gapIntensity());
    }

    @Test
    void berlyneFocus_singleGap_specific() {
        addGapNode("the-one-question", 0.4, 3);
        var gaps = detector.detectGaps(TENANT, null);
        var channel = channelComputer.compute(gaps, 0.5);

        assertThat(channel.focus()).isEqualTo(CuriosityChannel.FocusType.SPECIFIC);
    }

    @Test
    void berlyneFocus_manyEqualGaps_diversive() {
        for (int i = 0; i < 5; i++) {
            addGapNode("topic-" + i, 0.4, 2);
        }
        var gaps = detector.detectGaps(TENANT, null);
        var channel = channelComputer.compute(gaps, 0.5);

        assertThat(channel.focus()).isEqualTo(CuriosityChannel.FocusType.DIVERSIVE);
    }

    @Test
    void silvia_highConfidenceGaps_interestNotConfusion() {
        addGapNode("almost-grasped", 0.65, 5);
        var gaps = detector.detectGaps(TENANT, null);
        var channel = channelComputer.compute(gaps, 0.5);

        assertThat(channel.comprehension()).isGreaterThan(0.5);
    }

    @Test
    void searchBudgetIntegration_gapBasedCuriosityProducesIntensity() {
        addGapNode("investigation-target", 0.4, 3);

        var drive = new CuriosityDrive(detector, new CuriosityChannelComputer(), 0.5);
        var result = drive.evaluate("agent-1", TENANT);

        assertThat(result.axis()).isEqualTo(DriveAxis.CURIOSITY);
        assertThat(result.intensity()).isGreaterThan(0.0);
        assertThat(drive.lastGaps()).isNotEmpty();
        // DriveProfile can consume this intensity via DriveOrchestrator
    }

    @Test
    void goalDirectedCuriosity_boostsGoalAdjacentGaps() {
        String sgConcept = store.createSubgraph(
                new SubgraphInput("concepts", SubgraphTypes.CONCEPT, null), TENANT);
        String sgGoal = store.createSubgraph(
                new SubgraphInput("goals", SubgraphTypes.GOAL, null), TENANT);
        String goalNode = store.addNode(NodeInput.of("learn-rust", sgGoal)
                .withProperties(Map.of("status", "active")), TENANT);

        String ownershipId = store.addNode(NodeInput.of("rust-ownership", sgConcept)
                .withConfidence(Confidence.inferred(0.4, NOW)), TENANT);
        String ctx1 = store.addNode(NodeInput.of("ownership-ctx", sgConcept)
                .withConfidence(Confidence.stated(0.9, NOW)), TENANT);
        store.addEdge(EdgeInput.of(ownershipId, ctx1, "relates-to"), TENANT);
        store.addEdge(EdgeInput.of(goalNode, ownershipId, "requires"), TENANT);

        String unrelatedId = store.addNode(NodeInput.of("unrelated-topic", sgConcept)
                .withConfidence(Confidence.inferred(0.4, NOW)), TENANT);
        String ctx2 = store.addNode(NodeInput.of("unrelated-ctx", sgConcept)
                .withConfidence(Confidence.stated(0.9, NOW)), TENANT);
        store.addEdge(EdgeInput.of(unrelatedId, ctx2, "relates-to"), TENANT);

        var drive = new CuriosityDrive(detector, channelComputer, 0.5,
                () -> Set.of(goalNode));
        var intensity = drive.evaluate("agent-1", TENANT);

        assertThat(intensity.intensity()).isGreaterThan(0.0);
        var gaps = drive.lastGaps();
        assertThat(gaps.getFirst().nodeName()).isEqualTo("rust-ownership");
        assertThat(gaps.getFirst().goalRelevance()).isEqualTo(1.0);
    }

    @Test
    void entropyFunction_peaksAtHalf_zeroAtExtremes() {
        double atZero = InformationGapDetector.computeEntropy(0.0);
        double atHalf = InformationGapDetector.computeEntropy(0.5);
        double atOne = InformationGapDetector.computeEntropy(1.0);
        double atQuarter = InformationGapDetector.computeEntropy(0.25);
        double atThreeQuarter = InformationGapDetector.computeEntropy(0.75);

        assertThat(atZero).isEqualTo(0.0);
        assertThat(atOne).isEqualTo(0.0);
        assertThat(atHalf).isCloseTo(1.0, Offset.offset(0.01));
        // Symmetric
        assertThat(atQuarter).isCloseTo(atThreeQuarter, Offset.offset(0.01));
        // Peak
        assertThat(atHalf).isGreaterThan(atQuarter);
    }
}
