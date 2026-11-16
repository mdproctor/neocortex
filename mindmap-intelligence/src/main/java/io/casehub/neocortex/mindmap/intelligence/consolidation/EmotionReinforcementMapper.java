package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.cognitive.EmotionType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public final class EmotionReinforcementMapper {

    public record ReinforcementSignal(double lambdaSign, Set<String> pathwayTags) {}

    private static final Map<EmotionType, ReinforcementSignal> MAPPINGS;

    static {
        var m = new EnumMap<EmotionType, ReinforcementSignal>(EmotionType.class);

        var negBisThreat = new ReinforcementSignal(-1.0, Set.of("bis", "threat"));
        m.put(EmotionType.FEAR, negBisThreat);
        m.put(EmotionType.FEARS_CONFIRMED, negBisThreat);

        var posBas = new ReinforcementSignal(1.0, Set.of("bas"));
        m.put(EmotionType.HOPE, posBas);
        m.put(EmotionType.INTEREST, posBas);
        m.put(EmotionType.RELIEF, posBas);
        m.put(EmotionType.JOY, posBas);
        m.put(EmotionType.SATISFACTION, posBas);
        m.put(EmotionType.GRATIFICATION, posBas);
        m.put(EmotionType.PRIDE, posBas);
        m.put(EmotionType.ADMIRATION, posBas);

        var negBis = new ReinforcementSignal(-1.0, Set.of("bis"));
        m.put(EmotionType.DISTRESS, negBis);
        m.put(EmotionType.DISAPPOINTMENT, negBis);

        var negCompliance = new ReinforcementSignal(-1.0, Set.of("compliance"));
        m.put(EmotionType.SHAME, negCompliance);
        m.put(EmotionType.REPROACH, negCompliance);
        m.put(EmotionType.REMORSE, negCompliance);

        var negFight = new ReinforcementSignal(-1.0, Set.of("fight_assert"));
        m.put(EmotionType.ANGER, negFight);
        m.put(EmotionType.RESENTMENT, negFight);

        var posBasFawn = new ReinforcementSignal(1.0, Set.of("bas", "fawn_accommodate"));
        m.put(EmotionType.LOVE, posBasFawn);
        m.put(EmotionType.HAPPY_FOR, posBasFawn);
        m.put(EmotionType.GRATITUDE, posBasFawn);

        var negThreat = new ReinforcementSignal(-1.0, Set.of("threat"));
        m.put(EmotionType.HATE, negThreat);
        m.put(EmotionType.GLOATING, negThreat);

        m.put(EmotionType.PITY, new ReinforcementSignal(1.0, Set.of("fawn_accommodate")));

        MAPPINGS = Map.copyOf(m);
    }

    private EmotionReinforcementMapper() {}

    public static ReinforcementSignal forEmotion(EmotionType type) {
        var signal = MAPPINGS.get(type);
        if (signal == null) {
            throw new IllegalArgumentException("No reinforcement mapping for: " + type);
        }
        return signal;
    }
}
