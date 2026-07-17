package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.cbr.AdaptationAction;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpPlanAdapterTest {

    private final NoOpPlanAdapter adapter = new NoOpPlanAdapter();

    @Test
    void retainsAllSteps() {
        var trace1 = new PlanTrace("b1", "cap1", "w1", "SUCCESS", 0, Map.of());
        var trace2 = new PlanTrace("b2", "cap2", "w2", "FAILURE", 1, Map.of("p", "v"));
        var plan = new PlanCbrCase("problem", "solution", "WIN", 0.9,
                                   Map.of("f", FeatureValue.string("v")), List.of(trace1, trace2));
        var scored = new ScoredCbrCase<>(plan, "c1", 0.85);

        var result = adapter.adapt("typeA", scored, Map.of("f", FeatureValue.string("q")));

        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps()).allSatisfy(s -> {
            assertThat(s.action()).isEqualTo(AdaptationAction.RETAINED);
            assertThat(s.reason()).isNull();
        });
    }

    @Test
    void preservesStepFields() {
        var trace = new PlanTrace("b1", "cap1", "w1", "SUCCESS", 3,
                                  Map.of("key", "val"));
        var plan = new PlanCbrCase("problem", "solution", null, null,
                                   Map.of(), List.of(trace));
        var scored = new ScoredCbrCase<>(plan, "c1", 0.5);

        var result = adapter.adapt("typeA", scored, Map.of());
        var step   = result.steps().getFirst();

        assertThat(step.bindingName()).isEqualTo("b1");
        assertThat(step.capabilityName()).isEqualTo("cap1");
        assertThat(step.workerName()).isEqualTo("w1");
        assertThat(step.stepOutcome()).isEqualTo("SUCCESS");
        assertThat(step.priority()).isEqualTo(3);
        assertThat(step.parameters()).containsEntry("key", "val");
    }

    @Test
    void emptyTrace() {
        var plan = new PlanCbrCase("problem", "solution", null, null,
                                   Map.of(), List.of());
        var scored = new ScoredCbrCase<>(plan, "c1", 0.5);

        var result = adapter.adapt("typeA", scored, Map.of());

        assertThat(result.steps()).isEmpty();
    }
}
