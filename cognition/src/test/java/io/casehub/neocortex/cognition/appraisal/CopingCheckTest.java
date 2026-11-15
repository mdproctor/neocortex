package io.casehub.neocortex.cognition.appraisal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CopingCheckTest {

    private final CopingCheck check = new CopingCheck();

    @Test
    void highAgency_narrativeWithNewKeywords() {
        var ctx = contextWithNarrative("I feel empowered and capable of managing this situation. I have alternatives and can navigate through it.");
        var result = check.evaluate(ctx);
        double controllability = result.dimensions().get(SecDimensions.CONTROLLABILITY);
        assertThat(controllability).isGreaterThan(0.6);
    }

    @Test
    void highHelplessness_narrativeWithNewKeywords() {
        var ctx = contextWithNarrative("I feel powerless and cornered, completely vulnerable and exposed to this futile situation.");
        var result = check.evaluate(ctx);
        double controllability = result.dimensions().get(SecDimensions.CONTROLLABILITY);
        assertThat(controllability).isLessThan(0.3);
    }

    @Test
    void mixedSignals_returnsIntermediateControllability() {
        var    ctx             = contextWithNarrative("I feel trapped and powerless but maybe I can still choose a path forward.");
        var    result          = check.evaluate(ctx);
        double controllability = result.dimensions().get(SecDimensions.CONTROLLABILITY);
        assertThat(controllability).isBetween(0.3, 0.7);
    }

    @Test
    void noKeywords_defaultsToNeutral() {
        var ctx = contextWithNarrative("The weather is pleasant today.");
        var result = check.evaluate(ctx);
        double controllability = result.dimensions().get(SecDimensions.CONTROLLABILITY);
        assertThat(controllability).isEqualTo(0.5);
    }

    @Test
    void originalKeywords_stillDetected() {
        var ctx = contextWithNarrative("I can choose and decide my option. This is possible.");
        var result = check.evaluate(ctx);
        double controllability = result.dimensions().get(SecDimensions.CONTROLLABILITY);
        assertThat(controllability).isGreaterThan(0.8);
    }

    private AppraisalContext contextWithNarrative(String narrative) {
        return new AppraisalContext("agent1",
                                    PerceivedSituation.passThrough(narrative),
                                    List.of(),
                                    io.casehub.neocortex.mindmap.AppraisalWeights.NEUTRAL,
                                    io.casehub.neocortex.cognitive.HabituationConfig.defaults(),
                                    HabituationState.empty(),
                                    null);
    }
}
