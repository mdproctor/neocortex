package io.casehub.neocortex.cognition.situational;

import io.casehub.neocortex.cognition.prompt.BlockTag;
import io.casehub.neocortex.cognition.prompt.CognitionRenderContext;
import io.casehub.neocortex.cognition.trace.PipelineTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import java.util.Set;

class ShadowArchetypePromptSectionTest {

    @Test
    void rendersAffectiveModeWithFacets() {
        var snapshot = new PipelineTrace.SituationalPersonalitySnapshot(
                Map.of("STRESS", 0.72, "FLOW", 0.1),
                "STRESS", null, Map.of("anxiousness", 0.15, "emotional-lability", 0.12), 2);

        var section = new ShadowArchetypePromptSection((a, t) -> Optional.of(snapshot));
        var ctx     = new CognitionRenderContext("agent1", "t1", null, List.of());

        var text = section.render(ctx);

        assertThat(text).isNotNull();
        assertThat(text).contains("stress");
        assertThat(text).contains("72%");
        assertThat(text).contains("Anxiousness");
        assertThat(text).contains("Emotional lability");
    }

    @Test
    void rendersSocialModeWhenActive() {
        var snapshot = new PipelineTrace.SituationalPersonalitySnapshot(
                Map.of("SOCIAL_COMPETITIVE", 0.65),
                null, "SOCIAL_COMPETITIVE", Map.of(), 0);

        var section = new ShadowArchetypePromptSection((a, t) -> Optional.of(snapshot));
        var ctx     = new CognitionRenderContext("agent1", "t1", null, List.of());

        var text = section.render(ctx);

        assertThat(text).isNotNull();
        assertThat(text).contains("social-competitive");
    }

    @Test
    void returnsNullWhenNoSnapshotAvailable() {
        var section = new ShadowArchetypePromptSection((a, t) -> Optional.empty());
        var ctx     = new CognitionRenderContext("agent1", "t1", null, List.of());

        assertThat(section.render(ctx)).isNull();
    }

    @Test
    void returnsNullWhenNoActiveWinner() {
        var snapshot = new PipelineTrace.SituationalPersonalitySnapshot(
                Map.of("STRESS", 0.1), null, null, Map.of(), 0);

        var section = new ShadowArchetypePromptSection((a, t) -> Optional.of(snapshot));
        var ctx     = new CognitionRenderContext("agent1", "t1", null, List.of());

        assertThat(section.render(ctx)).isNull();
    }

    @Test
    void blockTagIsSituational() {
        var section = new ShadowArchetypePromptSection((a, t) -> Optional.empty());
        assertThat(section.blockTag()).isEqualTo(BlockTag.SITUATIONAL);
    }

    @Test
    void rendersWithArchetypeAndStoreContext() {
        var snapshot = new PipelineTrace.SituationalPersonalitySnapshot(
                Map.of("STRESS", 0.72),
                "STRESS", null,
                Map.of("anxiousness", 0.15, "emotional-lability", 0.12,
                       "hostility", 0.08, "grandiosity", 0.03),
                3);
        var store = new InMemoryMindMapStore();
        var personSgId = store.createSubgraph(
                new SubgraphInput(
                        "Person", SubgraphTypes.PERSON, null), "t1");
        store.addNode(NodeInput.of("agent-1", personSgId)
                                                            .withProperties(Map.of("archetype-1-family", "creator")), "t1");

        Set<String> modeProfileFacets = Set.of("anxiousness", "emotional-lability");
        var                   config            = ShadowFrameConfig.defaults();

        var section = new ShadowArchetypePromptSection(
                (a, t) -> Optional.of(snapshot),
                store, config,
                (agentId, tenantId, mode) -> modeProfileFacets);
        var ctx    = new CognitionRenderContext("agent-1", "t1", null, List.of());
        var result = section.render(ctx);

        assertThat(result).isNotNull();
        assertThat(result).contains("stress");
        assertThat(result).contains("72%");
        assertThat(result).contains("Anxiousness");
        assertThat(result).contains("characteristic of this state");
        assertThat(result).contains("Creator");
        assertThat(result).doesNotContain("Grandiosity");
    }

    @Test
    void modeCharacteristicFacetsAnnotated() {
        var snapshot = new PipelineTrace.SituationalPersonalitySnapshot(
                Map.of("STRESS", 0.72),
                "STRESS", null,
                Map.of("anxiousness", 0.15, "hostility", 0.08),
                2);

        Set<String> modeProfileFacets = Set.of("anxiousness");
        var                   config            = ShadowFrameConfig.defaults();

        var section = new ShadowArchetypePromptSection(
                (a, t) -> Optional.of(snapshot),
                null, config,
                (agentId, tenantId, mode) -> modeProfileFacets);
        var ctx    = new CognitionRenderContext("agent1", "t1", null, List.of());
        var result = section.render(ctx);

        assertThat(result).contains("characteristic of this state");
    }

    @Test
    void rendersClosingWhenStorePresentsButScoredEmptyAndNoArchetype() {
        var snapshot = new PipelineTrace.SituationalPersonalitySnapshot(
                Map.of("STRESS", 0.72),
                "STRESS", null,
                Map.of("anxiousness", 0.01),
                1);
        var store = new InMemoryMindMapStore();

        var section = new ShadowArchetypePromptSection(
                (a, t) -> Optional.of(snapshot),
                store, ShadowFrameConfig.defaults(), null);
        var ctx    = new CognitionRenderContext("agent1", "t1", null, List.of());
        var result = section.render(ctx);

        assertThat(result).isNotNull();
        assertThat(result).contains("stress");
        assertThat(result).contains("current reality");
    }

}
