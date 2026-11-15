package io.casehub.neocortex.cognition.coping;

import io.casehub.neocortex.cognitive.CognitiveEmotion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record CopingState(
    String interpretationNarrative,
    Map<String, Double> secDimensions,
    List<CognitiveEmotion> workingEmotions,
    int iteration,
    List<GoalOperation> goalOperations,
    List<DriveModulation> driveModulations,
    List<AttentionDirective> attentionDirectives,
    List<CopingStrategyAttempt> strategiesAttempted
) {

    public CopingState {
        secDimensions = secDimensions != null ? Map.copyOf(secDimensions) : Map.of();
        workingEmotions = workingEmotions != null ? List.copyOf(workingEmotions) : List.of();
        goalOperations = goalOperations != null ? List.copyOf(goalOperations) : List.of();
        driveModulations = driveModulations != null ? List.copyOf(driveModulations) : List.of();
        attentionDirectives = attentionDirectives != null ? List.copyOf(attentionDirectives) : List.of();
        strategiesAttempted = strategiesAttempted != null ? List.copyOf(strategiesAttempted) : List.of();
    }

    public static CopingState initial(String narrative, Map<String, Double> secDimensions,
                                      List<CognitiveEmotion> emotions) {
        return new CopingState(narrative, Map.copyOf(secDimensions), List.copyOf(emotions),
            0, List.of(), List.of(), List.of(), List.of());
    }

    public CopingState withInterpretation(String narrative) {
        return new CopingState(narrative, secDimensions, workingEmotions, iteration,
            goalOperations, driveModulations, attentionDirectives, strategiesAttempted);
    }

    public CopingState withDimensions(Map<String, Double> dims) {
        return new CopingState(interpretationNarrative, Map.copyOf(dims), workingEmotions, iteration,
            goalOperations, driveModulations, attentionDirectives, strategiesAttempted);
    }

    public CopingState withEmotions(List<CognitiveEmotion> emotions) {
        return new CopingState(interpretationNarrative, secDimensions, List.copyOf(emotions), iteration,
            goalOperations, driveModulations, attentionDirectives, strategiesAttempted);
    }

    public CopingState withIteration(int iter) {
        return new CopingState(interpretationNarrative, secDimensions, workingEmotions, iter,
            goalOperations, driveModulations, attentionDirectives, strategiesAttempted);
    }

    public CopingState withAccumulatedGoalOp(GoalOperation op) {
        var ops = new ArrayList<>(goalOperations);
        ops.add(op);
        return new CopingState(interpretationNarrative, secDimensions, workingEmotions, iteration,
            Collections.unmodifiableList(ops), driveModulations, attentionDirectives, strategiesAttempted);
    }

    public CopingState withAccumulatedDriveMod(DriveModulation mod) {
        var mods = new ArrayList<>(driveModulations);
        mods.add(mod);
        return new CopingState(interpretationNarrative, secDimensions, workingEmotions, iteration,
            goalOperations, Collections.unmodifiableList(mods), attentionDirectives, strategiesAttempted);
    }

    public CopingState withAccumulatedAttentionDirective(AttentionDirective dir) {
        var dirs = new ArrayList<>(attentionDirectives);
        dirs.add(dir);
        return new CopingState(interpretationNarrative, secDimensions, workingEmotions, iteration,
            goalOperations, driveModulations, Collections.unmodifiableList(dirs), strategiesAttempted);
    }

    public CopingState withStrategy(CopingStrategyAttempt attempt) {
        var attempts = new ArrayList<>(strategiesAttempted);
        attempts.add(attempt);
        return new CopingState(interpretationNarrative, secDimensions, workingEmotions, iteration,
            goalOperations, driveModulations, attentionDirectives, Collections.unmodifiableList(attempts));
    }

    public CopingState withAccumulatedEffects(CopingResult result) {
        var state = this;
        for (var op : result.goalOperations()) {
            state = state.withAccumulatedGoalOp(op);
        }
        for (var mod : result.driveModulations()) {
            state = state.withAccumulatedDriveMod(mod);
        }
        for (var dir : result.attentionDirectives()) {
            state = state.withAccumulatedAttentionDirective(dir);
        }
        return state;
    }

    public boolean hasAttempted(CopingStrategy strategy) {
        return strategiesAttempted.stream().anyMatch(a -> a.strategy() == strategy);
    }
}
