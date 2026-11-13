package io.casehub.neocortex.caps.llm;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DispositionTension {

    public record TensionEntry(String axis1, String value1,
                               String axis2, String value2,
                               double score, String conflict,
                               String narrative) {}

    private static final List<TensionEntry> TABLE = List.of(
        new TensionEntry("riskAppetite", "bold", "ruleFollowing", "strict",
            1.0, "approach vs inhibition",
            "wants to act decisively but feels bound by rules — ambiguous situations feel like tests of whether the rules permit the action"),
        new TensionEntry("autonomy", "high", "conflictMode", "avoidant",
            0.9, "independent but won't confront",
            "values independence but avoids confrontation — perceives social expectations as constraints but won't push against them directly"),
        new TensionEntry("socialOrient", "cooperative", "autonomy", "high",
            0.8, "group-oriented but self-directed",
            "drawn to group goals but resistant to group control — reads situations for both collaboration potential and threats to independence"),
        new TensionEntry("riskAppetite", "bold", "conflictMode", "avoidant",
            0.7, "acts but doesn't confront",
            "impulsive toward action but shrinks from interpersonal friction — sees opportunities but worries about the social cost of seizing them"),
        new TensionEntry("socialOrient", "independent", "autonomy", "low",
            0.7, "independent identity but follows",
            "identifies as self-reliant but defers to authority — notices when others lead but feels conflicted about following"),
        new TensionEntry("socialOrient", "competitive", "ruleFollowing", "strict",
            0.6, "driven to win but bound by rules",
            "driven to win but constrained by propriety — seeks advantage within the rules, alert to where rules create or block competitive edges")
    );

    private DispositionTension() {}

    public static double maxTension(Map<String, String> metadata) {
        return topTension(metadata).map(TensionEntry::score).orElse(0.0);
    }

    public static Optional<TensionEntry> topTension(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return Optional.empty();

        TensionEntry best = null;
        for (TensionEntry entry : TABLE) {
            String v1 = metadata.get("disposition." + entry.axis1);
            String v2 = metadata.get("disposition." + entry.axis2);
            if (entry.value1.equals(v1) && entry.value2.equals(v2)) {
                if (best == null || entry.score > best.score) {
                    best = entry;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
