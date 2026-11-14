package io.casehub.neocortex.cognition.appraisal;

import io.casehub.neocortex.cognitive.AlmaPadTable;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.PadProjection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EmotionMapper {

    private EmotionMapper() {}

    public static List<CognitiveEmotion> mapEmotions(List<SecResult> results, String subjectId) {
        var dims = mergeDimensions(results);
        var emotions = new ArrayList<CognitiveEmotion>();
        var now = Instant.now();

        double relevance = dims.getOrDefault(SecDimensions.RELEVANCE, 0.0);
        double novelty = dims.getOrDefault(SecDimensions.NOVELTY, 0.0);
        double urgency = dims.getOrDefault(SecDimensions.URGENCY, 0.0);
        double conduciveness = dims.getOrDefault(SecDimensions.CONDUCIVENESS, 0.0);
        double controllability = dims.getOrDefault(SecDimensions.CONTROLLABILITY, 0.5);
        double internalStd = dims.getOrDefault(SecDimensions.INTERNAL_STANDARDS, 1.0);
        double externalStd = dims.getOrDefault(SecDimensions.EXTERNAL_STANDARDS, 1.0);

        if (relevance < 0.2) return emotions;

        double arousalBoost = urgency * 0.5;

        double fearThreshold = urgency > 0.7 ? 0.6 : 0.4;
        if (conduciveness < -0.2 && controllability < fearThreshold) {
            double intensity = clampIntensity(relevance * Math.abs(conduciveness));
            emotions.add(emotion(EmotionType.FEAR, intensity, subjectId, now, arousalBoost));
        }
        if (conduciveness < -0.2 && controllability > 0.5) {
            double intensity = clampIntensity(relevance * Math.abs(conduciveness));
            emotions.add(emotion(EmotionType.ANGER, intensity, subjectId, now, arousalBoost));
        }
        if (conduciveness < -0.2 && !containsType(emotions, EmotionType.FEAR)
                && !containsType(emotions, EmotionType.ANGER)) {
            double intensity = clampIntensity(relevance * Math.abs(conduciveness));
            emotions.add(emotion(EmotionType.DISTRESS, intensity, subjectId, now, arousalBoost));
        }

        if (conduciveness > 0.3) {
            double intensity = clampIntensity(relevance * conduciveness);
            emotions.add(emotion(EmotionType.JOY, intensity, subjectId, now, arousalBoost));
        }

        if (novelty > 0.4 && conduciveness >= 0) {
            double intensity = clampIntensity(relevance * novelty);
            emotions.add(emotion(EmotionType.HOPE, intensity, subjectId, now, arousalBoost));
        }

        if (internalStd < 0.5) {
            double intensity = clampIntensity(relevance * (1.0 - internalStd));
            emotions.add(emotion(EmotionType.SHAME, intensity, subjectId, now, arousalBoost));
        }

        if (externalStd < 0.5) {
            double intensity = clampIntensity(relevance * (1.0 - externalStd));
            emotions.add(emotion(EmotionType.REPROACH, intensity, subjectId, now, arousalBoost));
        }

        return emotions;
    }

    public static List<ActionTendency> mapTendencies(List<SecResult> results) {
        var dims = mergeDimensions(results);
        var tendencies = new ArrayList<ActionTendency>();

        double conduciveness = dims.getOrDefault(SecDimensions.CONDUCIVENESS, 0.0);
        double controllability = dims.getOrDefault(SecDimensions.CONTROLLABILITY, 0.5);
        double novelty = dims.getOrDefault(SecDimensions.NOVELTY, 0.0);

        if (conduciveness > 0.3) {
            tendencies.add(new ActionTendency(ActionReadiness.APPROACH, clampIntensity(conduciveness), ""));
        } else if (conduciveness < -0.3 && controllability < 0.4) {
            tendencies.add(new ActionTendency(ActionReadiness.AVOIDANCE, clampIntensity(Math.abs(conduciveness)), ""));
        } else if (conduciveness < -0.3 && controllability > 0.5) {
            tendencies.add(new ActionTendency(ActionReadiness.ANTAGONISM, clampIntensity(Math.abs(conduciveness)), ""));
        }

        if (novelty > 0.5) {
            tendencies.add(new ActionTendency(ActionReadiness.ATTENDING, clampIntensity(novelty), ""));
        } else if (novelty < 0.2) {
            tendencies.add(new ActionTendency(ActionReadiness.INTERRUPTION, clampIntensity(1.0 - novelty), ""));
        }

        return tendencies;
    }

    static Map<String, Double> mergeDimensions(List<SecResult> results) {
        var merged = new HashMap<String, Double>();
        for (var result : results) {
            merged.putAll(result.dimensions());
        }
        return merged;
    }

    private static boolean containsType(List<CognitiveEmotion> emotions, EmotionType type) {
        return emotions.stream().anyMatch(e -> e.type() == type);
    }

    private static CognitiveEmotion emotion(EmotionType type, double intensity,
                                            String subjectId, Instant onset,
                                            double arousalBoost) {
        var basePad = AlmaPadTable.project(type, intensity);
        var pad = arousalBoost > 0
                ? new PadProjection(basePad.pleasure(),
                        Math.min(1.0, basePad.arousal() + arousalBoost * intensity),
                        basePad.dominance())
                : basePad;
        return new CognitiveEmotion(type, intensity, subjectId, onset,
                EmotionSource.INTRINSIC, pad);
    }

    private static double clampIntensity(double value) {
        return Math.max(0.01, Math.min(1.0, value));
    }
}
