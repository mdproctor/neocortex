package io.casehub.neocortex.cognition.coping;

import io.casehub.neocortex.cognition.regulation.EmotionRegulationStrategy;

import java.util.Optional;

// Marsella & Gratch (2009) EMA coping strategy taxonomy
public enum CopingStrategy {
    // Attention-related
    SEEK_INFORMATION(CopingCategory.ATTENTION, null),
    SUPPRESS_INFORMATION(CopingCategory.ATTENTION, null),

    // Belief-related
    SHIFT_RESPONSIBILITY(CopingCategory.BELIEF, null),
    WISHFUL_THINKING(CopingCategory.BELIEF, null),
    DENIAL(CopingCategory.BELIEF, null),

    // Desire-related
    MENTAL_DISENGAGEMENT(CopingCategory.DESIRE, EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT),
    POSITIVE_REINTERPRETATION(CopingCategory.DESIRE, EmotionRegulationStrategy.COGNITIVE_CHANGE),
    ACCEPTANCE(CopingCategory.DESIRE, null),

    // Intention-related
    PLANNING(CopingCategory.INTENTION, null),
    SEEK_INSTRUMENTAL_SUPPORT(CopingCategory.INTENTION, EmotionRegulationStrategy.SOCIAL_SUPPORT_SEEKING),
    SEEK_EMOTIONAL_SUPPORT(CopingCategory.INTENTION, EmotionRegulationStrategy.SOCIAL_SHARING),
    ACTIVE_CONFRONTATION(CopingCategory.INTENTION, EmotionRegulationStrategy.SITUATION_MODIFICATION),
    SUPPRESSION(CopingCategory.INTENTION, EmotionRegulationStrategy.RESPONSE_MODULATION),
    PROCRASTINATION(CopingCategory.INTENTION, null),
    RESIGNATION(CopingCategory.INTENTION, null),
    AVOIDANCE(CopingCategory.INTENTION, EmotionRegulationStrategy.SITUATION_SELECTION);

    private final CopingCategory category;
    private final EmotionRegulationStrategy regulationTarget;

    CopingStrategy(CopingCategory category, EmotionRegulationStrategy regulationTarget) {
        this.category = category;
        this.regulationTarget = regulationTarget;
    }

    public CopingCategory category() {
        return category;
    }

    public Optional<EmotionRegulationStrategy> regulationStrategy() {
        return Optional.ofNullable(regulationTarget);
    }

    public boolean isRegulationRouted() {
        return regulationTarget != null;
    }
}
