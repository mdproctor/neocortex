package io.casehub.neocortex.cognitive;

import java.util.Map;
import java.util.Optional;

public enum EmotionType {
    // Prospect-based (goal lifecycle)
    HOPE, FEAR, SATISFACTION, DISAPPOINTMENT, RELIEF, FEARS_CONFIRMED,
    // Well-being
    JOY, DISTRESS,
    // Epistemic (Silvia 2006 extension)
    INTEREST,
    // Fortunes of others
    HAPPY_FOR, PITY,
    // Attribution
    PRIDE, SHAME, REPROACH, ADMIRATION,
    // Compound
    GRATITUDE, ANGER, REMORSE, GRATIFICATION,
    // Object-based
    LOVE, HATE,
    // Fortunes-of-others (disliked)
    RESENTMENT, GLOATING;

    private static final Map<String, EmotionType> ALIASES = Map.ofEntries(
            Map.entry("sorry-for", PITY),
            Map.entry("despair", DISTRESS),
            Map.entry("sadness", DISTRESS),
            Map.entry("grief", DISTRESS),
            Map.entry("melancholy", DISTRESS),
            Map.entry("loss", DISTRESS),
            Map.entry("helplessness", DISTRESS),
            Map.entry("longing", DISTRESS),
            Map.entry("homesickness", DISTRESS),
            Map.entry("frustration", ANGER),
            Map.entry("indignation", ANGER),
            Map.entry("anxiety", FEAR),
            Map.entry("abandonment-anxiety", FEAR),
            Map.entry("betrayal", REPROACH),
            Map.entry("contempt", REPROACH),
            Map.entry("regret", REMORSE),
            Map.entry("guilt", REMORSE),
            Map.entry("curiosity", INTEREST),
            Map.entry("fascination", INTEREST),
            Map.entry("intrigue", INTEREST),
            Map.entry("sympathy", PITY),
            Map.entry("resolve", GRATIFICATION),
            Map.entry("jealousy", RESENTMENT),
            Map.entry("awe", ADMIRATION),
            Map.entry("inspiration", ADMIRATION),
            Map.entry("passion", LOVE),
            Map.entry("yearning", HOPE),
            Map.entry("courage", HOPE),
            Map.entry("determination", HOPE),
            Map.entry("defiance", PRIDE),
            Map.entry("creative-defiance", PRIDE)
                                                                         );

    public String canonicalName() {
        return name().toLowerCase().replace('_', '-');
    }

    public static Optional<EmotionType> normalise(String input) {
        if (input == null || input.isBlank()) {return Optional.empty();}
        String key = input.strip().toLowerCase().replace('_', '-');
        for (EmotionType t : values()) {
            if (t.canonicalName().equals(key)) {return Optional.of(t);}
        }
        return Optional.ofNullable(ALIASES.get(key));
    }
}
