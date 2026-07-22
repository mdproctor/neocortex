package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.cbr.AdaptationAction;
import io.casehub.neocortex.memory.cbr.AdaptedPlan;
import io.casehub.neocortex.memory.cbr.AdaptedStep;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.StepAgreement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class NoOpPlanEnsembleAnalyzerTest {

    private final NoOpPlanEnsembleAnalyzer analyzer = new NoOpPlanEnsembleAnalyzer();

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

    @Test
    void noOp_returns_best_scoring_plan() {
        var s1 = scored("c1", 0.5, "a");
        var s2 = scored("c2", 0.9, "b", "c");
        var s3 = scored("c3", 0.7, "d");
        var a1 = adapted("a");
        var a2 = adapted("b", "c");
        var a3 = adapted("d");

        var result = analyzer.analyze("type",
                                      List.of(s1, s2, s3), List.of(a1, a2, a3),
                                      Map.of("f", FeatureValue.string("v")));

        assertThat(result.synthesizedPlan().steps()).hasSize(2);
        assertThat(result.synthesizedPlan().steps().get(0).bindingName()).isEqualTo("b");
        assertThat(result.synthesizedPlan().steps().get(1).bindingName()).isEqualTo("c");
        assertThat(result.sourceCaseIds()).containsExactly("c2");
    }

    @Test
    void noOp_step_analysis_reflects_single_plan() {
        var scored  = scored("c1", 0.8, "step-a", "step-b");
        var adapted = adapted("step-a", "step-b");

        var result = analyzer.analyze("type", List.of(scored), List.of(adapted),
                                      Map.of("f", FeatureValue.string("v")));

        assertThat(result.stepAnalysis()).hasSize(2);
        result.stepAnalysis().forEach(sc -> {
            assertThat(sc.occurrenceCount()).isEqualTo(1);
            assertThat(sc.totalPlans()).isEqualTo(1);
            assertThat(sc.agreement()).isEqualTo(StepAgreement.UNANIMOUS);
        });
    }

    @Test
    void noOp_empty_input() {
        var result = analyzer.analyze("type", List.of(), List.of(),
                                      Map.of("f", FeatureValue.string("v")));

        assertThat(result.synthesizedPlan().steps()).isEmpty();
        assertThat(result.stepAnalysis()).isEmpty();
        assertThat(result.sourceCaseIds()).isEmpty();
        assertThat(result.inputPlanCount()).isEqualTo(0);
        assertThat(result.ensembleConfidence()).isEqualTo(0.0);
    }

    @Test
    void noOp_preserves_step_fields() {
        var step = new AdaptedStep("bind", "cap", "worker", "SUCCESS", 3,
                                   Map.of("k", "v"), AdaptationAction.BOOSTED, "boosted");
        var adapted = new AdaptedPlan(List.of(step));
        var plan = new PlanCbrCase("problem", "solution", "COMPLETED", 0.8,
                                   Map.of(), List.of(new PlanTrace("bind", "cap", "worker", "SUCCESS", 3, Map.of("k", "v"))));
        var scored = new ScoredCbrCase<>(plan, "c1", 0.8);

        var result = analyzer.analyze("type", List.of(scored), List.of(adapted), Map.of());

        var synth = result.synthesizedPlan().steps().getFirst();
        assertThat(synth.bindingName()).isEqualTo("bind");
        assertThat(synth.capabilityName()).isEqualTo("cap");
        assertThat(synth.workerName()).isEqualTo("worker");
        assertThat(synth.stepOutcome()).isEqualTo("SUCCESS");
        assertThat(synth.priority()).isEqualTo(3);
        assertThat(synth.parameters()).containsEntry("k", "v");
    }

    @Test
    void noOp_multi_plan_reports_inputPlanCount_1() {
        var s1 = scored("c1", 0.9, "a");
        var s2 = scored("c2", 0.7, "b");
        var s3 = scored("c3", 0.5, "c");

        var result = analyzer.analyze("type",
                                      List.of(s1, s2, s3), List.of(adapted("a"), adapted("b"), adapted("c")),
                                      Map.of());

        assertThat(result.inputPlanCount()).isEqualTo(1);
    }

    @Test
    void noOp_negative_score_clamped_to_zero() {
        var plan = new PlanCbrCase("problem", "solution", "COMPLETED", null,
                                   Map.of(), List.of(new PlanTrace("a", "cap-a", "worker-a", "COMPLETED", 0, Map.of())));
        var scored  = new ScoredCbrCase<>(plan, "c1", -0.5);
        var adapted = adapted("a");

        var result = analyzer.analyze("type", List.of(scored), List.of(adapted), Map.of());

        assertThat(result.ensembleConfidence()).isEqualTo(0.0);
    }

    @Test
    void noOp_priority_distribution_populated() {
        var step = new AdaptedStep("b", "cap", "w", "SUCCESS", 5,
                                   Map.of(), AdaptationAction.RETAINED, null);
        var adapted = new AdaptedPlan(List.of(step));
        var plan = new PlanCbrCase("problem", "solution", null, null,
                                   Map.of(), List.of(new PlanTrace("b", "cap", "w", "SUCCESS", 5, Map.of())));
        var scored = new ScoredCbrCase<>(plan, "c1", 0.8);

        var result = analyzer.analyze("type", List.of(scored), List.of(adapted), Map.of());

        assertThat(result.stepAnalysis().getFirst().priorityDistribution())
                .containsEntry(5, 1);
    }

    @Test
    void noOp_null_caseType_rejected() {
        assertThatNullPointerException().isThrownBy(() ->
                                                            analyzer.analyze(null, List.of(), List.of(), Map.of()));
    }

    @Test
    void noOp_parallel_list_mismatch_rejected() {
        var scored = scored("c1", 0.9, "a");
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                analyzer.analyze("type", List.of(scored), List.of(), Map.of()));
    }

    @Test
    void noOp_confidence_in_range() {
        var scored  = scored("c1", 0.9, "a");
        var adapted = adapted("a");
        var result  = analyzer.analyze("type", List.of(scored), List.of(adapted), Map.of());
        assertThat(result.ensembleConfidence()).isBetween(0.0, 1.0);
    }
}
