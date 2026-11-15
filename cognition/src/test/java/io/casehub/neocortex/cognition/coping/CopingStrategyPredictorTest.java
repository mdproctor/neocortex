package io.casehub.neocortex.cognition.coping;

import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.PadProjection;
import io.casehub.neocortex.cognitive.index.CopingDefaults;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognition.regulation.CognitiveLoadLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CopingStrategyPredictorTest {

    private final CopingStrategyPredictor predictor = new CopingStrategyPredictor();

    private static final CopingDefaults PLANFUL = new CopingDefaults(0.85, 0.4, 0.2, 0.4, 0.6);
    private static final CopingDefaults AVOIDANT = new CopingDefaults(0.2, 0.3, 0.85, 0.3, 0.15);
    private static final CopingDefaults SUPPORT_SEEKING = new CopingDefaults(0.4, 0.4, 0.3, 0.85, 0.5);
    private static final CopingDefaults REINTERPRETER = new CopingDefaults(0.4, 0.85, 0.2, 0.4, 0.6);
    private static final CopingDefaults BALANCED = new CopingDefaults(0.5, 0.5, 0.5, 0.5, 0.5);

    private static final DispositionAxes AVOIDANT_AXES =
            new DispositionAxes("independent", "flexible", "conservative", "low", "avoidant");
    private static final DispositionAxes ANALYTICAL_AXES =
            new DispositionAxes("cooperative", "strict", "calculated", "high", "analytical");

    private static Map<String, Double> dims(double controllability) {
        return Map.of("controllability", controllability, "adjustability", 0.5);
    }

    private static CognitiveEmotion emotion(EmotionType type, double intensity) {
        return new CognitiveEmotion(type, intensity, "test-agent",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
    }

    // --- Stage 1: Cognitive load gate ---

    @Test
    void highCognitiveLoad_filtersToLowCostStrategies() {
        var result = predictor.predict(BALANCED, dims(0.5),
                List.of(emotion(EmotionType.ANGER, 0.6)), null, CognitiveLoadLevel.HIGH);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isIn(
                CopingStrategy.SUPPRESSION, CopingStrategy.AVOIDANCE,
                CopingStrategy.MENTAL_DISENGAGEMENT, CopingStrategy.RESIGNATION);
    }

    @Test
    void nonHighCognitiveLoad_allowsComplexStrategies() {
        var result = predictor.predict(PLANFUL, dims(0.8),
                List.of(emotion(EmotionType.ANGER, 0.6)), ANALYTICAL_AXES, CognitiveLoadLevel.MODERATE);
        assertThat(result).isPresent();
        assertThat(result.get().strategy().category()).isEqualTo(CopingCategory.INTENTION);
    }

    // --- Stage 2: Controllability-based filtering ---

    @Test
    void highControllability_filtersToIntentionStrategies() {
        var result = predictor.predict(PLANFUL, dims(0.8),
                List.of(emotion(EmotionType.ANGER, 0.6)), null, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy().category()).isEqualTo(CopingCategory.INTENTION);
    }

    @Test
    void lowControllability_filtersToBeliefDesireStrategies() {
        var result = predictor.predict(AVOIDANT, dims(0.1),
                List.of(emotion(EmotionType.FEAR, 0.6)), null, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy().category()).isIn(CopingCategory.BELIEF, CopingCategory.DESIRE);
    }

    @Test
    void mediumControllability_allowsAllCategories() {
        var result = predictor.predict(PLANFUL, dims(0.45),
                List.of(emotion(EmotionType.ANGER, 0.6)), null, null);
        assertThat(result).isPresent();
    }

    // --- Stage 3: Mild distress override ---

    @Test
    void mildUncontrollableDistress_widensToControllable() {
        var result = predictor.predict(PLANFUL, dims(0.1),
                List.of(emotion(EmotionType.FEAR, 0.3)), null, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy().category()).isEqualTo(CopingCategory.INTENTION);
    }

    @Test
    void strongUncontrollableDistress_staysFiltered() {
        var result = predictor.predict(AVOIDANT, dims(0.1),
                List.of(emotion(EmotionType.FEAR, 0.7)), null, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy().category()).isIn(CopingCategory.BELIEF, CopingCategory.DESIRE);
    }

    // --- Stage 4: Self-conscious emotion refinement ---

    @Test
    void shameWithAvoidantPersonality_biasesAvoidance() {
        var result = predictor.predict(AVOIDANT, dims(0.4),
                List.of(emotion(EmotionType.SHAME, 0.6)), AVOIDANT_AXES, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(CopingStrategy.AVOIDANCE);
    }

    @Test
    void shameWithHighAvoidanceTendencyOnly_biasesAvoidance() {
        var result = predictor.predict(AVOIDANT, dims(0.4),
                List.of(emotion(EmotionType.SHAME, 0.6)), ANALYTICAL_AXES, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(CopingStrategy.AVOIDANCE);
    }

    @Test
    void remorseWithAvoidantConflictMode_biasesAvoidance() {
        var result = predictor.predict(BALANCED, dims(0.4),
                List.of(emotion(EmotionType.REMORSE, 0.6)), AVOIDANT_AXES, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(CopingStrategy.AVOIDANCE);
    }

    @Test
    void shameWithNonAvoidantPersonality_doesNotForceAvoidance() {
        var defaults = new CopingDefaults(0.8, 0.5, 0.3, 0.4, 0.6);
        var result = predictor.predict(defaults, dims(0.4),
                List.of(emotion(EmotionType.SHAME, 0.6)), ANALYTICAL_AXES, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isNotEqualTo(CopingStrategy.AVOIDANCE);
    }

    // --- Stage 5: Personality bias resolution ---

    @Test
    void planfulPersonality_selectsPlanningFamily() {
        var result = predictor.predict(PLANFUL, dims(0.8),
                List.of(emotion(EmotionType.ANGER, 0.6)), null, null);
        assertThat(result).isPresent();
        var planfulStrategies = Set.of(CopingStrategy.PLANNING,
                CopingStrategy.ACTIVE_CONFRONTATION, CopingStrategy.SEEK_INSTRUMENTAL_SUPPORT);
        assertThat(planfulStrategies).contains(result.get().strategy());
    }

    @Test
    void supportSeekingPersonality_selectsSupportStrategy() {
        var result = predictor.predict(SUPPORT_SEEKING, dims(0.8),
                List.of(emotion(EmotionType.ANGER, 0.6)), null, null);
        assertThat(result).isPresent();
        var supportStrategies = Set.of(CopingStrategy.SEEK_INSTRUMENTAL_SUPPORT,
                CopingStrategy.SEEK_EMOTIONAL_SUPPORT);
        assertThat(supportStrategies).contains(result.get().strategy());
    }

    @Test
    void reinterpreterPersonality_lowControllability_selectsReinterpretation() {
        var result = predictor.predict(REINTERPRETER, dims(0.1),
                List.of(emotion(EmotionType.FEAR, 0.6)), null, null);
        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(CopingStrategy.POSITIVE_REINTERPRETATION);
    }

    @Test
    void familyTie_lowersConfidence() {
        var result = predictor.predict(PLANFUL, dims(0.8),
                List.of(emotion(EmotionType.ANGER, 0.6)), null, null);
        assertThat(result).isPresent();
        assertThat(result.get().familyLabel()).isNotNull();
        assertThat(result.get().confidence()).isLessThan(0.7);
    }

    // --- Edge cases ---

    @Test
    void nullDefaults_returnsEmpty() {
        var result = predictor.predict(null, dims(0.5),
                List.of(emotion(EmotionType.ANGER, 0.6)), null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void emptyEmotions_returnsEmpty() {
        var result = predictor.predict(BALANCED, dims(0.5), List.of(), null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void confidenceIsInUnitInterval() {
        var result = predictor.predict(PLANFUL, dims(0.8),
                List.of(emotion(EmotionType.ANGER, 0.6)), ANALYTICAL_AXES, null);
        assertThat(result).isPresent();
        assertThat(result.get().confidence()).isBetween(0.0, 1.0);
    }
}
