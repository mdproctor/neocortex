package io.casehub.neocortex.cognitive.index;

import io.casehub.neocortex.cognitive.observability.DerivationTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DerivationTraceTest {

    private static final DispositionAxes DEFAULT_AXES =
            new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical");

    private static final List<WeightedTerm> BALANCED_PROFILE = List.of(
            new WeightedTerm("ni", 0.3), new WeightedTerm("te", 0.25),
            new WeightedTerm("fi", 0.25), new WeightedTerm("se", 0.2));

    @Test
    void journal_capturesPipelineStartAndComplete() {
        var facets = List.of(new PersonalityFacet(PersonalityFacet.RISK_TAKING, 0.8, "test"));
        var descriptor = new DescriptorView("traced-agent", DEFAULT_AXES, BALANCED_PROFILE,
                                            List.of(), null, facets);
        var trace = new DerivationTrace();
        CognitiveDerivationEngine.derive(descriptor, trace);

        assertThat(trace.entries().getFirst()).isInstanceOf(DerivationTrace.PipelineStarted.class);
        assertThat(trace.entries().getLast()).isInstanceOf(DerivationTrace.PipelineCompleted.class);
    }

    @Test
    void journal_capturesFullStageComputations() {
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.9, "test"),
                new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.8, "test"));
        var descriptor = new DescriptorView("traced-agent", DEFAULT_AXES, BALANCED_PROFILE,
                                            List.of("investigate"), null, facets);
        var trace = new DerivationTrace();
        CognitiveDerivationEngine.derive(descriptor, trace);

        var stages = trace.stages();
        assertThat(stages).isNotEmpty();

        var capacityStage = trace.stagesFor("deriveCapacity");
        assertThat(capacityStage).hasSize(1);
        var cap = capacityStage.getFirst();
        assertThat(cap.baseValues()).containsKey("workingMemorySpan");
        assertThat(cap.finalValues()).containsKey("workingMemorySpan");
        assertThat(cap.facetEffects()).isNotEmpty();
        assertThat(cap.facetEffects().stream().anyMatch(e -> "workingMemorySpan".equals(e.field()))).isTrue();
    }

    @Test
    void journal_capturesBaseAndFinalValues() {
        var facets = List.of(new PersonalityFacet(PersonalityFacet.WITHDRAWAL, 0.9, "test"));
        var descriptor = new DescriptorView("traced-agent", DEFAULT_AXES, BALANCED_PROFILE,
                                            List.of(), null, facets);
        var trace = new DerivationTrace();
        CognitiveDerivationEngine.derive(descriptor, trace);

        var    graphStage = trace.stagesFor("deriveGraphStructure").getFirst();
        double baseCB     = graphStage.baseValues().get("connectiveBias");
        double finalCB    = graphStage.finalValues().get("connectiveBias");
        assertThat(finalCB).isLessThan(baseCB);
    }

    @Test
    void journal_capturesDataFlows() {
        var facets = List.of(new PersonalityFacet(PersonalityFacet.WITHDRAWAL, 0.5, "test"));
        var descriptor = new DescriptorView("traced-agent", DEFAULT_AXES, BALANCED_PROFILE,
                                            List.of(), null, facets);
        var trace = new DerivationTrace();
        CognitiveDerivationEngine.derive(descriptor, trace);

        var flows = trace.flows();
        assertThat(flows.stream().anyMatch(f ->
                                                   "deriveGraphStructure".equals(f.sourceMethod()) &&
                                                   "deriveSearchBudgetDefaults".equals(f.targetMethod()) &&
                                                   "connectiveBias".equals(f.sourceField()))).isTrue();
    }

    @Test
    void journal_queryByFacet() {
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.9, "test"),
                new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.8, "test"));
        var descriptor = new DescriptorView("traced-agent", DEFAULT_AXES, BALANCED_PROFILE,
                                            List.of(), null, facets);
        var trace = new DerivationTrace();
        CognitiveDerivationEngine.derive(descriptor, trace);

        var impulsivityEffects = trace.effectsOf(PersonalityFacet.IMPULSIVITY);
        assertThat(impulsivityEffects).isNotEmpty();
        assertThat(impulsivityEffects.stream().anyMatch(e -> e.delta() != 0.0)).isTrue();
    }

    @Test
    void journal_filterByStage() {
        var facets = List.of(new PersonalityFacet(PersonalityFacet.RISK_TAKING, 0.8, "test"));
        var descriptor = new DescriptorView("traced-agent", DEFAULT_AXES, BALANCED_PROFILE,
                                            List.of(), null, facets);
        var filtered = new DerivationTrace(method -> "deriveMoodBaseline".equals(method));
        CognitiveDerivationEngine.derive(descriptor, filtered);

        assertThat(filtered.stages()).hasSize(1);
        assertThat(filtered.stages().getFirst().method()).isEqualTo("deriveMoodBaseline");
    }

    @Test
    void journal_noFacetsMeansNoEffects() {
        var descriptor = DescriptorView.of("no-facets", DEFAULT_AXES, BALANCED_PROFILE, List.of());
        var trace      = new DerivationTrace();
        CognitiveDerivationEngine.derive(descriptor, trace);

        assertThat(trace.allFacetEffects()).isEmpty();
        assertThat(trace.stages()).isNotEmpty();
    }
}
