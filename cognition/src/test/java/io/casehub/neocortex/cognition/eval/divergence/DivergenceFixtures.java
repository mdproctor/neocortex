package io.casehub.neocortex.cognition.eval.divergence;

import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.CognitiveDerivationEngine;
import io.casehub.neocortex.cognitive.index.DescriptorView;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognitive.index.PersonalityFacet;
import io.casehub.neocortex.cognitive.index.WeightedTerm;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;

import java.util.List;
import io.casehub.neocortex.caps.CapsAttractor;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import java.util.Map;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;

public final class DivergenceFixtures {

    public static final String TENANT = "test";

    public static final DispositionAxes OPEN_AXES =
            new DispositionAxes("cooperative", "moderate", "bold", "high", "cooperative");

    public static final List<PersonalityFacet> OPEN_FACETS = List.of(
            new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.2, "test"),
            new PersonalityFacet(PersonalityFacet.EMOTIONAL_LABILITY, 0.2, "test"),
            new PersonalityFacet(PersonalityFacet.WITHDRAWAL, 0.1, "test"),
            new PersonalityFacet(PersonalityFacet.INTIMACY_AVOIDANCE, 0.1, "test"),
            new PersonalityFacet(PersonalityFacet.RISK_TAKING, 0.8, "test"),
            new PersonalityFacet(PersonalityFacet.SUSPICIOUSNESS, 0.2, "test"),
            new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.1, "test"),
            new PersonalityFacet(PersonalityFacet.HOSTILITY, 0.1, "test"),
            new PersonalityFacet(PersonalityFacet.MANIPULATIVENESS, 0.1, "test"),
            new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.3, "test"));

    public static final DispositionAxes CAUTIOUS_AXES =
            new DispositionAxes("independent", "strict", "conservative", "low", "avoidant");

    public static final List<PersonalityFacet> CAUTIOUS_FACETS = List.of(
            new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.85, "test"),
            new PersonalityFacet(PersonalityFacet.EMOTIONAL_LABILITY, 0.8, "test"),
            new PersonalityFacet(PersonalityFacet.WITHDRAWAL, 0.8, "test"),
            new PersonalityFacet(PersonalityFacet.INTIMACY_AVOIDANCE, 0.7, "test"),
            new PersonalityFacet(PersonalityFacet.RISK_TAKING, 0.2, "test"),
            new PersonalityFacet(PersonalityFacet.SUSPICIOUSNESS, 0.7, "test"),
            new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.4, "test"),
            new PersonalityFacet(PersonalityFacet.HOSTILITY, 0.5, "test"),
            new PersonalityFacet(PersonalityFacet.MANIPULATIVENESS, 0.3, "test"),
            new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.5, "test"));

    private DivergenceFixtures() {}

    public static DescriptorView openView() {
        return new DescriptorView("open", OPEN_AXES,
                List.of(new WeightedTerm("ne", 0.5), new WeightedTerm("se", 0.5)),
                List.of(), null, OPEN_FACETS);
    }

    public static DescriptorView cautiousView() {
        return new DescriptorView("cautious", CAUTIOUS_AXES,
                List.of(new WeightedTerm("si", 0.5), new WeightedTerm("te", 0.5)),
                List.of(), null, CAUTIOUS_FACETS);
    }

    public static CognitiveDefaults openDefaults() {
        return CognitiveDerivationEngine.derive(openView());
    }

    public static CognitiveDefaults cautiousDefaults() {
        return CognitiveDerivationEngine.derive(cautiousView());
    }

    public static CognitionTestHarness.Builder pairedBuilder() {
        return CognitionTestHarness.builder()
                .agentFromDefaults("open", openDefaults())
                .agentFromDefaults("cautious", cautiousDefaults());
    }

    public static CognitionTestHarness.Builder fullBuilder(InMemoryMindMapStore store) {
        return CognitionTestHarness.builder()
                .withMindMapStore(store)
                .agentFromDefaults("open", openDefaults())
                .agentFromDefaults("cautious", cautiousDefaults())
                .withBehavioralSynthesis(true);
    }

    public static InMemoryMindMapStore seededCognitiveStore() {
        var store = new InMemoryMindMapStore();

        var cogSgId = store.createSubgraph(
                new SubgraphInput("Cognitive",
                                                               SubgraphTypes.COGNITIVE, null), TENANT);

        for (String agentId : List.of("open", "cautious")) {
            for (int i = 0; i < 5; i++) {
                store.addNode(
                        NodeInput.of("experience-" + agentId + "-" + i, cogSgId)
                                                              .withProperties(Map.of(
                                                                      "source-memory-id", "mem-" + agentId + "-" + i,
                                                                      "agent-id", agentId,
                                                                      "event-type", "observation")),
                        TENANT);
            }
        }

        store.addNode(NodeInput.of("drive-adventure-open", cogSgId)
                                                            .withProperties(Map.of(
                                                                    "cognitiveKind", "drive-intensity", "agent-id", "open",
                                                                    "drive-type", "adventure", "intensity", "0.9")), TENANT);
        store.addNode(NodeInput.of("drive-curiosity-open", cogSgId)
                                                            .withProperties(Map.of(
                                                                    "cognitiveKind", "drive-intensity", "agent-id", "open",
                                                                    "drive-type", "curiosity", "intensity", "0.8")), TENANT);

        store.addNode(NodeInput.of("drive-suspicion-cautious", cogSgId)
                                                            .withProperties(Map.of(
                                                                    "cognitiveKind", "drive-intensity", "agent-id", "cautious",
                                                                    "drive-type", "suspicion", "intensity", "0.9")), TENANT);
        store.addNode(NodeInput.of("drive-self-preservation-cautious", cogSgId)
                                                            .withProperties(Map.of(
                                                                    "cognitiveKind", "drive-intensity", "agent-id", "cautious",
                                                                    "drive-type", "self-preservation", "intensity", "0.8")), TENANT);

        return store;
    }

    public static void assertAttractorsDiverge(CognitionTestHarness harness) {
        var openResult     = harness.capsSettlingResult("open", TENANT);
        var cautiousResult = harness.capsSettlingResult("cautious", TENANT);

        Assertions.assertThat(openResult).as("open agent should have settling result").isPresent();
        Assertions.assertThat(cautiousResult).as("cautious agent should have settling result").isPresent();

        var openAttractors     = openResult.get().attractors();
        var cautiousAttractors = cautiousResult.get().attractors();

        Assertions.assertThat(openAttractors).as("open agent should have attractors").isNotEmpty();
        Assertions.assertThat(cautiousAttractors).as("cautious agent should have attractors").isNotEmpty();

        var openByCategory = openAttractors.stream()
                                           .collect(Collectors.toMap(
                                                   CapsAttractor::category,
                                                   CapsAttractor::strength,
                                                   Math::max));
        var cautiousByCategory = cautiousAttractors.stream()
                                                   .collect(Collectors.toMap(
                                                           CapsAttractor::category,
                                                           CapsAttractor::strength,
                                                           Math::max));

        boolean attractorsDiffer = !openByCategory.keySet().equals(cautiousByCategory.keySet());
        if (!attractorsDiffer) {
            for (var category : openByCategory.keySet()) {
                if (Math.abs(openByCategory.getOrDefault(category, 0.0)
                             - cautiousByCategory.getOrDefault(category, 0.0)) > 0.001) {
                    attractorsDiffer = true;
                    break;
                }
            }
        }

        Assertions.assertThat(attractorsDiffer)
                                       .as("behavioral attractor strengths should differ — open=%s vs cautious=%s",
                                           openByCategory, cautiousByCategory)
                                       .isTrue();
    }


}
