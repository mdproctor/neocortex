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

class EmotionRegulationGenerationPhaseLoadConstraintTest {

    @Test
    void highLoad_includesProcessConstraint() {
        var context = new CognitionTickContext("test-agent", "test-tenant", null,
                (a, t) -> Set.of());
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.6, "confrontation",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(),
                HabituationState.empty());
        var highLoad = new CognitiveLoadEstimate(CognitiveLoadLevel.HIGH, 4, 3, 3);

        String message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, highLoad, 0.0, null);

        assertThat(message).contains("cannot hold the multi-step reasoning");
        assertThat(message).contains("ATTENTIONAL_DEPLOYMENT");
        assertThat(message).contains("RESPONSE_MODULATION");
    }

    @Test
    void lowLoad_doesNotIncludeProcessConstraint() {
        var context = new CognitionTickContext("test-agent", "test-tenant", null,
                (a, t) -> Set.of());
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.6, "confrontation",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(),
                HabituationState.empty());
        var lowLoad = new CognitiveLoadEstimate(CognitiveLoadLevel.LOW, 1, 0, 0);

        String message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, lowLoad, 0.0, null);

        assertThat(message).doesNotContain("cannot hold the multi-step reasoning");
    }
}
