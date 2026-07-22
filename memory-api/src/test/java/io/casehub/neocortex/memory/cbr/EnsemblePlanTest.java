package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class EnsemblePlanTest {

    private final AdaptedStep step = new AdaptedStep("b", "cap", "w", "SUCCESS", 0,
            Map.of(), AdaptationAction.RETAINED, null);
    private final StepConsensus consensus = new StepConsensus("b", "cap", 1, 1,
            Map.of("w", 1), Map.of("SUCCESS", 1), Map.of(0, 1),
            List.of("c1"), StepAgreement.UNANIMOUS);

    @Test void validPlan() {
        var plan = new EnsemblePlan(
                new AdaptedPlan(List.of(step)),
                List.of(consensus),
                List.of("c1"),
                0.85, 3);
        assertThat(plan.synthesizedPlan().steps()).hasSize(1);
        assertThat(plan.stepAnalysis()).hasSize(1);
        assertThat(plan.sourceCaseIds()).containsExactly("c1");
        assertThat(plan.ensembleConfidence()).isEqualTo(0.85);
        assertThat(plan.inputPlanCount()).isEqualTo(3);
    }

    @Test void nullSynthesizedPlanRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new EnsemblePlan(null, List.of(), List.of(), 0.5, 1));
    }

    @Test void nullStepAnalysisRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new EnsemblePlan(new AdaptedPlan(List.of()), null, List.of(), 0.5, 1));
    }

    @Test void nullSourceCaseIdsRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new EnsemblePlan(new AdaptedPlan(List.of()), List.of(), null, 0.5, 1));
    }

    @Test void confidenceBelowZeroRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new EnsemblePlan(new AdaptedPlan(List.of()), List.of(), List.of(), -0.1, 1));
    }

    @Test void confidenceAboveOneRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new EnsemblePlan(new AdaptedPlan(List.of()), List.of(), List.of(), 1.1, 1));
    }

    @Test void confidenceBoundariesAccepted() {
        assertThat(new EnsemblePlan(new AdaptedPlan(List.of()), List.of(), List.of(), 0.0, 1)
                .ensembleConfidence()).isEqualTo(0.0);
        assertThat(new EnsemblePlan(new AdaptedPlan(List.of()), List.of(), List.of(), 1.0, 1)
                .ensembleConfidence()).isEqualTo(1.0);
    }

    @Test void inputPlanCountNegativeRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new EnsemblePlan(new AdaptedPlan(List.of()), List.of(), List.of(), 0.5, -1));
    }

    @Test void inputPlanCountZeroAllowed() {
        var plan = new EnsemblePlan(new AdaptedPlan(List.of()), List.of(), List.of(), 0.0, 0);
        assertThat(plan.inputPlanCount()).isEqualTo(0);
    }

    @Test void emptyInputInvariant_nonEmptyAnalysisWithZeroCountRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new EnsemblePlan(new AdaptedPlan(List.of()), List.of(consensus), List.of(), 0.0, 0));
    }

    @Test void stepAnalysisDefensivelyCopied() {
        var list = new ArrayList<>(List.of(consensus));
        var plan = new EnsemblePlan(new AdaptedPlan(List.of(step)), list, List.of("c1"), 0.5, 1);
        list.clear();
        assertThat(plan.stepAnalysis()).hasSize(1);
    }

    @Test void sourceCaseIdsDefensivelyCopied() {
        var list = new ArrayList<>(List.of("c1"));
        var plan = new EnsemblePlan(new AdaptedPlan(List.of(step)), List.of(consensus), list, 0.5, 1);
        list.add("c2");
        assertThat(plan.sourceCaseIds()).hasSize(1);
    }
}
