package io.casehub.neocortex.cognition.coping;

import io.casehub.neocortex.cognition.drive.DriveAxis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CopingStateTest {

    @Test
    void shouldCreateInitialState() {
        var state = CopingState.initial("raw situation",
            Map.of("CONTROLLABILITY", 0.5), List.of());
        assertEquals("raw situation", state.interpretationNarrative());
        assertEquals(0, state.iteration());
        assertEquals(0.5, state.secDimensions().get("CONTROLLABILITY"), 0.001);
        assertTrue(state.goalOperations().isEmpty());
        assertTrue(state.driveModulations().isEmpty());
        assertTrue(state.attentionDirectives().isEmpty());
        assertTrue(state.strategiesAttempted().isEmpty());
    }

    @Test
    void withInterpretationShouldProduceNewInstance() {
        var state = CopingState.initial("raw", Map.of(), List.of());
        var next = state.withInterpretation("reinterpreted");
        assertEquals("raw", state.interpretationNarrative());
        assertEquals("reinterpreted", next.interpretationNarrative());
    }

    @Test
    void withIterationShouldProduceNewInstance() {
        var state = CopingState.initial("raw", Map.of(), List.of());
        var next = state.withIteration(1);
        assertEquals(0, state.iteration());
        assertEquals(1, next.iteration());
    }

    @Test
    void withDimensionsShouldReplaceAll() {
        var state = CopingState.initial("raw", Map.of("A", 0.1), List.of());
        var next = state.withDimensions(Map.of("B", 0.9));
        assertEquals(0.1, state.secDimensions().get("A"), 0.001);
        assertNull(next.secDimensions().get("A"));
        assertEquals(0.9, next.secDimensions().get("B"), 0.001);
    }

    @Test
    void shouldAccumulateGoalOperations() {
        var state = CopingState.initial("raw", Map.of(), List.of());
        var op = new GoalOperation(GoalOperationType.CREATE, "plan",
            "plan desc", DriveAxis.COMPETENCE, "coping");
        var next = state.withAccumulatedGoalOp(op);
        assertEquals(1, next.goalOperations().size());
        assertTrue(state.goalOperations().isEmpty());
    }

    @Test
    void shouldAccumulateDriveModulations() {
        var state = CopingState.initial("raw", Map.of(), List.of());
        var mod = new DriveModulation(DriveAxis.AFFILIATION, -0.3, "disengagement");
        var next = state.withAccumulatedDriveMod(mod);
        assertEquals(1, next.driveModulations().size());
        assertTrue(state.driveModulations().isEmpty());
    }

    @Test
    void shouldAccumulateAttentionDirectives() {
        var state = CopingState.initial("raw", Map.of(), List.of());
        var dir = new AttentionDirective(AttentionDirectiveType.FOCUS, "threat", "monitor");
        var next = state.withAccumulatedAttentionDirective(dir);
        assertEquals(1, next.attentionDirectives().size());
        assertTrue(state.attentionDirectives().isEmpty());
    }

    @Test
    void shouldTrackStrategiesAttempted() {
        var state = CopingState.initial("raw", Map.of(), List.of());
        var attempt = new CopingStrategyAttempt(CopingStrategy.PLANNING, 0, "reinterpreted");
        var next = state.withStrategy(attempt);
        assertEquals(1, next.strategiesAttempted().size());
        assertTrue(state.strategiesAttempted().isEmpty());
    }

    @Test
    void hasAttemptedShouldDetectPriorStrategies() {
        var state = CopingState.initial("raw", Map.of(), List.of());
        var attempt = new CopingStrategyAttempt(CopingStrategy.PLANNING, 0, "r1");
        var next = state.withStrategy(attempt);
        assertTrue(next.hasAttempted(CopingStrategy.PLANNING));
        assertFalse(next.hasAttempted(CopingStrategy.DENIAL));
    }

    @Test
    void withAccumulatedEffects_appliesAllEffectsFromCopingResult() {
        var state = CopingState.initial("raw", Map.of(), List.of());
        var result = new CopingResult(CopingStrategy.PLANNING, "reinterpreted",
                List.of(new GoalOperation(GoalOperationType.CREATE, "plan", "desc",
                        DriveAxis.COMPETENCE, "reason")),
                List.of(new DriveModulation(DriveAxis.AFFILIATION, -0.2, "disengage")),
                List.of(new AttentionDirective(AttentionDirectiveType.FOCUS, "threat", "monitor")));
        var next = state.withAccumulatedEffects(result);
        assertEquals(1, next.goalOperations().size());
        assertEquals(1, next.driveModulations().size());
        assertEquals(1, next.attentionDirectives().size());
        assertTrue(state.goalOperations().isEmpty());
    }

    @Test
    void withAccumulatedEffects_emptyResult_noChange() {
        var state = CopingState.initial("raw", Map.of(), List.of());
        var result = new CopingResult(CopingStrategy.DENIAL, "nothing changed",
                List.of(), List.of(), List.of());
        var next = state.withAccumulatedEffects(result);
        assertTrue(next.goalOperations().isEmpty());
        assertTrue(next.driveModulations().isEmpty());
        assertTrue(next.attentionDirectives().isEmpty());
    }

    @Test
    void multipleAccumulationsShouldStack() {
        var state = CopingState.initial("raw", Map.of(), List.of());
        var op1 = new GoalOperation(GoalOperationType.CREATE, "a", "a", DriveAxis.COMPETENCE, "r");
        var op2 = new GoalOperation(GoalOperationType.DROP, "b", "b", DriveAxis.AUTONOMY, "r");
        var next = state.withAccumulatedGoalOp(op1).withAccumulatedGoalOp(op2);
        assertEquals(2, next.goalOperations().size());
        assertEquals(GoalOperationType.CREATE, next.goalOperations().get(0).type());
        assertEquals(GoalOperationType.DROP, next.goalOperations().get(1).type());
    }
}
