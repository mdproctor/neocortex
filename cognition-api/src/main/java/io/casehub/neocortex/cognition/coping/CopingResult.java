package io.casehub.neocortex.cognition.coping;

import java.util.List;
import java.util.Objects;

public record CopingResult(
    CopingStrategy strategy,
    String narrative,
    List<GoalOperation> goalOperations,
    List<DriveModulation> driveModulations,
    List<AttentionDirective> attentionDirectives
) {
    public CopingResult {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(narrative, "narrative");
        goalOperations = goalOperations != null ? List.copyOf(goalOperations) : List.of();
        driveModulations = driveModulations != null ? List.copyOf(driveModulations) : List.of();
        attentionDirectives = attentionDirectives != null ? List.copyOf(attentionDirectives) : List.of();
    }
}
