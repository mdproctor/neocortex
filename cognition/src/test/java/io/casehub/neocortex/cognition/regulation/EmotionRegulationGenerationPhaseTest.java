package io.casehub.neocortex.cognition.regulation;

import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.prompt.observation.RegulationTracker;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.PadProjection;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.session.ManagedSession;
import io.casehub.platform.agent.session.SessionPipeline;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmotionRegulationGenerationPhaseTest {

    private SessionPipeline sessionPipeline;
    private ManagedSession managedSession;
    private CognitionTickContext context;

    @BeforeEach
    void setUp() {
        sessionPipeline = mock(SessionPipeline.class);
        managedSession = mock(ManagedSession.class);
        when(sessionPipeline.openSession(eq("emotion-regulation"), any(AgentSessionInit.class)))
                .thenReturn(managedSession);
        context = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of());
    }

    @Test
    void parsesValidResponseModulationResult() {
        var json = """
                {
                    "strategy": "RESPONSE_MODULATION",
                    "targetEmotions": ["anger"],
                    "targetDrives": ["gloating"],
                    "peakOriginalIntensity": 0.85,
                    "regulatedIntensity": 0.4,
                    "effectiveness": 0.6,
                    "narrative": "You hold back the triumph"
                }
                """;
        when(managedSession.query(any())).thenReturn(
                Multi.createFrom().item(new AgentEvent.TextDelta(json)));

        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.85, "subject1",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var phase = new EmotionRegulationGenerationPhase(sessionPipeline);
        var result = phase.generate(context, appraisal, List.of());

        assertThat(result).isNotNull();
        assertThat(result.strategy()).isEqualTo(EmotionRegulationStrategy.RESPONSE_MODULATION);
        assertThat(result.targetEmotions()).containsExactly("anger");
        assertThat(result.targetDrives()).containsExactly("gloating");
        assertThat(result.peakOriginalIntensity()).isEqualTo(0.85);
        assertThat(result.regulatedIntensity()).isEqualTo(0.4);
        assertThat(result.effectiveness()).isEqualTo(0.6);
        assertThat(result.narrative()).isEqualTo("You hold back the triumph");
        assertThat(result.isEnvironmentalStrategy()).isFalse();
        assertThat(result.regulationAxis()).isNull();
        assertThat(result.environmentalDirective()).isNull();
    }

    @Test
    void parsesEnvironmentalStrategyWithAxisAndDirective() {
        var json = """
                {
                    "strategy": "SITUATION_SELECTION",
                    "targetEmotions": ["anxiety"],
                    "targetDrives": [],
                    "peakOriginalIntensity": 0.9,
                    "regulatedIntensity": 0.5,
                    "effectiveness": 0.7,
                    "narrative": "You decide to leave",
                    "regulationAxis": "AFFILIATION",
                    "environmentalDirective": "Avoid being alone with Muttley"
                }
                """;
        when(managedSession.query(any())).thenReturn(
                Multi.createFrom().item(new AgentEvent.TextDelta(json)));

        var emotion = new CognitiveEmotion(EmotionType.FEAR, 0.9, "subject1",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var phase = new EmotionRegulationGenerationPhase(sessionPipeline);
        var result = phase.generate(context, appraisal, List.of());

        assertThat(result).isNotNull();
        assertThat(result.strategy()).isEqualTo(EmotionRegulationStrategy.SITUATION_SELECTION);
        assertThat(result.isEnvironmentalStrategy()).isTrue();
        assertThat(result.regulationAxis()).isEqualTo(DriveAxis.AFFILIATION);
        assertThat(result.environmentalDirective()).isEqualTo("Avoid being alone with Muttley");
    }

    @Test
    void returnsNullOnEmptyResponse() {
        when(managedSession.query(any())).thenReturn(Multi.createFrom().empty());

        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.85, "subject1",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var phase = new EmotionRegulationGenerationPhase(sessionPipeline);
        var result = phase.generate(context, appraisal, List.of());

        assertThat(result).isNull();
    }

    @Test
    void returnsNullOnMalformedJson() {
        when(managedSession.query(any())).thenReturn(
                Multi.createFrom().item(new AgentEvent.TextDelta("not json at all")));

        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.85, "subject1",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var phase = new EmotionRegulationGenerationPhase(sessionPipeline);
        var result = phase.generate(context, appraisal, List.of());

        assertThat(result).isNull();
    }

    @Test
    void returnsNullWhenNoPipeline() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.85, "subject1",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var phase = new EmotionRegulationGenerationPhase(null);
        var result = phase.generate(context, appraisal, List.of());

        assertThat(result).isNull();
    }

    @Test
    void systemPromptContainsGrossStrategies() {
        assertThat(EmotionRegulationGenerationPhase.SYSTEM_PROMPT)
                .contains("SITUATION_SELECTION")
                .contains("SITUATION_MODIFICATION")
                .contains("ATTENTIONAL_DEPLOYMENT")
                .contains("COGNITIVE_CHANGE")
                .contains("RESPONSE_MODULATION");
    }

    @Test
    void systemPromptContainsPetterSelectionFactors() {
        assertThat(EmotionRegulationGenerationPhase.SYSTEM_PROMPT)
                .contains("intensity")
                .contains("cognitive");
    }

    @Test
    void userMessageIncludesCognitiveLoad() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.7, "rival",
                                           Instant.now(), EmotionSource.INTRINSIC, new PadProjection(-0.5, 0.6, 0.3));
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        var load      = new CognitiveLoadEstimate(CognitiveLoadLevel.HIGH, 3, 3, 2);

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.6, load, 0.0, null);

        assertThat(message).contains("Cognitive load: HIGH");
        assertThat(message).contains("drives=3");
        assertThat(message).contains("goals=3");
        assertThat(message).contains("ToM targets=2");
    }

    @Test
    void userMessageOmitsLoadWhenNull() {
        var emotion = new CognitiveEmotion(EmotionType.JOY, 0.5, "success",
                                           Instant.now(), EmotionSource.INTRINSIC, new PadProjection(0.5, 0.3, 0.4));
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.3, null, 0.0, null);

        assertThat(message).doesNotContain("Cognitive load");
    }

    @Test
    void systemPromptIncludesCognitiveLoadGuidance() {
        assertThat(EmotionRegulationGenerationPhase.SYSTEM_PROMPT)
                .contains("Cognitive load constrains strategy");
    }

    @Test
    void userMessageIncludesFatigueWhenPresent() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.7, "rival",
                                           Instant.now(), EmotionSource.INTRINSIC, new PadProjection(-0.5, 0.6, 0.3));
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.6, null, 0.45, null);

        assertThat(message).contains("Regulation fatigue: 45%");
        assertThat(message).contains("depleting capacity");
        assertThat(message).doesNotContain("breakdown");
    }

    @Test
    void userMessageWarnsOfBreakdownAtHighFatigue() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.7, "rival",
                                           Instant.now(), EmotionSource.INTRINSIC, new PadProjection(-0.5, 0.6, 0.3));
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.6, null, 0.85, null);

        assertThat(message).contains("reappraisal capacity is depleted");
        assertThat(message).contains("MUST choose ATTENTIONAL_DEPLOYMENT");
    }

    @Test
    void userMessageOmitsFatigueWhenNegligible() {
        var emotion = new CognitiveEmotion(EmotionType.JOY, 0.5, "success",
                                           Instant.now(), EmotionSource.INTRINSIC, new PadProjection(0.5, 0.3, 0.4));
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.3, null, 0.005, null);

        assertThat(message).doesNotContain("fatigue");
    }

    @Test
    void userMessageIncludesStrategyFailureSignal() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.7, "rival",
                                           Instant.now(), EmotionSource.INTRINSIC, new PadProjection(-0.5, 0.6, 0.3));
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        var signal = new StrategyFailureSignal(
                EmotionRegulationStrategy.RESPONSE_MODULATION, 3, 0.7, 0.3, false);

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null, 0.0, signal);

        assertThat(message).contains("STRATEGY FAILING");
        assertThat(message).contains("RESPONSE_MODULATION");
        assertThat(message).contains("0.70");
        assertThat(message).contains("0.30");
        assertThat(message).contains("3 ticks");
    }

    @Test
    void userMessageIncludesBreakthroughSignal() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.7, "rival",
                                           Instant.now(), EmotionSource.INTRINSIC, new PadProjection(-0.5, 0.6, 0.3));
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        var signal = new StrategyFailureSignal(
                EmotionRegulationStrategy.RESPONSE_MODULATION, 5, 0.7, 0.05, true);

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null, 0.0, signal);

        assertThat(message).contains("BREAKTHROUGH");
        assertThat(message).contains("breaking through");
        assertThat(message).contains("different strategy");
    }

    @Test
    void userMessageIncludesDispositionAxesWhenProvided() {
        var emotion = new CognitiveEmotion(EmotionType.SHAME, 0.6, "self",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        var axes      = new DispositionAxes("cooperative", "moderate", "conservative", "low", "avoidant");

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null, 0.0, null, null, axes, EmotionType.SHAME);

        assertThat(message).contains("Personality disposition");
        assertThat(message).contains("predictor did not pin a strategy");
        assertThat(message).contains("conflict-mode: avoidant");
        assertThat(message).contains("social-orient: cooperative");
        assertThat(message).contains("risk-appetite: conservative");
        assertThat(message).contains("autonomy: low");
        assertThat(message).contains("rule-following: moderate");
    }

    @Test
    void userMessageOmitsAxesWhenStrategyIsPinned() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.5, "rival",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        var axes      = new DispositionAxes("cooperative", "moderate", "conservative", "low", "avoidant");

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null, 0.0, null,
                EmotionRegulationStrategy.RESPONSE_MODULATION, axes, EmotionType.ANGER);

        assertThat(message).doesNotContain("Personality disposition");
        assertThat(message).contains("Strategy: RESPONSE_MODULATION");
    }

    @Test
    void userMessageOmitsAxesWhenNull() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.5, "rival",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null, 0.0, null, null, null, EmotionType.ANGER);

        assertThat(message).doesNotContain("Personality disposition");
    }

    @Test
    void userMessageIncludesSelfConsciousGuidanceForShame() {
        var emotion = new CognitiveEmotion(EmotionType.SHAME, 0.6, "self",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        var axes      = new DispositionAxes("cooperative", "moderate", "conservative", "low", "confronting");

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null, 0.0, null, null, axes, EmotionType.SHAME);

        assertThat(message).contains("Self-conscious emotion guidance");
        assertThat(message).contains("self-evaluative");
        assertThat(message).contains("Temporal containment");
        assertThat(message).contains("Webb et al. 2012");
    }

    @Test
    void userMessageIncludesAvoidantShameResponseForAvoidantAxes() {
        var emotion = new CognitiveEmotion(EmotionType.SHAME, 0.6, "self",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        var axes      = new DispositionAxes("cooperative", "moderate", "conservative", "low", "avoidant");

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null, 0.0, null, null, axes, EmotionType.SHAME);

        assertThat(message).contains("Avoidant shame response");
        assertThat(message).contains("Nathanson 1992");
        assertThat(message).contains("SITUATION_SELECTION");
        assertThat(message).contains("self-evaluative");
    }

    @Test
    void userMessageIncludesSelfConsciousGuidanceForRemorse() {
        var emotion = new CognitiveEmotion(EmotionType.REMORSE, 0.6, "self",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null, 0.0, null, null, null, EmotionType.REMORSE);

        assertThat(message).contains("Self-conscious emotion guidance");
    }

    @Test
    void userMessageOmitsSelfConsciousGuidanceForAnger() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.6, "rival",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null, 0.0, null, null, null, EmotionType.ANGER);

        assertThat(message).doesNotContain("Self-conscious emotion guidance");
    }

    @Test
    void userMessageOmitsSelfConsciousGuidanceWhenStrategyPinned() {
        var emotion = new CognitiveEmotion(EmotionType.SHAME, 0.6, "self",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var message = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null, 0.0, null,
                EmotionRegulationStrategy.COGNITIVE_CHANGE, null, EmotionType.SHAME);

        assertThat(message).doesNotContain("Self-conscious emotion guidance");
    }

    @Test
    void systemPromptContainsSocialStrategies() {
        assertThat(EmotionRegulationGenerationPhase.SYSTEM_PROMPT)
                .contains("SOCIAL_SUPPORT_SEEKING")
                .contains("CO_REGULATION")
                .contains("DELIBERATE_PROVOCATION")
                .contains("targetAgentId");
    }

    @Test
    void userMessageIncludesSocialContextWhenAgentsPresent() {
        var regCtx = new RegulationContext("library", List.of("penelope-pitstop", "dick-dastardly"), "treasure");
        var ctxWithAgents = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(),
                                                     null, null, null, regCtx);
        var emotion = new CognitiveEmotion(EmotionType.FEAR, 0.7, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        var rd        = new io.casehub.neocortex.cognitive.index.RegulationDefaults(0.5, 0.5, 0.5, 0.8);

        var msg = EmotionRegulationGenerationPhase.buildUserMessage(
                ctxWithAgents, appraisal, List.of(), 0.5, null, 0.0, null,
                null, null, null, rd, null, null);

        assertThat(msg).contains("Available agents:");
        assertThat(msg).contains("penelope-pitstop");
        assertThat(msg).contains("Social regulation tendency");
    }

    @Test
    void userMessageOmitsSocialContextWhenNoAgents() {
        var emotion = new CognitiveEmotion(EmotionType.FEAR, 0.7, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        var rd        = new io.casehub.neocortex.cognitive.index.RegulationDefaults(0.5, 0.5, 0.5, 0.8);

        var msg = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null, 0.0, null,
                null, null, null, rd, null, null);

        assertThat(msg).doesNotContain("Available agents:");
    }

    @Test
    void parsesSocialStrategyWithTargetAgentId() {
        var json = """
                   {
                       "strategy": "SOCIAL_SUPPORT_SEEKING",
                       "targetEmotions": ["fear"],
                       "targetDrives": [],
                       "peakOriginalIntensity": 0.8,
                       "regulatedIntensity": 0.4,
                       "effectiveness": 0.5,
                       "narrative": "You seek comfort from Penelope",
                       "regulationAxis": null,
                       "environmentalDirective": null,
                       "targetAgentId": "penelope-pitstop"
                   }
                   """;
        when(managedSession.query(any())).thenReturn(
                Multi.createFrom().item(new AgentEvent.TextDelta(json)));

        var emotion = new CognitiveEmotion(EmotionType.FEAR, 0.8, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var phase  = new EmotionRegulationGenerationPhase(sessionPipeline);
        var result = phase.generate(context, appraisal, List.of());

        assertThat(result).isNotNull();
        assertThat(result.strategy()).isEqualTo(EmotionRegulationStrategy.SOCIAL_SUPPORT_SEEKING);
        assertThat(result.targetAgentId()).isEqualTo("penelope-pitstop");
        assertThat(result.isSocialStrategy()).isTrue();
    }

    @Test
    void parsesNullTargetAgentIdForIntrapersonalStrategy() {
        var json = """
                   {
                       "strategy": "COGNITIVE_CHANGE",
                       "targetEmotions": ["anger"],
                       "targetDrives": [],
                       "peakOriginalIntensity": 0.8,
                       "regulatedIntensity": 0.4,
                       "effectiveness": 0.6,
                       "narrative": "You reframe the situation",
                       "regulationAxis": null,
                       "environmentalDirective": null,
                       "targetAgentId": null
                   }
                   """;
        when(managedSession.query(any())).thenReturn(
                Multi.createFrom().item(new AgentEvent.TextDelta(json)));

        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.8, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var phase  = new EmotionRegulationGenerationPhase(sessionPipeline);
        var result = phase.generate(context, appraisal, List.of());

        assertThat(result).isNotNull();
        assertThat(result.targetAgentId()).isNull();
        assertThat(result.isSocialStrategy()).isFalse();
    }

    @Test
    void userMessageIncludesDistressTrajectoryWhenAgentsAndTrajectoriesPresent() {
        var regCtx = new RegulationContext("library", List.of("penelope-pitstop", "dick-dastardly"), "treasure");
        var ctxWithAgents = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(),
                                                     null, null, null, regCtx);
        var emotion = new CognitiveEmotion(EmotionType.FEAR, 0.7, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        var trajectories = Map.of(
                "penelope-pitstop", new RegulationTracker.TrajectoryResult(RegulationTracker.Trajectory.WORSENING, 0.15),
                "dick-dastardly", new RegulationTracker.TrajectoryResult(RegulationTracker.Trajectory.IMPROVING, -0.10)
                                 );

        var msg = EmotionRegulationGenerationPhase.buildUserMessage(
                ctxWithAgents, appraisal, List.of(), 0.5, null, 0.0, null,
                null, null, null, null, null, null, trajectories);

        assertThat(msg).contains("Distress trajectories");
        assertThat(msg).contains("penelope-pitstop: WORSENING");
        assertThat(msg).contains("dick-dastardly: IMPROVING");
    }

    @Test
    void userMessageOmitsDistressTrajectoryWhenNull() {
        var regCtx = new RegulationContext("library", List.of("penelope-pitstop"), "treasure");
        var ctxWithAgents = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(),
                                                     null, null, null, regCtx);
        var emotion = new CognitiveEmotion(EmotionType.FEAR, 0.7, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);

        var msg = EmotionRegulationGenerationPhase.buildUserMessage(
                ctxWithAgents, appraisal, List.of(), 0.5, null, 0.0, null,
                null, null, null, null, null, null, null);

        assertThat(msg).doesNotContain("Distress trajectories");
    }

    @Test
    void userMessageOmitsDistressTrajectoryWhenNoAgents() {
        var emotion = new CognitiveEmotion(EmotionType.FEAR, 0.7, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        var trajectories = Map.of(
                "penelope-pitstop", new RegulationTracker.TrajectoryResult(RegulationTracker.Trajectory.WORSENING, 0.15)
                                 );

        var msg = EmotionRegulationGenerationPhase.buildUserMessage(
                context, appraisal, List.of(), 0.5, null, 0.0, null,
                null, null, null, null, null, null, trajectories);

        assertThat(msg).doesNotContain("Distress trajectories");
    }


}
