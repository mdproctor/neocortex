package io.casehub.neocortex.memory.cbr.tracking;

import io.casehub.neocortex.memory.cbr.AdaptationAction;
import io.casehub.neocortex.memory.cbr.AdaptedPlan;
import io.casehub.neocortex.memory.cbr.AdaptedStep;
import io.casehub.neocortex.memory.cbr.CbrAdaptationRecorded;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanAdapter;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingPlanAdapterTest {

    private ScoredCbrCase<PlanCbrCase> scored() {
        var trace = new PlanTrace("b1", "cap1", "w1", "SUCCESS", 0, Map.of());
        var plan = new PlanCbrCase("problem", "solution", "WIN", 0.9,
                Map.of("f", FeatureValue.string("v")), List.of(trace));
        return new ScoredCbrCase<>(plan, "c1", 0.85);
    }

    private PlanAdapter noOpDelegate() {
        return (caseType, retrieved, features) -> new AdaptedPlan(
                retrieved.cbrCase().planTrace().stream()
                         .map(t -> new AdaptedStep(t.bindingName(), t.capabilityName(),
                                                   t.workerName(), t.stepOutcome(), t.priority(), t.parameters(),
                                                   AdaptationAction.RETAINED, null))
                         .toList());
    }

    @Test
    void firesEventAfterAdaptation() {
        var eventRef  = new AtomicReference<CbrAdaptationRecorded>();
        var decorator = new TrackingPlanAdapter(noOpDelegate(), eventRef::set);

        Map<String, FeatureValue> features = Map.of("f", FeatureValue.string("q"));
        decorator.adapt("typeA", scored(), features);

        assertThat(eventRef.get()).isNotNull();
        assertThat(eventRef.get().trace().traceId()).isNotBlank();
        assertThat(eventRef.get().trace().caseType()).isEqualTo("typeA");
        assertThat(eventRef.get().trace().steps()).hasSize(1);
        assertThat(eventRef.get().trace().timestamp()).isNotNull();
    }

    @Test
    void traceContainsCorrectFields() {
        var                       eventRef  = new AtomicReference<CbrAdaptationRecorded>();
        var                       decorator = new TrackingPlanAdapter(noOpDelegate(), eventRef::set);
        Map<String, FeatureValue> features  = Map.of("f", FeatureValue.string("q"));

        decorator.adapt("typeB", scored(), features);
        var trace = eventRef.get().trace();

        assertThat(trace.caseType()).isEqualTo("typeB");
        assertThat(trace.sourceCaseId()).isEqualTo("c1");
        assertThat(trace.sourceScore()).isEqualTo(0.85);
        assertThat(trace.currentFeatures()).containsKey("f");
        assertThat(trace.steps().getFirst().action()).isEqualTo(AdaptationAction.RETAINED);
    }

    @Test
    void trackingFailureDoesNotBreakAdaptation() {
        var decorator = new TrackingPlanAdapter(noOpDelegate(), e -> {
            throw new RuntimeException("event sink failure");
        });

        var result = decorator.adapt("typeA", scored(), Map.of());

        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().getFirst().bindingName()).isEqualTo("b1");
    }

    @Test
    void firesForNoOpAdapter() {
        var eventRef  = new AtomicReference<CbrAdaptationRecorded>();
        var decorator = new TrackingPlanAdapter(noOpDelegate(), eventRef::set);

        var emptyPlan = new PlanCbrCase("problem", "solution", null, null,
                                        Map.of(), List.of());
        var scored = new ScoredCbrCase<>(emptyPlan, "c2", 0.3);
        decorator.adapt("typeA", scored, Map.of());

        assertThat(eventRef.get()).isNotNull();
        assertThat(eventRef.get().trace().steps()).isEmpty();
        assertThat(eventRef.get().trace().sourceCaseId()).isEqualTo("c2");
    }
}
