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
}
