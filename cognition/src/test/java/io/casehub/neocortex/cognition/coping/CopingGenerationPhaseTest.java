package io.casehub.neocortex.cognition.coping;

import io.casehub.neocortex.cognition.appraisal.AppraisalContext;
import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.cognition.appraisal.PerceivedSituation;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.HabituationConfig;
import io.casehub.neocortex.cognitive.PadProjection;
import io.casehub.neocortex.cognitive.index.CopingDefaults;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CopingGenerationPhaseTest {

    private static final Map<String, Double> SEC_DIMS = Map.of(
            "controllability", 0.7,
            "adjustability", 0.5,
            "relevance", 0.8);

    private static final CognitiveEmotion ANGER = emotion(EmotionType.ANGER, 0.65);
    private static final CognitiveEmotion FEAR = emotion(EmotionType.FEAR, 0.55);

    private static final DispositionAxes AXES =
            new DispositionAxes("cooperative", "flexible", "calculated", "high", "analytical");

    private static final CopingDefaults DEFAULTS =
            new CopingDefaults(0.8, 0.5, 0.2, 0.4, 0.6);

    private static CognitiveEmotion emotion(EmotionType type, double intensity) {
        return new CognitiveEmotion(type, intensity, "test-agent",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
    }

    private static CopingState basicState() {
        return CopingState.initial("Someone blocked the doorway and won't move.", SEC_DIMS, List.of(ANGER));
    }

    private static AppraisalContext basicAppraisalContext() {
        return new AppraisalContext("agent-1",
                PerceivedSituation.passThrough("Someone blocked the doorway"),
                List.of(), null,
                HabituationConfig.defaults(), HabituationState.empty(),
                null, AXES);
    }

    // --- parseResponse tests ---

    @Test
    void parseResponse_validPlanningResponse() throws Exception {
        var json = """
                {
                  "strategy": "PLANNING",
                  "narrative": "I can find another route around the blockage.",
                  "goalOperations": [
                    {"type": "CREATE", "goalName": "find-alternate-route",
                     "goalDescription": "Locate an alternative path",
                     "axis": "COMPETENCE", "formationReason": "Blocked doorway requires problem solving"}
                  ],
                  "driveModulations": [],
                  "attentionDirectives": []
                }
                """;
        var result = CopingGenerationPhase.parseResponse(json);

        assertThat(result).isNotNull();
        assertThat(result.strategy()).isEqualTo(CopingStrategy.PLANNING);
        assertThat(result.narrative()).contains("another route");
        assertThat(result.goalOperations()).hasSize(1);
        assertThat(result.goalOperations().getFirst().type()).isEqualTo(GoalOperationType.CREATE);
        assertThat(result.goalOperations().getFirst().axis()).isEqualTo(DriveAxis.COMPETENCE);
        assertThat(result.driveModulations()).isEmpty();
        assertThat(result.attentionDirectives()).isEmpty();
    }

    @Test
    void parseResponse_mentalDisengagementWithDriveModulation() throws Exception {
        var json = """
                {
                  "strategy": "MENTAL_DISENGAGEMENT",
                  "narrative": "This situation isn't worth getting upset about.",
                  "goalOperations": [],
                  "driveModulations": [
                    {"axis": "COMPETENCE", "intensityDelta": -0.2, "reason": "Reducing investment in proving competence here"}
                  ],
                  "attentionDirectives": []
                }
                """;
        var result = CopingGenerationPhase.parseResponse(json);

        assertThat(result).isNotNull();
        assertThat(result.strategy()).isEqualTo(CopingStrategy.MENTAL_DISENGAGEMENT);
        assertThat(result.driveModulations()).hasSize(1);
        assertThat(result.driveModulations().getFirst().intensityDelta()).isEqualTo(-0.2);
        assertThat(result.driveModulations().getFirst().axis()).isEqualTo(DriveAxis.COMPETENCE);
    }

    @Test
    void parseResponse_seekInformationWithAttentionDirective() throws Exception {
        var json = """
                {
                  "strategy": "SEEK_INFORMATION",
                  "narrative": "I need to understand why the door is blocked.",
                  "goalOperations": [],
                  "driveModulations": [],
                  "attentionDirectives": [
                    {"type": "FOCUS", "target": "cause of the blockage", "reason": "Need clarity on the situation"}
                  ]
                }
                """;
        var result = CopingGenerationPhase.parseResponse(json);

        assertThat(result).isNotNull();
        assertThat(result.strategy()).isEqualTo(CopingStrategy.SEEK_INFORMATION);
        assertThat(result.attentionDirectives()).hasSize(1);
        assertThat(result.attentionDirectives().getFirst().type()).isEqualTo(AttentionDirectiveType.FOCUS);
    }

    @Test
    void parseResponse_resignationWithDropGoal() throws Exception {
        var json = """
                {
                  "strategy": "RESIGNATION",
                  "narrative": "There's nothing I can do about this. I'm trapped.",
                  "goalOperations": [
                    {"type": "DROP", "goalName": "reach-destination",
                     "goalDescription": "Get through the doorway",
                     "axis": "AUTONOMY", "formationReason": "Perceived as uncontrollable"}
                  ],
                  "driveModulations": [],
                  "attentionDirectives": []
                }
                """;
        var result = CopingGenerationPhase.parseResponse(json);

        assertThat(result).isNotNull();
        assertThat(result.strategy()).isEqualTo(CopingStrategy.RESIGNATION);
        assertThat(result.goalOperations().getFirst().type()).isEqualTo(GoalOperationType.DROP);
    }

    @Test
    void parseResponse_invalidStrategy_returnsNull() throws Exception {
        var json = """
                {"strategy": "NOT_A_STRATEGY", "narrative": "something", "goalOperations": [], "driveModulations": [], "attentionDirectives": []}
                """;
        assertThat(CopingGenerationPhase.parseResponse(json)).isNull();
    }

    @Test
    void parseResponse_missingNarrative_returnsNull() throws Exception {
        var json = """
                {"strategy": "PLANNING", "goalOperations": [], "driveModulations": [], "attentionDirectives": []}
                """;
        assertThat(CopingGenerationPhase.parseResponse(json)).isNull();
    }

    @Test
    void parseResponse_blankNarrative_returnsNull() throws Exception {
        var json = """
                {"strategy": "PLANNING", "narrative": "  ", "goalOperations": [], "driveModulations": [], "attentionDirectives": []}
                """;
        assertThat(CopingGenerationPhase.parseResponse(json)).isNull();
    }

    @Test
    void parseResponse_missingStrategy_returnsNull() throws Exception {
        var json = """
                {"narrative": "something", "goalOperations": [], "driveModulations": [], "attentionDirectives": []}
                """;
        assertThat(CopingGenerationPhase.parseResponse(json)).isNull();
    }

    @Test
    void parseResponse_noJson_returnsNull() throws Exception {
        assertThat(CopingGenerationPhase.parseResponse("No JSON here")).isNull();
    }

    @Test
    void parseResponse_jsonWithSurroundingText() throws Exception {
        var response = """
                Here is the coping response:
                {"strategy": "DENIAL", "narrative": "There is no problem here.",
                 "goalOperations": [], "driveModulations": [], "attentionDirectives": []}
                That's my answer.
                """;
        var result = CopingGenerationPhase.parseResponse(response);
        assertThat(result).isNotNull();
        assertThat(result.strategy()).isEqualTo(CopingStrategy.DENIAL);
    }

    @Test
    void parseResponse_missingOptionalArrays_defaultsToEmpty() throws Exception {
        var json = """
                {"strategy": "POSITIVE_REINTERPRETATION", "narrative": "This is actually an opportunity to grow."}
                """;
        var result = CopingGenerationPhase.parseResponse(json);
        assertThat(result).isNotNull();
        assertThat(result.goalOperations()).isEmpty();
        assertThat(result.driveModulations()).isEmpty();
        assertThat(result.attentionDirectives()).isEmpty();
    }

    @Test
    void parseResponse_invalidGoalOpType_skipsEntry() throws Exception {
        var json = """
                {
                  "strategy": "PLANNING",
                  "narrative": "I'll work around it.",
                  "goalOperations": [
                    {"type": "INVALID_TYPE", "goalName": "x", "axis": "COMPETENCE"},
                    {"type": "CREATE", "goalName": "valid-goal", "goalDescription": "ok", "axis": "COMPETENCE", "formationReason": "yes"}
                  ],
                  "driveModulations": [],
                  "attentionDirectives": []
                }
                """;
        var result = CopingGenerationPhase.parseResponse(json);
        assertThat(result).isNotNull();
        assertThat(result.goalOperations()).hasSize(1);
        assertThat(result.goalOperations().getFirst().goalName()).isEqualTo("valid-goal");
    }

    @Test
    void parseResponse_procrastinationWithDeferGoal() throws Exception {
        var json = """
                {
                  "strategy": "PROCRASTINATION",
                  "narrative": "I'll deal with this later when I have more energy.",
                  "goalOperations": [
                    {"type": "DEFER", "goalName": "resolve-conflict",
                     "goalDescription": "Address the doorway situation",
                     "axis": "AUTONOMY", "formationReason": "Insufficient energy to confront now"}
                  ],
                  "driveModulations": [],
                  "attentionDirectives": []
                }
                """;
        var result = CopingGenerationPhase.parseResponse(json);
        assertThat(result).isNotNull();
        assertThat(result.strategy()).isEqualTo(CopingStrategy.PROCRASTINATION);
        assertThat(result.goalOperations()).hasSize(1);
        assertThat(result.goalOperations().getFirst().type()).isEqualTo(GoalOperationType.DEFER);
    }

    @Test
    void parseResponse_goalOpMissingAxis_skipsEntry() throws Exception {
        var json = """
                {
                  "strategy": "PLANNING",
                  "narrative": "I have a plan.",
                  "goalOperations": [
                    {"type": "CREATE", "goalName": "no-axis-goal", "goalDescription": "missing axis"},
                    {"type": "CREATE", "goalName": "valid-goal", "goalDescription": "ok", "axis": "AFFILIATION", "formationReason": "yes"}
                  ],
                  "driveModulations": [],
                  "attentionDirectives": []
                }
                """;
        var result = CopingGenerationPhase.parseResponse(json);
        assertThat(result).isNotNull();
        assertThat(result.goalOperations()).hasSize(1);
        assertThat(result.goalOperations().getFirst().goalName()).isEqualTo("valid-goal");
        assertThat(result.goalOperations().getFirst().axis()).isEqualTo(DriveAxis.AFFILIATION);
    }

    @Test
    void parseResponse_invalidDriveAxis_skipsModulation() throws Exception {
        var json = """
                {
                  "strategy": "ACCEPTANCE",
                  "narrative": "I accept this.",
                  "goalOperations": [],
                  "driveModulations": [
                    {"axis": "BOGUS", "intensityDelta": -0.1, "reason": "bad axis"},
                    {"axis": "AFFILIATION", "intensityDelta": -0.15, "reason": "valid"}
                  ],
                  "attentionDirectives": []
                }
                """;
        var result = CopingGenerationPhase.parseResponse(json);
        assertThat(result).isNotNull();
        assertThat(result.driveModulations()).hasSize(1);
        assertThat(result.driveModulations().getFirst().axis()).isEqualTo(DriveAxis.AFFILIATION);
    }

    @Test
    void parseResponse_multipleGoalOperations() throws Exception {
        var json = """
                {
                  "strategy": "PLANNING",
                  "narrative": "I have several steps to take.",
                  "goalOperations": [
                    {"type": "CREATE", "goalName": "step-one", "goalDescription": "First step", "axis": "COMPETENCE", "formationReason": "start"},
                    {"type": "DEFER", "goalName": "low-priority", "goalDescription": "Later", "axis": "CURIOSITY", "formationReason": "not now"}
                  ],
                  "driveModulations": [],
                  "attentionDirectives": []
                }
                """;
        var result = CopingGenerationPhase.parseResponse(json);
        assertThat(result).isNotNull();
        assertThat(result.goalOperations()).hasSize(2);
        assertThat(result.goalOperations().get(0).type()).isEqualTo(GoalOperationType.CREATE);
        assertThat(result.goalOperations().get(1).type()).isEqualTo(GoalOperationType.DEFER);
    }

    // --- buildUserMessage tests ---

    @Test
    void buildUserMessage_includesPinnedStrategy() {
        var msg = CopingGenerationPhase.buildUserMessage(
                basicAppraisalContext(), basicState(), CopingStrategy.PLANNING, null);
        assertThat(msg).contains("Pinned strategy: PLANNING");
        assertThat(msg).contains("intention strategy");
    }

    @Test
    void buildUserMessage_includesSituationNarrative() {
        var msg = CopingGenerationPhase.buildUserMessage(
                basicAppraisalContext(), basicState(), null, null);
        assertThat(msg).contains("Someone blocked the doorway");
    }

    @Test
    void buildUserMessage_includesControllabilityAnnotation_high() {
        var msg = CopingGenerationPhase.buildUserMessage(
                basicAppraisalContext(), basicState(), null, null);
        assertThat(msg).contains("controllability: 0.70");
        assertThat(msg).contains("situation can be changed");
    }

    @Test
    void buildUserMessage_includesControllabilityAnnotation_low() {
        var lowCtrl = CopingState.initial("Stuck.", Map.of("controllability", 0.15), List.of(FEAR));
        var msg = CopingGenerationPhase.buildUserMessage(
                basicAppraisalContext(), lowCtrl, null, null);
        assertThat(msg).contains("situation is fixed");
    }

    @Test
    void buildUserMessage_includesEmotions() {
        var msg = CopingGenerationPhase.buildUserMessage(
                basicAppraisalContext(), basicState(), null, null);
        assertThat(msg).contains("ANGER");
        assertThat(msg).contains("intensity=0.65");
    }

    @Test
    void buildUserMessage_includesPersonalityAxes() {
        var msg = CopingGenerationPhase.buildUserMessage(
                basicAppraisalContext(), basicState(), null, null);
        assertThat(msg).contains("conflict-mode: analytical");
        assertThat(msg).contains("social-orient: cooperative");
    }

    @Test
    void buildUserMessage_includesCopingDefaults() {
        var msg = CopingGenerationPhase.buildUserMessage(
                basicAppraisalContext(), basicState(), null, DEFAULTS);
        assertThat(msg).contains("planful: strong (0.80)");
        assertThat(msg).contains("avoidance: minimal (0.20)");
        assertThat(msg).contains("Connor-Smith");
    }

    @Test
    void buildUserMessage_includesIterationContext() {
        var state = basicState()
                .withStrategy(new CopingStrategyAttempt(CopingStrategy.DENIAL, 0, "It's fine"))
                .withIteration(1);
        var msg = CopingGenerationPhase.buildUserMessage(
                basicAppraisalContext(), state, null, null);
        assertThat(msg).contains("Micro-loop iteration: 1");
        assertThat(msg).contains("Previously attempted strategies");
        assertThat(msg).contains("DENIAL");
        assertThat(msg).contains("DIFFERENT strategy");
    }

    @Test
    void buildUserMessage_noPinnedStrategy_omitsPinnedSection() {
        var msg = CopingGenerationPhase.buildUserMessage(
                basicAppraisalContext(), basicState(), null, null);
        assertThat(msg).doesNotContain("Pinned strategy");
    }

    @Test
    void buildUserMessage_iteration0_omitsIterationSection() {
        var msg = CopingGenerationPhase.buildUserMessage(
                basicAppraisalContext(), basicState(), null, null);
        assertThat(msg).doesNotContain("Micro-loop iteration");
    }

    // --- Constructor with null pipeline ---

    @Test
    void generate_nullPipeline_returnsNull() {
        var phase = new CopingGenerationPhase(null);
        var result = phase.generate(basicAppraisalContext(), basicState(), null);
        assertThat(result).isNull();
    }
}
