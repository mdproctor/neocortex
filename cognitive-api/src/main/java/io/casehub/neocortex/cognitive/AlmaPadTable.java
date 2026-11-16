package io.casehub.neocortex.cognitive;

import java.util.EnumMap;
import java.util.Map;

public final class AlmaPadTable {

    private static final Map<EmotionType, PadProjection> TABLE = new EnumMap<>(EmotionType.class);

    static {
        TABLE.put(EmotionType.ADMIRATION,      new PadProjection( 0.5,   0.3,  -0.2));
        TABLE.put(EmotionType.ANGER,           new PadProjection(-0.51,  0.59,  0.25));
        TABLE.put(EmotionType.DISAPPOINTMENT,  new PadProjection(-0.3,   0.1,  -0.4));
        TABLE.put(EmotionType.DISTRESS,        new PadProjection(-0.4,  -0.2,  -0.5));
        TABLE.put(EmotionType.FEAR,            new PadProjection(-0.64,  0.60, -0.43));
        TABLE.put(EmotionType.FEARS_CONFIRMED, new PadProjection(-0.5,  -0.3,  -0.7));
        TABLE.put(EmotionType.GLOATING,        new PadProjection( 0.3,  -0.3,  -0.1));
        TABLE.put(EmotionType.GRATIFICATION,   new PadProjection( 0.6,   0.5,   0.4));
        TABLE.put(EmotionType.GRATITUDE,       new PadProjection( 0.4,   0.2,  -0.3));
        TABLE.put(EmotionType.HAPPY_FOR,       new PadProjection( 0.4,   0.2,   0.2));
        TABLE.put(EmotionType.HATE,            new PadProjection(-0.6,   0.6,   0.3));
        TABLE.put(EmotionType.HOPE,            new PadProjection( 0.2,   0.2,  -0.1));
        TABLE.put(EmotionType.INTEREST,         new PadProjection( 0.3,   0.4,  -0.1));
        TABLE.put(EmotionType.JOY,             new PadProjection( 0.4,   0.2,   0.1));
        TABLE.put(EmotionType.LOVE,            new PadProjection( 0.4,   0.16, -0.24));
        TABLE.put(EmotionType.PITY,            new PadProjection(-0.4,  -0.2,  -0.5));
        TABLE.put(EmotionType.PRIDE,           new PadProjection( 0.4,   0.3,   0.3));
        TABLE.put(EmotionType.RELIEF,          new PadProjection( 0.2,  -0.3,  -0.4));
        TABLE.put(EmotionType.REMORSE,         new PadProjection(-0.3,   0.1,  -0.6));
        TABLE.put(EmotionType.REPROACH,        new PadProjection(-0.3,  -0.1,   0.4));
        TABLE.put(EmotionType.RESENTMENT,      new PadProjection(-0.2,  -0.3,  -0.2));
        TABLE.put(EmotionType.SATISFACTION,    new PadProjection( 0.3,  -0.2,   0.4));
        TABLE.put(EmotionType.SHAME,           new PadProjection(-0.3,   0.1,  -0.6));
    }

    private AlmaPadTable() {}

    public static PadProjection lookup(EmotionType type) {
        var pad = TABLE.get(type);
        if (pad == null) throw new IllegalArgumentException("No PAD mapping for " + type);
        return pad;
    }

    public static PadProjection project(EmotionType type, double intensity) {
        var base = lookup(type);
        return new PadProjection(
                base.pleasure() * intensity,
                base.arousal() * intensity,
                base.dominance() * intensity
        );
    }
}
