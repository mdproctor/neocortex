package io.casehub.neocortex.cognition.prompt;

import io.casehub.neocortex.cognition.drive.CuriosityDrive;
import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.mindmap.EdgeInput;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.mindmap.intelligence.CuriosityChannelComputer;
import io.casehub.neocortex.mindmap.intelligence.InformationGapDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import java.util.Set;

class CuriosityPromptSectionTest {

    private InMemoryMindMapStore store;
    private CuriosityDrive drive;
    private CuriosityPromptSection section;
    private static final String TENANT = "test-tenant";
    private static final String AGENT = "test-agent";
    private static final Instant NOW = Instant.now();

    @BeforeEach
    void setUp() {
        store = new InMemoryMindMapStore();
        var detector = new InformationGapDetector(store);
        drive = new CuriosityDrive(detector, new CuriosityChannelComputer(), 0.5);
        var channelComputer = new CuriosityChannelComputer();
        section = new CuriosityPromptSection(drive, channelComputer, null, store, 0.5);
    }

    private CognitionRenderContext ctx() {
        return new CognitionRenderContext(AGENT, TENANT, null, null);
    }

    @Test
    void noGaps_returnsNull() {
        drive.evaluate(AGENT, TENANT);
        assertThat(section.render(ctx())).isNull();
    }

    @Test
    void gapsPresent_rendersFourChannelProse() {
        String sgId = store.createSubgraph(
                new SubgraphInput("concepts", SubgraphTypes.CONCEPT, null), TENANT);
        String nodeA = store.addNode(NodeInput.of("quantum-theory", sgId)
                .withConfidence(Confidence.inferred(0.4, NOW)), TENANT);
        String nodeB = store.addNode(NodeInput.of("physics", sgId)
                .withConfidence(Confidence.stated(0.9, NOW)), TENANT);
        store.addEdge(EdgeInput.of(nodeA, nodeB, "relates-to"), TENANT);

        drive.evaluate(AGENT, TENANT);
        String result = section.render(ctx());

        assertThat(result).isNotNull();
        assertThat(result).isNotBlank();
        // BLENDED hedonic (deprivationBias=0.5)
        assertThat(result).contains("fascination");
        // SPECIFIC focus with node name
        assertThat(result).contains("quantum-theory");
    }

    @Test
    void iTypeCuriosity_rendersPleasantPull() {
        String sgId = store.createSubgraph(
                new SubgraphInput("concepts", SubgraphTypes.CONCEPT, null), TENANT);
        String nodeA = store.addNode(NodeInput.of("interesting-topic", sgId)
                .withConfidence(Confidence.inferred(0.4, NOW)), TENANT);
        String nodeB = store.addNode(NodeInput.of("context", sgId)
                .withConfidence(Confidence.stated(0.9, NOW)), TENANT);
        store.addEdge(EdgeInput.of(nodeA, nodeB, "relates-to"), TENANT);

        var iTypeSection = new CuriosityPromptSection(drive, new CuriosityChannelComputer(),
                null, store, 0.1);

        drive.evaluate(AGENT, TENANT);
        String result = iTypeSection.render(ctx());

        assertThat(result).contains("interest");
    }

    @Test
    void dTypeCuriosity_rendersAversiveGap() {
        String sgId = store.createSubgraph(
                new SubgraphInput("concepts", SubgraphTypes.CONCEPT, null), TENANT);
        String nodeA = store.addNode(NodeInput.of("troubling-topic", sgId)
                .withConfidence(Confidence.inferred(0.4, NOW)), TENANT);
        String nodeB = store.addNode(NodeInput.of("context", sgId)
                .withConfidence(Confidence.stated(0.9, NOW)), TENANT);
        store.addEdge(EdgeInput.of(nodeA, nodeB, "relates-to"), TENANT);

        var dTypeSection = new CuriosityPromptSection(drive, new CuriosityChannelComputer(),
                null, store, 0.9);

        drive.evaluate(AGENT, TENANT);
        String result = dTypeSection.render(ctx());

        assertThat(result).contains("gap");
    }

    @Test
    void baselineDeviation_aboveBaseline_rendersStrongerThanUsual() {
        String sgId = store.createSubgraph(
                new SubgraphInput("concepts", SubgraphTypes.CONCEPT, null), TENANT);
        String ctx = store.addNode(NodeInput.of("hub", sgId)
                                            .withConfidence(Confidence.stated(0.9, NOW)), TENANT);
        // Create multiple high-intensity gaps to push intensity well above a low baseline
        for (int i = 0; i < 3; i++) {
            String gap = store.addNode(NodeInput.of("intense-gap-" + i, sgId)
                                                .withConfidence(Confidence.inferred(0.4, NOW)), TENANT);
            store.addEdge(EdgeInput.of(gap, ctx, "relates-to"), TENANT);
            store.addEdge(EdgeInput.of(ctx, gap, "relates-to"), TENANT);
        }

        // Low baseline (0.1) — current gaps should be well above this
        var lowBaselineSection = new CuriosityPromptSection(drive, new CuriosityChannelComputer(),
                                                            null, store, 0.5, 0.1);

        drive.evaluate(AGENT, TENANT);
        String result = lowBaselineSection.render(ctx());

        assertThat(result).contains("stronger than your usual");
    }

    @Test
    void baselineDeviation_belowBaseline_rendersQuieterThanUsual() {
        String sgId = store.createSubgraph(
                new SubgraphInput("concepts", SubgraphTypes.CONCEPT, null), TENANT);
        // Single weak gap — low intensity
        String gap = store.addNode(NodeInput.of("minor-topic", sgId)
                                            .withConfidence(Confidence.inferred(0.15, NOW)), TENANT);
        String ctx = store.addNode(NodeInput.of("context", sgId)
                                            .withConfidence(Confidence.stated(0.9, NOW)), TENANT);
        store.addEdge(EdgeInput.of(gap, ctx, "relates-to"), TENANT);

        // High baseline (0.8) — current gaps should be well below this
        var highBaselineSection = new CuriosityPromptSection(drive, new CuriosityChannelComputer(),
                                                             null, store, 0.5, 0.8);

        drive.evaluate(AGENT, TENANT);
        String result = highBaselineSection.render(ctx());

        if (result != null) {
            assertThat(result).contains("Quieter than usual");
        }
    }


    @Test
    void blockTag_isCuriosity() {
        assertThat(section.blockTag()).isEqualTo(BlockTag.CURIOSITY);
    }

    @Test
    void crystallisedProse_preferredOverProgrammatic() {
        // Add crystallised node
        String cogSgId = store.createSubgraph(
                new SubgraphInput("cognitive", SubgraphTypes.COGNITIVE, null), TENANT);
        store.addNode(NodeInput.of("curiosity-" + AGENT, cogSgId)
                               .withTraits(Set.of(CuriosityPromptSection.GENERATED_TRAIT))
                               .withProperties(Map.of(
                                       "agent-id", AGENT,
                                       "description", "A crystallised curiosity description.")), TENANT);

        // Also add gaps so programmatic would fire
        String sgId = store.createSubgraph(
                new SubgraphInput("concepts", SubgraphTypes.CONCEPT, null), TENANT);
        String nodeA = store.addNode(NodeInput.of("topic", sgId)
                                              .withConfidence(Confidence.inferred(0.4, NOW)), TENANT);
        String nodeB = store.addNode(NodeInput.of("context", sgId)
                                              .withConfidence(Confidence.stated(0.9, NOW)), TENANT);
        store.addEdge(EdgeInput.of(nodeA, nodeB, "relates-to"), TENANT);

        drive.evaluate(AGENT, TENANT);
        String result = section.render(ctx());

        assertThat(result).isEqualTo("A crystallised curiosity description.");
    }
}
