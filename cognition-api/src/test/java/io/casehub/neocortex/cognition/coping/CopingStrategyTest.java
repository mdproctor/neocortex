package io.casehub.neocortex.cognition.coping;

import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.regulation.EmotionRegulationStrategy;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CopingStrategyTest {

    @Test
    void shouldHave16Strategies() {
        assertEquals(16, CopingStrategy.values().length);
    }

    @Test
    void eachStrategyShouldHaveCategory() {
        for (CopingStrategy s : CopingStrategy.values()) {
            assertNotNull(s.category(), "Missing category for " + s);
        }
    }

    @Test
    void shouldCoverAllFourCategories() {
        var categories = EnumSet.noneOf(CopingCategory.class);
        for (CopingStrategy s : CopingStrategy.values()) {
            categories.add(s.category());
        }
        assertEquals(EnumSet.allOf(CopingCategory.class), categories);
    }

    @Test
    void regulationRoutedStrategiesShouldMapToRegulationStrategy() {
        assertSame(EmotionRegulationStrategy.COGNITIVE_CHANGE,
            CopingStrategy.POSITIVE_REINTERPRETATION.regulationStrategy().orElseThrow());
        assertSame(EmotionRegulationStrategy.SITUATION_SELECTION,
            CopingStrategy.AVOIDANCE.regulationStrategy().orElseThrow());
        assertSame(EmotionRegulationStrategy.SOCIAL_SUPPORT_SEEKING,
            CopingStrategy.SEEK_INSTRUMENTAL_SUPPORT.regulationStrategy().orElseThrow());
        assertSame(EmotionRegulationStrategy.SOCIAL_SHARING,
            CopingStrategy.SEEK_EMOTIONAL_SUPPORT.regulationStrategy().orElseThrow());
        assertSame(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT,
            CopingStrategy.MENTAL_DISENGAGEMENT.regulationStrategy().orElseThrow());
        assertSame(EmotionRegulationStrategy.RESPONSE_MODULATION,
            CopingStrategy.SUPPRESSION.regulationStrategy().orElseThrow());
        assertSame(EmotionRegulationStrategy.SITUATION_MODIFICATION,
            CopingStrategy.ACTIVE_CONFRONTATION.regulationStrategy().orElseThrow());
    }

    @Test
    void directEffectStrategiesShouldNotMapToRegulation() {
        var directEffects = Set.of(
            CopingStrategy.PLANNING, CopingStrategy.DENIAL,
            CopingStrategy.WISHFUL_THINKING, CopingStrategy.SHIFT_RESPONSIBILITY,
            CopingStrategy.RESIGNATION, CopingStrategy.PROCRASTINATION,
            CopingStrategy.ACCEPTANCE, CopingStrategy.SEEK_INFORMATION,
            CopingStrategy.SUPPRESS_INFORMATION);
        for (CopingStrategy s : directEffects) {
            assertTrue(s.regulationStrategy().isEmpty(), s + " should not map to regulation");
            assertFalse(s.isRegulationRouted(), s + " should not be regulation-routed");
        }
    }

    @Test
    void goalOperationTypeShouldHaveThreeValues() {
        assertEquals(3, GoalOperationType.values().length);
        assertNotNull(GoalOperationType.CREATE);
        assertNotNull(GoalOperationType.DROP);
        assertNotNull(GoalOperationType.DEFER);
    }

    @Test
    void goalOperationRecordShouldBeImmutable() {
        var op = new GoalOperation(GoalOperationType.CREATE, "plan-ahead",
            "Create a plan to address the situation", DriveAxis.COMPETENCE, "coping: planning");
        assertEquals(GoalOperationType.CREATE, op.type());
        assertEquals("plan-ahead", op.goalName());
        assertEquals(DriveAxis.COMPETENCE, op.axis());
    }

    @Test
    void driveModulationRecordShouldCarryDelta() {
        var mod = new DriveModulation(DriveAxis.AFFILIATION, -0.3, "mental disengagement");
        assertEquals(DriveAxis.AFFILIATION, mod.axis());
        assertEquals(-0.3, mod.intensityDelta(), 0.001);
    }

    @Test
    void attentionDirectiveTypeShouldHaveTwoValues() {
        assertEquals(2, AttentionDirectiveType.values().length);
        assertNotNull(AttentionDirectiveType.FOCUS);
        assertNotNull(AttentionDirectiveType.SUPPRESS);
    }

    @Test
    void copingPredictionShouldCarryConfidence() {
        var prediction = new CopingPrediction(CopingStrategy.PLANNING, 0.85, null);
        assertEquals(CopingStrategy.PLANNING, prediction.strategy());
        assertEquals(0.85, prediction.confidence(), 0.001);
        assertNull(prediction.familyLabel());
    }

    @Test
    void copingPredictionWithFamilyLabel() {
        var prediction = new CopingPrediction(CopingStrategy.PLANNING, 0.5, "intention-related");
        assertEquals("intention-related", prediction.familyLabel());
    }
}
