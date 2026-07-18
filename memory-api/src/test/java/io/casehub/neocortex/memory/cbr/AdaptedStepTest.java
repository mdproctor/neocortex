package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AdaptedStepTest {

    @Test void validStep() {
        var step = new AdaptedStep("b1", "cap1", "w1", "SUCCESS", 0,
                Map.of("k", "v"), AdaptationAction.RETAINED, null);
        assertThat(step.bindingName()).isEqualTo("b1");
        assertThat(step.capabilityName()).isEqualTo("cap1");
        assertThat(step.workerName()).isEqualTo("w1");
        assertThat(step.stepOutcome()).isEqualTo("SUCCESS");
        assertThat(step.priority()).isZero();
        assertThat(step.parameters()).containsEntry("k", "v");
        assertThat(step.action()).isEqualTo(AdaptationAction.RETAINED);
        assertThat(step.reason()).isNull();
    }

    @Test void nullBindingNameRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new AdaptedStep(null, "cap", "w", "SUCCESS", 0,
                        Map.of(), AdaptationAction.RETAINED, null));
    }

    @Test
    void nullCapabilityNameAccepted() {
        var step = new AdaptedStep("b1", null, "w1", "SUCCESS", 0,
                                   Map.of("k", "v"), AdaptationAction.RETAINED, null);
        assertThat(step.capabilityName()).isNull();
    }

    @Test void negativePriorityRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AdaptedStep("b", "cap", "w", "SUCCESS", -1,
                        Map.of(), AdaptationAction.RETAINED, null));
    }

    @Test void nullActionRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new AdaptedStep("b", "cap", "w", "SUCCESS", 0,
                        Map.of(), null, null));
    }

    @Test void nullWorkerNameAllowed_removedStep() {
        var step = new AdaptedStep("b", "cap", null, "FAILURE", 0,
                Map.of(), AdaptationAction.REMOVED, "worker unavailable");
        assertThat(step.workerName()).isNull();
    }

    @Test void nullWorkerNameAllowed_addedStep() {
        var step = new AdaptedStep("b", "cap", null, null, 0,
                Map.of(), AdaptationAction.ADDED, "severity requires IRB");
        assertThat(step.workerName()).isNull();
        assertThat(step.stepOutcome()).isNull();
    }

    @Test void nullStepOutcomeAllowed_addedStep() {
        var step = new AdaptedStep("b", "cap", "w", null, 5,
                Map.of(), AdaptationAction.ADDED, "new step");
        assertThat(step.stepOutcome()).isNull();
    }

    @Test void nullParametersDefaultsToEmptyMap() {
        var step = new AdaptedStep("b", "cap", "w", "SUCCESS", 0,
                null, AdaptationAction.RETAINED, null);
        assertThat(step.parameters()).isEmpty();
    }

    @Test void parametersDefensivelyCopied() {
        var params = new java.util.HashMap<String, Object>();
        params.put("k", "v");
        var step = new AdaptedStep("b", "cap", "w", "SUCCESS", 0,
                params, AdaptationAction.RETAINED, null);
        params.put("k2", "v2");
        assertThat(step.parameters()).doesNotContainKey("k2");
    }

    @Test void allActionsAccessible() {
        assertThat(AdaptationAction.values()).containsExactly(
                AdaptationAction.RETAINED, AdaptationAction.SUBSTITUTED,
                AdaptationAction.BOOSTED, AdaptationAction.SUPPRESSED,
                AdaptationAction.ADDED, AdaptationAction.REMOVED);
    }
}
