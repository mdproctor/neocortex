package io.casehub.neocortex.cognition.regulation;

import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.index.CognitiveDerivationEngine;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognitive.index.RegulationDefaults;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;

class RegulationStrategyPredictorTest {

    private final RegulationStrategyPredictor predictor = new RegulationStrategyPredictor();

    // Coherent suppressor: avoidant + conservative, no CC-enabling traits
    // (cooperative social is mild CC signal but below threshold)
    private static final DispositionAxes SUPPRESSOR =
            new DispositionAxes("cooperative", "moderate", "conservative", "low", "avoidant");

    // Coherent reappraiser: analytical + strict + cooperative + high autonomy
    private static final DispositionAxes REAPPRAISER =
            new DispositionAxes("cooperative", "strict", "calculated", "high", "analytical");

    // Incoherent by signal-count, but continuous weights resolve as clear suppressor
    private static final DispositionAxes INCOHERENT =
            new DispositionAxes("independent", "flexible", "conservative", "moderate", "avoidant");

    private static final RegulationDefaults SUPPRESSOR_RD = CognitiveDerivationEngine.deriveRegulationDefaults(SUPPRESSOR);
    private static final RegulationDefaults REAPPRAISER_RD = CognitiveDerivationEngine.deriveRegulationDefaults(REAPPRAISER);
    private static final RegulationDefaults INCOHERENT_RD = CognitiveDerivationEngine.deriveRegulationDefaults(INCOHERENT);

    // --- Coherent suppressor predictions ---

    @Test
    void suppressor_anger_predictsResponseModulation() {
        var result = predictor.predict(SUPPRESSOR_RD, SUPPRESSOR, EmotionType.ANGER, 0.5, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.RESPONSE_MODULATION);
    }

    @Test
    void suppressor_shame_predictsSituationSelection() {
        // Nathanson 1992: avoidant personalities withdraw from shame.
        // Strategy exploration: SS=5 for this personality.
        var result = predictor.predict(SUPPRESSOR_RD, SUPPRESSOR, EmotionType.SHAME, 0.5, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.SITUATION_SELECTION);
    }

    @Test
    void suppressor_reproach_predictsResponseModulation() {
        var result = predictor.predict(SUPPRESSOR_RD, SUPPRESSOR, EmotionType.REPROACH, 0.5, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.RESPONSE_MODULATION);
    }

    // --- Coherent reappraiser predictions ---

    @Test
    void reappraiser_anger_predictsCognitiveChange() {
        var result = predictor.predict(REAPPRAISER_RD, REAPPRAISER, EmotionType.ANGER, 0.5, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.COGNITIVE_CHANGE);
    }

    @Test
    void reappraiser_shame_predictsCognitiveChange() {
        var result = predictor.predict(REAPPRAISER_RD, REAPPRAISER, EmotionType.SHAME, 0.5, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.COGNITIVE_CHANGE);
    }

    // --- Fear: universal AD ---

    @Test
    void fear_alwaysPredictsAttentionalDeployment() {
        var result = predictor.predict(SUPPRESSOR_RD, SUPPRESSOR, EmotionType.FEAR, 0.5, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT);
    }

    // --- Viable alternative: moderate reappraisal capacity blocks RM pinning ---

    @Test
    void viableReappraisal_controllableNeg_fallsThrough() {
        // suppression=0.95 above threshold, but reappraisal=0.45 exceeds VIABLE_REAPPRAISAL
        // → predictor defers to LLM for situation-specific choice
        var result = predictor.predict(INCOHERENT_RD, INCOHERENT, EmotionType.ANGER, 0.5, 0.0, null);
        assertThat(result).isEmpty();
    }

    // --- Positive emotions: no regulation ---

    @Test
    void positiveEmotion_noRegulation() {
        var result = predictor.predict(SUPPRESSOR_RD, SUPPRESSOR, EmotionType.JOY, 0.5, 0.0, null);
        assertThat(result).isEmpty();
    }

    // --- Contextual overrides ---

    @Test
    void highIntensity_overridesAll() {
        var result = predictor.predict(SUPPRESSOR_RD, SUPPRESSOR, EmotionType.ANGER, 0.9, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT);
    }

    @Test
    void highFatigue_overridesAll() {
        var result = predictor.predict(REAPPRAISER_RD, REAPPRAISER, EmotionType.ANGER, 0.5, 0.8, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT);
    }

    @Test
    void highLoad_constrainsToLowCost() {
        var result = predictor.predict(REAPPRAISER_RD, REAPPRAISER, EmotionType.ANGER, 0.5, 0.0, CognitiveLoadLevel.HIGH);
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
// --- Continuous capacity threshold tests ---

    @Test
    void predict_strongReappraiser_controllableNeg_pinsCognitiveChange() {
        var rd     = new RegulationDefaults(0.90, 0.40, 0.20);
        var axes   = new DispositionAxes("cooperative", "strict", "calculated", "high", "analytical");
        var result = predictor.predict(rd, axes, EmotionType.ANGER, 0.55, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.COGNITIVE_CHANGE);
    }

    @Test
    void predict_strongSuppressor_controllableNeg_pinsResponseModulation() {
        var rd     = new RegulationDefaults(0.30, 0.80, 0.60);
        var axes   = new DispositionAxes("cooperative", "moderate", "conservative", "low", "avoidant");
        var result = predictor.predict(rd, axes, EmotionType.ANGER, 0.55, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.RESPONSE_MODULATION);
    }

    @Test
    void predict_mixedProfile_controllableNeg_fallsThrough() {
        var rd     = new RegulationDefaults(0.68, 0.70, 0.45);
        var axes   = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "cooperative");
        var result = predictor.predict(rd, axes, EmotionType.ANGER, 0.55, 0.0, null);
        assertThat(result).isEmpty();
    }

    @Test
    void predict_selfConscious_avoidant_pinsSituationSelection() {
        // Nathanson 1992: avoidant conflict mode → SS for self-conscious emotions
        var rd     = new RegulationDefaults(0.30, 0.80, 0.60);
        var axes   = new DispositionAxes("cooperative", "moderate", "conservative", "low", "avoidant");
        var result = predictor.predict(rd, axes, EmotionType.SHAME, 0.55, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.SITUATION_SELECTION);
    }

    @Test
    void predict_selfConscious_nonAvoidant_lowReappraisal_fallsThrough() {
        var rd     = new RegulationDefaults(0.30, 0.80, 0.60);
        var axes   = new DispositionAxes("cooperative", "moderate", "conservative", "low", "cooperative");
        var result = predictor.predict(rd, axes, EmotionType.SHAME, 0.55, 0.0, null);
        assertThat(result).isEmpty();
    }

    @Test
    void predict_selfConscious_strongReappraiser_pinsCognitiveChange() {
        var rd     = new RegulationDefaults(0.90, 0.40, 0.20);
        var axes   = new DispositionAxes("cooperative", "strict", "bold", "high", "analytical");
        var result = predictor.predict(rd, axes, EmotionType.SHAME, 0.55, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.COGNITIVE_CHANGE);
    }

    @Test
    void predict_highIntensity_overrides_toAD() {
        var rd     = new RegulationDefaults(0.90, 0.40, 0.20);
        var axes   = new DispositionAxes("cooperative", "strict", "calculated", "high", "analytical");
        var result = predictor.predict(rd, axes, EmotionType.ANGER, 0.80, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT);
    }

    @Test
    void predict_ccDominant_overwhelmingGap_pinsCC() {
        // reappraisal(0.90) - suppression(0.50) = 0.40 > OVERWHELMING_DOMINANCE(0.30)
        // Reappraisal overwhelmingly dominates — pin CC despite viable alternatives
        var rd     = new RegulationDefaults(0.90, 0.50, 0.45);
        var axes   = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "cooperative");
        var result = predictor.predict(rd, axes, EmotionType.ANGER, 0.55, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.COGNITIVE_CHANGE);
    }

    @Test
    void predict_ccDominant_competitiveAlternatives_fallsThrough() {
        // Ford & Troy 2019: genuinely competitive capacities (gap < 0.30) → fall through
        // reappraisal(0.70) - suppression(0.55) = 0.15 < OVERWHELMING_DOMINANCE(0.30)
        var rd     = new RegulationDefaults(0.70, 0.55, 0.30);
        var axes   = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "cooperative");
        var result = predictor.predict(rd, axes, EmotionType.ANGER, 0.55, 0.0, null);
        assertThat(result).isEmpty();
    }

    @Test
    void predict_ccDominant_noViableAlternatives_pinsCC() {
        // Clear reappraiser: no viable suppression or redirect
        var rd     = new RegulationDefaults(0.90, 0.40, 0.20);
        var axes   = new DispositionAxes("cooperative", "strict", "calculated", "high", "analytical");
        var result = predictor.predict(rd, axes, EmotionType.ANGER, 0.55, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.COGNITIVE_CHANGE);
    }


    @Test
    void mildDistress_treatedAsControllable_allowsReappraisal() {
        // #907: DISTRESS at low intensity should be reappraised, not forced to AD
        var result = predictor.predict(REAPPRAISER_RD, REAPPRAISER, EmotionType.DISTRESS, 0.18, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy())
                .as("mild DISTRESS with high reappraisal capacity should not force AD")
                .isNotEqualTo(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT);
    }

    @Test
    void intenseDistress_remainsUncontrollable_forcesAD() {
        // DISTRESS at high intensity is genuinely uncontrollable — AD is correct
        var result = predictor.predict(REAPPRAISER_RD, REAPPRAISER, EmotionType.DISTRESS, 0.65, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT);
    }

    // --- Dynamic capacity: modulator → predictor integration ---

    @Test
    void compoundingShame_modulatedCapacity_fallsThrough() {
        // #855: after 4 recent SS failures, modulated redirect drops below VIABLE_ALTERNATIVE
        var now = Instant.now();
        var failures = List.of(
                new RegulationOutcome(EmotionRegulationStrategy.SITUATION_SELECTION, 0.15, now.minusSeconds(300)),
                new RegulationOutcome(EmotionRegulationStrategy.SITUATION_SELECTION, 0.20, now.minusSeconds(600)),
                new RegulationOutcome(EmotionRegulationStrategy.SITUATION_SELECTION, 0.10, now.minusSeconds(900)),
                new RegulationOutcome(EmotionRegulationStrategy.SITUATION_SELECTION, 0.25, now.minusSeconds(1200)));
        var modulated = RegulationCapacityModulator.modulate(SUPPRESSOR_RD, failures, now);
        var result = predictor.predict(modulated, SUPPRESSOR, EmotionType.SHAME, 0.55, 0.0, null);
        assertThat(result).as("modulated avoidant shame should fall through to LLM").isEmpty();
    }

    @Test
    void singleShame_noModulation_pinsSS() {
        // No regression: single episode without modulation still pins to SS
        var now = Instant.now();
        var result = predictor.predict(SUPPRESSOR_RD, SUPPRESSOR, EmotionType.SHAME, 0.55, 0.0, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(EmotionRegulationStrategy.SITUATION_SELECTION);
    }
}
