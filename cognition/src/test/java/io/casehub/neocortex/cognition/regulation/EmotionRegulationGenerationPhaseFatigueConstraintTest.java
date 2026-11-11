package io.casehub.neocortex.cognition.regulation;

import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.PadProjection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EmotionRegulationGenerationPhaseFatigueConstraintTest {

    @Test
    void moderateFatigue_includesDepletionWarning() {
        String message = buildMessage(0.6);
        assertThat(message).contains("reappraisal capacity is running low");
        assertThat(message).doesNotContain("MUST choose ATTENTIONAL_DEPLOYMENT");
    }

    @Test
    void severeFatigue_includesMandatoryConstraint() {
        String message = buildMessage(0.8);
        assertThat(message).contains("reappraisal capacity is depleted");
        assertThat(message).contains("MUST choose ATTENTIONAL_DEPLOYMENT");
    }

    @Test
    void lowFatigue_noProcessConstraint() {
        String message = buildMessage(0.3);
        assertThat(message).doesNotContain("reappraisal capacity");
        assertThat(message).contains("Regulation fatigue: 30%");
    }

    private String buildMessage(double fatigue) {
        var context = new CognitionTickContext("test-agent", "test-tenant", null,
                (a, t) -> Set.of());
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.6, "confrontation",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(),
                HabituationState.empty());
        return EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null, fatigue, null);
    }
}
