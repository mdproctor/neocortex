package io.casehub.neocortex.cognition.coping;

import io.casehub.neocortex.cognitive.CognitiveEmotion;

import java.util.List;

public class PreCopingSafetyGate {

    static final double FATIGUE_THRESHOLD = 0.7;
    static final double INTENSITY_THRESHOLD = 0.75;

    public boolean shouldSkipCoping(double regulationFatigue, List<CognitiveEmotion> emotions) {
        if (regulationFatigue > FATIGUE_THRESHOLD) return true;
        return emotions.stream().anyMatch(e -> e.intensity() > INTENSITY_THRESHOLD);
    }
}
