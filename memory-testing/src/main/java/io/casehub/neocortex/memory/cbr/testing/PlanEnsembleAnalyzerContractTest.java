package io.casehub.neocortex.memory.cbr.testing;

import io.casehub.neocortex.memory.cbr.AdaptationAction;
import io.casehub.neocortex.memory.cbr.AdaptedPlan;
import io.casehub.neocortex.memory.cbr.AdaptedStep;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanEnsembleAnalyzer;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

public abstract class PlanEnsembleAnalyzerContractTest {

    protected abstract PlanEnsembleAnalyzer analyzer();

    private static ScoredCbrCase<PlanCbrCase> scored(String caseId, double score, String... bindings) {
        var traces = new java.util.ArrayList<PlanTrace>();
        for (String b : bindings) {
            traces.add(new PlanTrace(b, "cap-" + b, "worker-" + b, "COMPLETED", 0, Map.of()));
        }
        var plan = new PlanCbrCase("problem", "solution", "COMPLETED", score,
                Map.of("f", FeatureValue.string("v")), traces);
        return new ScoredCbrCase<>(plan, caseId, score);
    }

    private static AdaptedPlan adapted(String... bindings) {
        var steps = new java.util.ArrayList<AdaptedStep>();
        for (String b : bindings) {
            steps.add(new AdaptedStep(b, "cap-" + b, "worker-" + b, "COMPLETED", 0,
                    Map.of(), AdaptationAction.RETAINED, null));
        }
        return new AdaptedPlan(steps);
    }

    @Test void single_plan_returns_that_plan() {
        var scored = scored("c1", 0.9, "step-a", "step-b");
        var adapted = adapted("step-a", "step-b");
        var result = analyzer().analyze("type", List.of(scored), List.of(adapted),
                Map.of("f", FeatureValue.string("v")));
        assertThat(result.synthesizedPlan().steps()).hasSize(2);
        assertThat(result.synthesizedPlan().steps().get(0).bindingName()).isEqualTo("step-a");
        assertThat(result.synthesizedPlan().steps().get(1).bindingName()).isEqualTo("step-b");
    }

    @Test void empty_plans_handled() {
        var result = analyzer().analyze("type", List.of(), List.of(),
                Map.of("f", FeatureValue.string("v")));
        assertThat(result.synthesizedPlan().steps()).isEmpty();
        assertThat(result.stepAnalysis()).isEmpty();
        assertThat(result.sourceCaseIds()).isEmpty();
        assertThat(result.inputPlanCount()).isEqualTo(0);
    }

    @Test void parallel_list_length_mismatch_rejected() {
        var scored = scored("c1", 0.9, "step-a");
        assertThatIllegalArgumentException().isThrownBy(() ->
                analyzer().analyze("type", List.of(scored), List.of(),
                        Map.of("f", FeatureValue.string("v"))));
    }

    @Test void null_caseType_rejected() {
        assertThatNullPointerException().isThrownBy(() ->
                analyzer().analyze(null, List.of(), List.of(), Map.of()));
    }

    @Test void null_scoredCases_rejected() {
        assertThatNullPointerException().isThrownBy(() ->
                analyzer().analyze("type", null, List.of(), Map.of()));
    }

    @Test void null_adaptedPlans_rejected() {
        assertThatNullPointerException().isThrownBy(() ->
                analyzer().analyze("type", List.of(), null, Map.of()));
    }

    @Test void null_features_rejected() {
        assertThatNullPointerException().isThrownBy(() ->
                analyzer().analyze("type", List.of(), List.of(), null));
    }

    @Test void source_case_ids_populated() {
        var scored = scored("c1", 0.9, "step-a");
        var adapted = adapted("step-a");
        var result = analyzer().analyze("type", List.of(scored), List.of(adapted),
                Map.of("f", FeatureValue.string("v")));
        assertThat(result.sourceCaseIds()).contains("c1");
    }

    @Test void ensemble_confidence_in_range() {
        var scored = scored("c1", 0.9, "step-a");
        var adapted = adapted("step-a");
        var result = analyzer().analyze("type", List.of(scored), List.of(adapted),
                Map.of("f", FeatureValue.string("v")));
        assertThat(result.ensembleConfidence()).isBetween(0.0, 1.0);
    }

    @Test void input_plan_count_in_valid_range() {
        var s1 = scored("c1", 0.9, "step-a");
        var s2 = scored("c2", 0.7, "step-a");
        var a1 = adapted("step-a");
        var a2 = adapted("step-a");
        var result = analyzer().analyze("type", List.of(s1, s2), List.of(a1, a2),
                Map.of("f", FeatureValue.string("v")));
        assertThat(result.inputPlanCount()).isBetween(1, 2);
    }
}
