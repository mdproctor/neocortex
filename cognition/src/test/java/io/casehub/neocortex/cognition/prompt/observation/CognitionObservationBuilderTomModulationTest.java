package io.casehub.neocortex.cognition.prompt.observation;

import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
import io.casehub.neocortex.cognitive.index.PersonalityFacet;
import io.casehub.neocortex.cognitive.index.PersonalityProfile;
import io.casehub.neocortex.cognition.core.CognitivePipelineBuilder;
import io.casehub.neocortex.cognition.mood.MoodConfig;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType;
import io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.neocortex.cognition.core.CognitionCore;

class CognitionObservationBuilderTomModulationTest {

    @Test
    void suspiciousnessShiftsPosteriorTowardAvoidantInterpretation() {
        var store = setupMindMapWithOtherModel();

        var suspiciousCore = buildCoreWithSuspiciousness(0.8);
        var suspiciousBuilder = new CognitionObservationBuilder(
                "observer", "t1", store, suspiciousCore, null, List.of());
        suspiciousBuilder.observeCharacterState("target", BehaviorType.APPROACH, ThreatLevel.MILD);

        var neutralBuilder = new CognitionObservationBuilder(
                "observer", "t1", store, null, null, List.of());
        neutralBuilder.observeCharacterState("target", BehaviorType.APPROACH, ThreatLevel.MILD);

        var suspiciousSections = suspiciousBuilder.renderTheoryOfMind(
                List.of("target"), Map.of("target", "Target"));
        var neutralSections = neutralBuilder.renderTheoryOfMind(
                List.of("target"), Map.of("target", "Target"));

        // Suspiciousness transfers APPROACH evidence toward AVOIDANT (Fenigstein & Vanable 1992),
        // raising distressed posterior above the 0.35 rendering threshold.
        // NEUTRAL modulation preserves APPROACH as benign — distress stays low, no rendering.
        assertThat(suspiciousSections).as("suspicious observer should sense distress")
                .isNotEmpty();
        assertThat(neutralSections).as("neutral observer should not sense distress from approach")
                .isEmpty();
    }

    @Test
    void zeroSuspiciousnessMatchesNeutralFallback() {
        var store = setupMindMapWithOtherModel();

        var zeroSuspiciousCore = buildCoreWithSuspiciousness(0.0);
        var withCore = new CognitionObservationBuilder(
                "observer", "t1", store, zeroSuspiciousCore, null, List.of());
        withCore.observeCharacterState("target", BehaviorType.APPROACH, ThreatLevel.MILD);

        var withoutCore = new CognitionObservationBuilder(
                "observer", "t1", store, null, null, List.of());
        withoutCore.observeCharacterState("target", BehaviorType.APPROACH, ThreatLevel.MILD);

        var coreSections = withCore.renderTheoryOfMind(
                List.of("target"), Map.of("target", "Target"));
        var fallbackSections = withoutCore.renderTheoryOfMind(
                List.of("target"), Map.of("target", "Target"));

        assertThat(coreSections).hasSameSizeAs(fallbackSections);
    }

    private InMemoryMindMapStore setupMindMapWithOtherModel() {
        var store = new InMemoryMindMapStore();
        var sgId = store.createSubgraph(
                new SubgraphInput("Cognitive", SubgraphTypes.COGNITIVE, null), "t1");
        store.addNode(NodeInput.of("other-model-observer-target", sgId)
                .withTraits(Set.of("OtherModelDerived"))
                .withProperties(Map.of(
                        "agent-id", "observer",
                        "target-id", "target",
                        "empathic-sensing", "You sense wariness in their posture.")),
                "t1");
        return store;
    }

    private CognitionCore buildCoreWithSuspiciousness(double suspiciousness) {
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.SUSPICIOUSNESS, suspiciousness, "test"),
                new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.0, "test"));
        var profile = new PersonalityProfile(null, null, null, null, facets);
        var defaults = CognitiveDefaults.empty("observer")
                .withPersonalityProfile(profile).withTenantId("t1");
        var effective = EffectiveCognitiveDefaults.forTesting(defaults);

        var mood = new MoodOrchestrator(MoodConfig.defaults());
        return CognitivePipelineBuilder.create(mood, null)
                .effectiveDefaults(effective)
                .build();
    }
}
