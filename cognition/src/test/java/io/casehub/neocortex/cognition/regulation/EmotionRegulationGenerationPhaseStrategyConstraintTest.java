package io.casehub.neocortex.cognition.regulation;

import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.PadProjection;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognitive.index.RegulationDefaults;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EmotionRegulationGenerationPhaseStrategyConstraintTest {

    @Test
    void highSuppression_rendersResponseModulationConstraint() {
        var rd = new RegulationDefaults(0.35, 0.80, 0.40);
        var axes = new DispositionAxes("competitive", "strict", "conservative", "high", "competitive");

        String message = buildMessage(rd, axes, EmotionType.ANGER);

        assertThat(message).contains("control the outward expression");
        assertThat(message).contains("RESPONSE_MODULATION");
    }

    @Test
    void highReappraisal_rendersCognitiveChangeConstraint() {
        var rd = new RegulationDefaults(0.80, 0.40, 0.30);
        var axes = new DispositionAxes("cooperative", "flexible", "conservative", "high", "accommodating");

        String message = buildMessage(rd, axes, EmotionType.FEAR);

        assertThat(message).contains("step back and reframe");
        assertThat(message).contains("COGNITIVE_CHANGE");
    }

    @Test
    void highRedirect_rendersAttentionalDeploymentConstraint() {
        var rd = new RegulationDefaults(0.30, 0.30, 0.80);
        var axes = new DispositionAxes("cooperative", "flexible", "bold", "low", "protective");

        String message = buildMessage(rd, axes, EmotionType.FEAR);

        assertThat(message).contains("shift what you're focusing on");
        assertThat(message).contains("ATTENTIONAL_DEPLOYMENT");
    }

    @Test
    void tiedCapacities_noStrategyConstraint() {
        var rd = new RegulationDefaults(0.70, 0.70, 0.30);
        var axes = new DispositionAxes("cooperative", "flexible", "conservative", "high", "accommodating");

        String message = buildMessage(rd, axes, EmotionType.ANGER);

        assertThat(message).doesNotContain("Your instinct is to");
    }

    private String buildMessage(RegulationDefaults rd, DispositionAxes axes, EmotionType emotionType) {
        var context = new CognitionTickContext("test-agent", "test-tenant", null,
                (a, t) -> Set.of());
        var emotion = new CognitiveEmotion(emotionType, 0.55, "test-subject",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(),
                HabituationState.empty());
        return EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null,
                0.0, null, null, axes, emotionType,
                rd, null, null);
    }
}
