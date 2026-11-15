package io.casehub.neocortex.cognition.coping;

import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.PadProjection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreCopingSafetyGateTest {

    private final PreCopingSafetyGate gate = new PreCopingSafetyGate();

    private static CognitiveEmotion emotion(EmotionType type, double intensity) {
        return new CognitiveEmotion(type, intensity, "test-agent",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
    }

    @Test
    void highFatigue_skipsCoping() {
        assertThat(gate.shouldSkipCoping(0.75, List.of(emotion(EmotionType.ANGER, 0.5)))).isTrue();
    }

    @Test
    void highIntensity_skipsCoping() {
        assertThat(gate.shouldSkipCoping(0.3, List.of(emotion(EmotionType.FEAR, 0.8)))).isTrue();
    }

    @Test
    void normalFatigueAndIntensity_proceedsToCoping() {
        assertThat(gate.shouldSkipCoping(0.5, List.of(emotion(EmotionType.ANGER, 0.6)))).isFalse();
    }

    @Test
    void exactThreshold_fatigue_doesNotSkip() {
        assertThat(gate.shouldSkipCoping(0.7, List.of(emotion(EmotionType.ANGER, 0.5)))).isFalse();
    }

    @Test
    void exactThreshold_intensity_doesNotSkip() {
        assertThat(gate.shouldSkipCoping(0.3, List.of(emotion(EmotionType.ANGER, 0.75)))).isFalse();
    }

    @Test
    void emptyEmotions_proceedsToCoping() {
        assertThat(gate.shouldSkipCoping(0.3, List.of())).isFalse();
    }

    @Test
    void multipleEmotions_oneAboveThreshold_skipsCoping() {
        assertThat(gate.shouldSkipCoping(0.3, List.of(
                emotion(EmotionType.ANGER, 0.5),
                emotion(EmotionType.FEAR, 0.8)))).isTrue();
    }
}
