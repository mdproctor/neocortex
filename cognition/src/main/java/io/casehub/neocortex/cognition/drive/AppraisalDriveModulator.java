package io.casehub.neocortex.cognition.drive;

import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Maps appraised emotions to drive modulations via Frijda (1986) action
 * tendencies: antagonism→COMPETENCE, avoidance→AUTONOMY,
 * approach→CURIOSITY, being-with→AFFILIATION.
 */
public final class AppraisalDriveModulator {

    static final double BOOST = 0.5;
    static final double SECONDARY_BOOST = 0.25;
    static final double REDUCE = -0.2;

    private AppraisalDriveModulator() {}

    public static Map<DriveAxis, Double> modulate(List<CognitiveEmotion> emotions) {
        var deltas = new EnumMap<DriveAxis, Double>(DriveAxis.class);
        for (var axis : DriveAxis.values()) {
            deltas.put(axis, 0.0);
        }
        for (var emotion : emotions) {
            var mapping = MAPPINGS.get(emotion.type());
            if (mapping == null) continue;
            double intensity = emotion.intensity();
            deltas.merge(mapping.primary, mapping.primaryWeight * intensity, Double::sum);
            if (mapping.secondary != null) {
                deltas.merge(mapping.secondary, mapping.secondaryWeight * intensity, Double::sum);
            }
        }
        deltas.replaceAll((axis, v) -> Math.tanh(v));
        return deltas;
    }

    public static Map<DriveAxis, String> dominantContributions(List<CognitiveEmotion> emotions) {
        var contributions = new EnumMap<DriveAxis, String>(DriveAxis.class);
        var maxDeltas     = new EnumMap<DriveAxis, Double>(DriveAxis.class);
        for (var emotion : emotions) {
            var mapping = MAPPINGS.get(emotion.type());
            if (mapping == null) {continue;}
            double primaryDelta = Math.abs(mapping.primaryWeight * emotion.intensity());
            if (primaryDelta > maxDeltas.getOrDefault(mapping.primary, 0.0)) {
                maxDeltas.put(mapping.primary, primaryDelta);
                contributions.put(mapping.primary, emotion.type().name());
            }
            if (mapping.secondary != null) {
                double secondaryDelta = Math.abs(mapping.secondaryWeight * emotion.intensity());
                if (secondaryDelta > maxDeltas.getOrDefault(mapping.secondary, 0.0)) {
                    maxDeltas.put(mapping.secondary, secondaryDelta);
                    contributions.put(mapping.secondary, emotion.type().name());
                }
            }
        }
        return contributions;
    }


    private record DriveMapping(DriveAxis primary, double primaryWeight,
                                DriveAxis secondary, double secondaryWeight) {
        DriveMapping(DriveAxis primary, double primaryWeight) {
            this(primary, primaryWeight, null, 0);
        }
    }

    // Frijda (1986) action tendencies → SDT drive axes
    // Lazarus (1991) core relational themes inform coping direction
    private static final Map<EmotionType, DriveMapping> MAPPINGS = Map.ofEntries(
            // Antagonism → COMPETENCE (overcome, assert)
            Map.entry(EmotionType.ANGER,       new DriveMapping(DriveAxis.COMPETENCE, BOOST)),
            Map.entry(EmotionType.REPROACH,     new DriveMapping(DriveAxis.COMPETENCE, BOOST, DriveAxis.AFFILIATION, REDUCE)),
            Map.entry(EmotionType.RESENTMENT,   new DriveMapping(DriveAxis.COMPETENCE, BOOST, DriveAxis.AFFILIATION, REDUCE)),
            Map.entry(EmotionType.HATE,         new DriveMapping(DriveAxis.COMPETENCE, SECONDARY_BOOST, DriveAxis.AFFILIATION, REDUCE)),

            // Avoidance → AUTONOMY (escape, regain control)
            Map.entry(EmotionType.FEAR,         new DriveMapping(DriveAxis.AUTONOMY, BOOST)),
            Map.entry(EmotionType.DISTRESS,     new DriveMapping(DriveAxis.AUTONOMY, BOOST)),
            Map.entry(EmotionType.FEARS_CONFIRMED, new DriveMapping(DriveAxis.AUTONOMY, BOOST)),
            Map.entry(EmotionType.SHAME,        new DriveMapping(DriveAxis.AUTONOMY, BOOST, DriveAxis.AFFILIATION, SECONDARY_BOOST)),
            Map.entry(EmotionType.REMORSE,      new DriveMapping(DriveAxis.AUTONOMY, SECONDARY_BOOST, DriveAxis.AFFILIATION, SECONDARY_BOOST)),
            Map.entry(EmotionType.DISAPPOINTMENT, new DriveMapping(DriveAxis.AUTONOMY, SECONDARY_BOOST)),

            // Approach → CURIOSITY (explore, engage)
            Map.entry(EmotionType.HOPE,         new DriveMapping(DriveAxis.CURIOSITY, BOOST, DriveAxis.COMPETENCE, SECONDARY_BOOST)),
            Map.entry(EmotionType.SATISFACTION,  new DriveMapping(DriveAxis.CURIOSITY, BOOST)),
            Map.entry(EmotionType.RELIEF,        new DriveMapping(DriveAxis.CURIOSITY, SECONDARY_BOOST)),
            Map.entry(EmotionType.JOY,          new DriveMapping(DriveAxis.CURIOSITY, SECONDARY_BOOST, DriveAxis.AFFILIATION, SECONDARY_BOOST)),

            // Being-with → AFFILIATION (connect, bond)
            Map.entry(EmotionType.LOVE,         new DriveMapping(DriveAxis.AFFILIATION, BOOST)),
            Map.entry(EmotionType.GRATITUDE,    new DriveMapping(DriveAxis.AFFILIATION, BOOST)),
            Map.entry(EmotionType.HAPPY_FOR,    new DriveMapping(DriveAxis.AFFILIATION, SECONDARY_BOOST)),
            Map.entry(EmotionType.PITY,         new DriveMapping(DriveAxis.AFFILIATION, SECONDARY_BOOST)),
            Map.entry(EmotionType.ADMIRATION,   new DriveMapping(DriveAxis.AFFILIATION, SECONDARY_BOOST, DriveAxis.CURIOSITY, SECONDARY_BOOST)),

            // Dominance → COMPETENCE (mastery, control)
            Map.entry(EmotionType.PRIDE,        new DriveMapping(DriveAxis.COMPETENCE, BOOST)),
            Map.entry(EmotionType.GRATIFICATION, new DriveMapping(DriveAxis.COMPETENCE, BOOST, DriveAxis.CURIOSITY, SECONDARY_BOOST)),
            Map.entry(EmotionType.GLOATING,     new DriveMapping(DriveAxis.COMPETENCE, SECONDARY_BOOST, DriveAxis.AFFILIATION, REDUCE))
    );
}
