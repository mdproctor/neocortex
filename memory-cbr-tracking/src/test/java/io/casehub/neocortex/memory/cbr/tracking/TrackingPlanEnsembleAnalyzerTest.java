package io.casehub.neocortex.memory.cbr.tracking;

import io.casehub.neocortex.memory.cbr.AdaptationAction;
import io.casehub.neocortex.memory.cbr.AdaptedPlan;
import io.casehub.neocortex.memory.cbr.AdaptedStep;
import io.casehub.neocortex.memory.cbr.CbrEnsembleRecorded;
import io.casehub.neocortex.memory.cbr.EnsemblePlan;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanEnsembleAnalyzer;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.StepAgreement;
import io.casehub.neocortex.memory.cbr.StepConsensus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingPlanEnsembleAnalyzerTest {

    private ScoredCbrCase<PlanCbrCase> scored() {
        var trace = new PlanTrace("b1", "cap1", "w1", "SUCCESS", 0, Map.of());
        var plan = new PlanCbrCase("problem", "solution", "WIN", 0.9,
                Map.of("f", FeatureValue.string("v")), List.of(trace));
        return new ScoredCbrCase<>(plan, "c1", 0.85);
    }

    private AdaptedPlan adapted() {
        return new AdaptedPlan(List.of(
                new AdaptedStep("b1", "cap1", "w1", "SUCCESS", 0,
                        Map.of(), AdaptationAction.RETAINED, null)));
    }

    private PlanEnsembleAnalyzer noOpDelegate() {
        return (caseType, scoredCases, adaptedPlans, features) -> {
            if (adaptedPlans.isEmpty()) {
                return new EnsemblePlan(new AdaptedPlan(List.of()), List.of(), List.of(), 0.0, 0);
            }
            var best = adaptedPlans.getFirst();
            var caseId = scoredCases.getFirst().caseId();
            var analysis = best.steps().stream()
                    .map(s -> new StepConsensus(s.bindingName(), s.capabilityName(),
                            1, 1, Map.of(s.workerName(), 1), Map.of(s.stepOutcome(), 1),
                            Map.of(s.priority(), 1), List.of(caseId), StepAgreement.UNANIMOUS))
                    .toList();
            return new EnsemblePlan(best, analysis, List.of(caseId),
                    Math.max(0.0, scoredCases.getFirst().score()), 1);
        };
    }

    @Test
    void firesEventAfterAnalysis() {
        var eventRef = new AtomicReference<CbrEnsembleRecorded>();
        var decorator = new TrackingPlanEnsembleAnalyzer(noOpDelegate(), eventRef::set);

        Map<String, FeatureValue> features = Map.of("f", FeatureValue.string("q"));
        decorator.analyze("typeA", List.of(scored()), List.of(adapted()), features);

        assertThat(eventRef.get()).isNotNull();
        assertThat(eventRef.get().trace().traceId()).isNotBlank();
        assertThat(eventRef.get().trace().caseType()).isEqualTo("typeA");
        assertThat(eventRef.get().trace().stepAnalysis()).hasSize(1);
        assertThat(eventRef.get().trace().synthesizedSteps()).hasSize(1);
        assertThat(eventRef.get().trace().timestamp()).isNotNull();
    }

    @Test
    void traceContainsCorrectFields() {
        var eventRef = new AtomicReference<CbrEnsembleRecorded>();
        var decorator = new TrackingPlanEnsembleAnalyzer(noOpDelegate(), eventRef::set);
        Map<String, FeatureValue> features = Map.of("f", FeatureValue.string("q"));

        decorator.analyze("typeB", List.of(scored()), List.of(adapted()), features);
        var trace = eventRef.get().trace();

        assertThat(trace.caseType()).isEqualTo("typeB");
        assertThat(trace.sourceCaseIds()).containsExactly("c1");
        assertThat(trace.inputPlanCount()).isEqualTo(1);
        assertThat(trace.ensembleConfidence()).isEqualTo(0.85);
        assertThat(trace.currentFeatures()).containsKey("f");
        assertThat(trace.retrievalTraceId()).isNull();
    }

    @Test
    void trackingFailureDoesNotBreakAnalysis() {
        var decorator = new TrackingPlanEnsembleAnalyzer(noOpDelegate(), e -> {
            throw new RuntimeException("event sink failure");
        });

        var result = decorator.analyze("typeA", List.of(scored()), List.of(adapted()), Map.of());

        assertThat(result.synthesizedPlan().steps()).hasSize(1);
        assertThat(result.synthesizedPlan().steps().getFirst().bindingName()).isEqualTo("b1");
    }

    @Test
    void firesForNoOpAnalyzer() {
        var eventRef = new AtomicReference<CbrEnsembleRecorded>();
        var decorator = new TrackingPlanEnsembleAnalyzer(noOpDelegate(), eventRef::set);

        decorator.analyze("typeA", List.of(), List.of(), Map.of());

        assertThat(eventRef.get()).isNotNull();
        assertThat(eventRef.get().trace().stepAnalysis()).isEmpty();
        assertThat(eventRef.get().trace().synthesizedSteps()).isEmpty();
        assertThat(eventRef.get().trace().inputPlanCount()).isEqualTo(0);
    }
}
