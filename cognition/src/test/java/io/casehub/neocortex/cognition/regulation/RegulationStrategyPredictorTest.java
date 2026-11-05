package io.casehub.neocortex.cognition.regulation;

import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegulationStrategyPredictorTest {

    private final RegulationStrategyPredictor predictor = new RegulationStrategyPredictor();

    // Coherent suppressor: avoidant + conservative, no CC-enabling traits
    // (cooperative social is mild CC signal but below threshold)
    private static final DispositionAxes SUPPRESSOR =
            new DispositionAxes("cooperative", "moderate", "conservative", "low", "avoidant");

    // Coherent reappraiser: analytical + strict + cooperative + high autonomy
    private static final DispositionAxes REAPPRAISER =
            new DispositionAxes("cooperative", "strict", "calculated", "high", "analytical");

    // Incoherent: avoidant + flexible + independent (suppressor + CC-enabling)
    private static final DispositionAxes INCOHERENT =
            new DispositionAxes("independent", "flexible", "conservative", "moderate", "avoidant");

    // --- Coherent suppressor predictions ---

    @Test
    void suppressor_anger_predictsResponseModulation() {
        var result = predictor.predict(SUPPRESSOR, EmotionType.ANGER, 0.5, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.RESPONSE_MODULATION);
    }

    @Test
    void suppressor_shame_fallsThrough() {
        // 7 judge runs: RM=2-3, CC=2-3, AD=3. Shame is self-evaluative —
        // no strategy scores 4+ for suppressors. LLM must handle this.
        var result = predictor.predict(SUPPRESSOR, EmotionType.SHAME, 0.5, 0.0, null);
        assertThat(result).isEmpty();
    }

    @Test
    void suppressor_reproach_predictsResponseModulation() {
        var result = predictor.predict(SUPPRESSOR, EmotionType.REPROACH, 0.5, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.RESPONSE_MODULATION);
    }

    // --- Coherent reappraiser predictions ---

    @Test
    void reappraiser_anger_predictsCognitiveChange() {
        var result = predictor.predict(REAPPRAISER, EmotionType.ANGER, 0.5, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.COGNITIVE_CHANGE);
    }

    @Test
    void reappraiser_shame_predictsCognitiveChange() {
        var result = predictor.predict(REAPPRAISER, EmotionType.SHAME, 0.5, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.COGNITIVE_CHANGE);
    }

    // --- Fear: universal AD ---

    @Test
    void fear_alwaysPredictsAttentionalDeployment() {
        var result = predictor.predict(SUPPRESSOR, EmotionType.FEAR, 0.5, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT);
    }

    // --- Incoherent personality falls through ---

    @Test
    void incoherentPersonality_fallsThrough() {
        // avoidant + flexible + independent = suppressor + CC-enabling → incoherent
        var result = predictor.predict(INCOHERENT, EmotionType.ANGER, 0.5, 0.0, null);
        assertThat(result).isEmpty();
    }

    // --- Positive emotions: no regulation ---

    @Test
    void positiveEmotion_noRegulation() {
        var result = predictor.predict(SUPPRESSOR, EmotionType.JOY, 0.5, 0.0, null);
        assertThat(result).isEmpty();
    }

    // --- Contextual overrides ---

    @Test
    void highIntensity_overridesAll() {
        var result = predictor.predict(SUPPRESSOR, EmotionType.ANGER, 0.9, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT);
    }

    @Test
    void highFatigue_overridesAll() {
        var result = predictor.predict(REAPPRAISER, EmotionType.ANGER, 0.5, 0.8, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT);
    }

    @Test
    void highLoad_constrainsToLowCost() {
        var result = predictor.predict(REAPPRAISER, EmotionType.ANGER, 0.5, 0.0, CognitiveLoadLevel.HIGH);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).satisfiesAnyOf(
                s -> assertThat(s).isEqualTo(EmotionRegulationStrategy.RESPONSE_MODULATION),
                s -> assertThat(s).isEqualTo(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT));
    }

    // --- Emotion category ---

    @Test
    void emotionCategory_classifiesCorrectly() {
        assertThat(RegulationStrategyPredictor.emotionCategory(EmotionType.ANGER))
                .isEqualTo(RegulationStrategyPredictor.EmotionCategory.CONTROLLABLE_NEGATIVE);
        assertThat(RegulationStrategyPredictor.emotionCategory(EmotionType.FEAR))
                .isEqualTo(RegulationStrategyPredictor.EmotionCategory.UNCONTROLLABLE_NEGATIVE);
        assertThat(RegulationStrategyPredictor.emotionCategory(EmotionType.SHAME))
                .isEqualTo(RegulationStrategyPredictor.EmotionCategory.SELF_CONSCIOUS);
        assertThat(RegulationStrategyPredictor.emotionCategory(EmotionType.REPROACH))
                .isEqualTo(RegulationStrategyPredictor.EmotionCategory.OTHER_DIRECTED);
        assertThat(RegulationStrategyPredictor.emotionCategory(EmotionType.JOY))
                .isEqualTo(RegulationStrategyPredictor.EmotionCategory.POSITIVE);
    }
}
