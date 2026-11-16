package io.casehub.neocortex.cognition.regulation;

import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognitive.index.RegulationDefaults;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Personality × emotion-type regulation strategy prediction (Petter et al. 2025).
 * Emotion type encodes situation controllability (ANGER = controllable, FEAR = not),
 * so the predictor is situation-aware without reading the scenario text.
 */
public class RegulationStrategyPredictor {

    static final double HIGH_INTENSITY_THRESHOLD = 0.75;
    static final double ALIGNED_EFFECTIVENESS = 0.55;
    static final double MISALIGNED_EFFECTIVENESS = 0.35;
    static final double REAPPRAISAL_THRESHOLD    = 0.65;
    static final double SUPPRESSION_THRESHOLD    = 0.65;
    static final double DOMINANCE_MARGIN         = 0.10;
    static final double VIABLE_REAPPRAISAL       = 0.40;
    static final double VIABLE_ALTERNATIVE       = 0.45;
    static final double OVERWHELMING_DOMINANCE   = 0.30;
    static final double MILD_DISTRESS_THRESHOLD  = 0.40;


    public RegulationStrategyPredictor() {}

    public Optional<RegulationPrediction> predict(RegulationDefaults rd,
                                                  DispositionAxes axes,
                                                  EmotionType dominantEmotion,
                                                  double peakIntensity,
                                                  double regulationFatigue,
                                                  @Nullable CognitiveLoadLevel cognitiveLoad) {
        if (regulationFatigue > 0.7) {
            return Optional.of(new RegulationPrediction(
                    EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT, 0.85, MISALIGNED_EFFECTIVENESS));
        }
        if (peakIntensity > HIGH_INTENSITY_THRESHOLD) {
            return Optional.of(new RegulationPrediction(
                    EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT, 0.80, ALIGNED_EFFECTIVENESS));
        }
        if (cognitiveLoad == CognitiveLoadLevel.HIGH) {
            var strategy = predictUnderHighLoad(axes);
            return Optional.of(new RegulationPrediction(strategy, 0.75, ALIGNED_EFFECTIVENESS));
        }
        if (rd == null) {
            return Optional.empty();
        }
        if (emotionCategory(dominantEmotion) == EmotionCategory.UNCONTROLLABLE_NEGATIVE
                && peakIntensity < MILD_DISTRESS_THRESHOLD) {
            return predictControllableFromCapacities(rd, axes);
        }
        return predictFromCapacities(rd, axes, dominantEmotion);
    }

    Optional<RegulationPrediction> predictFromCapacities(
            RegulationDefaults rd, DispositionAxes axes, EmotionType emotion) {
        return switch (emotionCategory(emotion)) {
            case CONTROLLABLE_NEGATIVE -> predictControllableFromCapacities(rd, axes);
            case UNCONTROLLABLE_NEGATIVE -> Optional.of(new RegulationPrediction(
                    EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT, 0.80, ALIGNED_EFFECTIVENESS));
            case SELF_CONSCIOUS -> predictSelfConsciousFromCapacities(rd, axes);
            case OTHER_DIRECTED -> predictOtherDirectedFromCapacities(rd, axes);
            case POSITIVE, UNKNOWN -> Optional.empty();
        };
    }

    private Optional<RegulationPrediction> predictControllableFromCapacities(
            RegulationDefaults rd, DispositionAxes axes) {
        var dominant = dominantAboveThreshold(
                rd.reappraisalCapacity(), EmotionRegulationStrategy.COGNITIVE_CHANGE,
                rd.suppressionCapacity(), EmotionRegulationStrategy.RESPONSE_MODULATION,
                REAPPRAISAL_THRESHOLD, SUPPRESSION_THRESHOLD, DOMINANCE_MARGIN);
        if (dominant == null) {return Optional.empty();}
        if (dominant == EmotionRegulationStrategy.RESPONSE_MODULATION) {
            if (hasCompetitiveAvoidantConflict(axes)) return Optional.empty();
            if (rd.reappraisalCapacity() >= VIABLE_REAPPRAISAL) return Optional.empty();
        }
        if (dominant == EmotionRegulationStrategy.COGNITIVE_CHANGE) {
            double maxAlt = Math.max(rd.suppressionCapacity(), rd.redirectTendency());
            if (maxAlt >= VIABLE_ALTERNATIVE
                    && rd.reappraisalCapacity() - maxAlt < OVERWHELMING_DOMINANCE) {
                return Optional.empty();
            }
        }
        return Optional.of(new RegulationPrediction(dominant, 0.70, ALIGNED_EFFECTIVENESS));
    }

    private Optional<RegulationPrediction> predictSelfConsciousFromCapacities(RegulationDefaults rd, DispositionAxes axes) {
        if (rd.reappraisalCapacity() >= REAPPRAISAL_THRESHOLD) {
            return Optional.of(new RegulationPrediction(
                    EmotionRegulationStrategy.COGNITIVE_CHANGE, 0.75, ALIGNED_EFFECTIVENESS));
        }
        // Nathanson 1992 compass of shame: avoidant personalities withdraw from shame
        // Bonanno & Burton 2013: fall through if redirect capacity degraded by prior failures
        if (axes != null && "avoidant".equals(axes.conflictMode())
                && rd.redirectTendency() >= VIABLE_ALTERNATIVE) {
            return Optional.of(new RegulationPrediction(
                    EmotionRegulationStrategy.SITUATION_SELECTION, 0.75, ALIGNED_EFFECTIVENESS));
        }
        return Optional.empty();
    }

    private Optional<RegulationPrediction> predictOtherDirectedFromCapacities(
            RegulationDefaults rd, DispositionAxes axes) {
        var dominant = dominantAboveThreshold(
                rd.suppressionCapacity(), EmotionRegulationStrategy.RESPONSE_MODULATION,
                rd.reappraisalCapacity(), EmotionRegulationStrategy.COGNITIVE_CHANGE,
                SUPPRESSION_THRESHOLD, REAPPRAISAL_THRESHOLD, DOMINANCE_MARGIN);
        if (dominant == null) {return Optional.empty();}
        return Optional.of(new RegulationPrediction(dominant, 0.70, ALIGNED_EFFECTIVENESS));
    }

    static @Nullable EmotionRegulationStrategy dominantAboveThreshold(
            double capacityA, EmotionRegulationStrategy strategyA,
            double capacityB, EmotionRegulationStrategy strategyB,
            double thresholdA, double thresholdB, double margin) {
        boolean aAbove = capacityA >= thresholdA;
        boolean bAbove = capacityB >= thresholdB;
        if (aAbove && bAbove) {
            if (capacityA - capacityB >= margin) {return strategyA;}
            if (capacityB - capacityA >= margin) {return strategyB;}
            return null;
        }
        if (aAbove) {return strategyA;}
        if (bAbove) {return strategyB;}
        return null;
    }


    private boolean hasCompetitiveAvoidantConflict(DispositionAxes axes) {
        boolean competitive = "competitive".equals(axes.conflictMode())
                || "competitive".equals(axes.socialOrient());
        boolean avoidant = "avoidant".equals(axes.conflictMode());
        return competitive && avoidant;
    }

    private static EmotionRegulationStrategy predictUnderHighLoad(DispositionAxes axes) {
        return switch (axes.conflictMode()) {
            case "avoidant" -> EmotionRegulationStrategy.RESPONSE_MODULATION;
            case "competitive" -> EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT;
            default -> switch (axes.riskAppetite()) {
                case "bold" -> EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT;
                default -> EmotionRegulationStrategy.RESPONSE_MODULATION;
            };
        };
    }

    enum EmotionCategory {
        CONTROLLABLE_NEGATIVE,
        UNCONTROLLABLE_NEGATIVE,
        SELF_CONSCIOUS,
        OTHER_DIRECTED,
        POSITIVE,
        UNKNOWN
    }

    static EmotionCategory emotionCategory(EmotionType type) {
        return switch (type) {
            case ANGER, HATE, RESENTMENT, GLOATING -> EmotionCategory.CONTROLLABLE_NEGATIVE;
            case FEAR, DISTRESS, FEARS_CONFIRMED -> EmotionCategory.UNCONTROLLABLE_NEGATIVE;
            case SHAME, REMORSE -> EmotionCategory.SELF_CONSCIOUS;
            case REPROACH, DISAPPOINTMENT, PITY -> EmotionCategory.OTHER_DIRECTED;
            case JOY, HOPE, INTEREST, SATISFACTION, RELIEF, PRIDE, ADMIRATION,
                 GRATITUDE, GRATIFICATION, HAPPY_FOR, LOVE -> EmotionCategory.POSITIVE;
        };
    }

}
