package io.casehub.neocortex.cognition.appraisal;

import io.casehub.neocortex.cognitive.CognitiveEmotion;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Map;

public record AppraisalResult(
        List<CognitiveEmotion> emotions,
        List<ActionTendency> actionTendencies,
        HabituationState updatedHabituation,
        @Nullable String narrative,
        Map<String, Double> secDimensions) {

    public AppraisalResult {
        emotions = emotions != null ? List.copyOf(emotions) : List.of();
        actionTendencies = actionTendencies != null ? List.copyOf(actionTendencies) : List.of();
        if (updatedHabituation == null) updatedHabituation = HabituationState.empty();
        secDimensions = secDimensions != null ? Map.copyOf(secDimensions) : Map.of();
    }

    public AppraisalResult(List<CognitiveEmotion> emotions,
                           List<ActionTendency> actionTendencies,
                           HabituationState updatedHabituation,
                           @Nullable String narrative) {
        this(emotions, actionTendencies, updatedHabituation, narrative, Map.of());
    }

    public AppraisalResult(List<CognitiveEmotion> emotions,
                           List<ActionTendency> actionTendencies,
                           HabituationState updatedHabituation) {
        this(emotions, actionTendencies, updatedHabituation, null, Map.of());
    }

    public static AppraisalResult empty() {
        return new AppraisalResult(List.of(), List.of(), HabituationState.empty(), null, Map.of());
    }
}
