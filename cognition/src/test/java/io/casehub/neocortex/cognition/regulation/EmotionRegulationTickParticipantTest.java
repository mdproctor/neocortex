package io.casehub.neocortex.cognition.regulation;

import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.AppraisalTickParticipant;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.PadProjection;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
import io.casehub.neocortex.cognitive.index.DescriptorView;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.casehub.neocortex.cognition.appraisal.AppraisalContext;
import io.casehub.neocortex.cognition.coping.AttentionDirective;
import io.casehub.neocortex.cognition.coping.AttentionDirectiveType;
import io.casehub.neocortex.cognition.coping.CopingMicroLoop;
import io.casehub.neocortex.cognition.coping.CopingState;
import io.casehub.neocortex.cognition.coping.CopingStrategy;
import io.casehub.neocortex.cognition.coping.CopingStrategyAttempt;
import io.casehub.neocortex.cognition.coping.DriveModulation;
import io.casehub.neocortex.cognition.core.CognitiveAttentionMediator;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.mindmap.AttentionBriefing;
import io.casehub.neocortex.mindmap.SignalCategory;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;


class EmotionRegulationTickParticipantTest {

    private AppraisalTickParticipant appraisalParticipant;
    private CognitionTickContext context;

    @BeforeEach
    void setUp() {
        appraisalParticipant = mock(AppraisalTickParticipant.class);
        context = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of());
    }

    @Test
    void noRegulationWhenDisabled() {
        var config = CognitionConfig.none();
        var participant = new EmotionRegulationTickParticipant(appraisalParticipant, config);

        participant.tick(context);

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void noRegulationWhenAppraisalEmpty() {
        var config = CognitionConfig.none().with("regulation", true).with("appraisal", true);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(AppraisalResult.empty()));

        var participant = new EmotionRegulationTickParticipant(appraisalParticipant, config);
        participant.tick(context);

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void noRegulationWhenBelowThreshold() {
        // intensity 0.3, baseline 0.3 → discrepancy 0.0 → below threshold 0.4
        var emotion = new CognitiveEmotion(EmotionType.JOY, 0.3, "subject1",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(appraisal));

        var config = CognitionConfig.none().with("regulation", true).with("appraisal", true);
        var participant = new EmotionRegulationTickParticipant(appraisalParticipant, config);
        participant.tick(context);

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void regulationFiresWhenAboveThreshold() {
        // intensity 0.85, baseline 0.3 → discrepancy 0.55 → above threshold 0.4
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.85, "subject1",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(appraisal));

        var config = CognitionConfig.none().with("regulation", true).with("appraisal", true);
        var participant = new EmotionRegulationTickParticipant(appraisalParticipant, config);
        participant.tick(context);

        // Generation phase not yet wired (Task 4), so result is still empty
        // This test verifies the gate logic only — the tick method runs past the gate
        // Full round-trip test comes in Task 4
        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void noRegulationWhenAppraisalNotPresent() {
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.empty());

        var config = CognitionConfig.none().with("regulation", true).with("appraisal", true);
        var participant = new EmotionRegulationTickParticipant(appraisalParticipant, config);
        participant.tick(context);

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void strategyPrediction_requiresGenerationPhaseForNarrative() {
        // Predictor provides strategy hint, but generation phase produces the result.
        // Without a generation phase, no regulation result even when predictor fires.
        var axes = new DispositionAxes("cooperative", "moderate", "conservative", "low", "avoidant");
        var descriptor = DescriptorView.of("a1", axes, List.of(), List.of());
        var defaults = CognitiveDefaults.empty("a1").withDescriptor(descriptor);

        var registry = mock(EffectiveCognitiveDefaults.class);
        when(registry.forAgentOrDefaults("t1", "a1")).thenReturn(defaults);
        when(registry.forAgent("t1", "a1")).thenReturn(defaults);

        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.65, "subject1",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(appraisal));

        var config = CognitionConfig.none().with("regulation", true).with("appraisal", true);
        // registry present (predictor active) but no generation phase → no result
        var participant = new EmotionRegulationTickParticipant(
                appraisalParticipant, config, null, null, null, registry, null);
        participant.tick(context);

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void mechanicalPrediction_fallsToLlmWhenAmbiguous() {
        // cooperative + moderate → mild reappraiser signals but insufficient for prediction
        var axes = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "cooperative");
        var descriptor = DescriptorView.of("a1", axes, List.of(), List.of());
        var defaults = CognitiveDefaults.empty("a1").withDescriptor(descriptor);

        var registry = mock(EffectiveCognitiveDefaults.class);
        when(registry.forAgentOrDefaults("t1", "a1")).thenReturn(defaults);
        when(registry.forAgent("t1", "a1")).thenReturn(defaults);

        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.65, "subject1",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(appraisal));

        var config = CognitionConfig.none().with("regulation", true).with("appraisal", true);
        var participant = new EmotionRegulationTickParticipant(
                appraisalParticipant, config, null, null, null, registry, null);
        participant.tick(context);

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void mechanicalPrediction_skippedWithNoRegistry() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.65, "subject1",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(appraisal));

        var config = CognitionConfig.none().with("regulation", true).with("appraisal", true);
        // No registry, no generation phase → no result
        var participant = new EmotionRegulationTickParticipant(appraisalParticipant, config);
        participant.tick(context);

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void passesDispositionAxesOnFallThrough() {
        // Incoherent personality → predictor falls through
        var axes       = new DispositionAxes("independent", "flexible", "conservative", "moderate", "avoidant");
        var descriptor = DescriptorView.of("a1", axes, List.of(), List.of());
        var defaults   = CognitiveDefaults.empty("a1").withDescriptor(descriptor);

        var registry = mock(EffectiveCognitiveDefaults.class);
        when(registry.forAgentOrDefaults("t1", "a1")).thenReturn(defaults);
        when(registry.forAgent("t1", "a1")).thenReturn(defaults);

        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.65, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(appraisal));

        var generationPhase = mock(EmotionRegulationGenerationPhase.class);
        var config          = CognitionConfig.none().with("regulation", true).with("appraisal", true);
        var participant = new EmotionRegulationTickParticipant(
                appraisalParticipant, config, generationPhase, null, null, registry, null);
        participant.tick(context);

        // Verify generate was called with axes and dominant emotion type
        verify(generationPhase).generate(
                eq(context), any(), any(), anyDouble(), anyDouble(), isNull(), isNull(),
                eq(axes), eq(EmotionType.ANGER), isNull(), isNull(), isNull(), isNull());
    }


    @Test
    void copingEnabled_safetyGateTriggered_pinsAttentionalDeployment() {
        var emotion = new CognitiveEmotion(EmotionType.FEAR, 0.90, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC,
                                           PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(appraisal));

        var generationPhase = mock(EmotionRegulationGenerationPhase.class);
        var copingMicroLoop = mock(CopingMicroLoop.class);

        var config = CognitionConfig.none().with("regulation", true)
                                    .with("appraisal", true).with("coping", true);
        var participant = new EmotionRegulationTickParticipant(
                appraisalParticipant, config, generationPhase, null, null,
                null, null, null, null, null, copingMicroLoop);
        participant.tick(context);

        // Safety gate fires (intensity 0.90 > 0.75) → AD pinned, regulation called
        verify(generationPhase).generate(eq(context), eq(appraisal), any(), anyDouble(), anyDouble(),
                                         isNull(), eq(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT));
        // Coping micro-loop NOT called
        Mockito.verifyNoInteractions(copingMicroLoop);
    }

    @Test
    void copingEnabled_regulationRoutedStrategy_callsGenerateForCoping() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.65, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC,
                                           PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(appraisal));

        var appraisalContext = mock(AppraisalContext.class);
        when(appraisalParticipant.currentContext("a1", "t1")).thenReturn(Optional.of(appraisalContext));

        // CopingState with POSITIVE_REINTERPRETATION → regulation-routed to COGNITIVE_CHANGE
        var copingState = CopingState.initial(
                                    "original", Map.of(), List.of(emotion))
                                                                           .withInterpretation("reinterpreted narrative")
                                                                           .withStrategy(new CopingStrategyAttempt(
                                                                                   CopingStrategy.POSITIVE_REINTERPRETATION,
                                                                                   0, "reinterpreted narrative"));

        var copingMicroLoop = mock(CopingMicroLoop.class);
        when(copingMicroLoop.execute(any(), any(), any(), any(), any(), any(), any())).thenReturn(copingState);

        var generationPhase = mock(EmotionRegulationGenerationPhase.class);

        var config = CognitionConfig.none().with("regulation", true)
                                    .with("appraisal", true).with("coping", true);
        var participant = new EmotionRegulationTickParticipant(
                appraisalParticipant, config, generationPhase, null, null,
                null, null, null, null, null, copingMicroLoop);
        participant.tick(context);

        // Verify generateForCoping called with correct coping context
        verify(generationPhase).generateForCoping(
                eq(context), eq(appraisal), any(), anyDouble(), anyDouble(),
                eq(CopingStrategy.POSITIVE_REINTERPRETATION),
                eq(EmotionRegulationStrategy.COGNITIVE_CHANGE),
                eq("reinterpreted narrative"));
    }

    @Test
    void copingEnabled_directEffectStrategy_noRegulationCall() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.65, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC,
                                           PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(appraisal));

        var appraisalContext = mock(AppraisalContext.class);
        when(appraisalParticipant.currentContext("a1", "t1")).thenReturn(Optional.of(appraisalContext));

        // CopingState with PLANNING — direct-effect (not regulation-routed)
        var copingState = CopingState.initial(
                                    "original", Map.of(), List.of(emotion))
                                                                           .withStrategy(new CopingStrategyAttempt(
                                                                                   CopingStrategy.PLANNING,
                                                                                   0, "I'll make a plan"));

        var copingMicroLoop = mock(CopingMicroLoop.class);
        when(copingMicroLoop.execute(any(), any(), any(), any(), any(), any(), any())).thenReturn(copingState);

        var generationPhase = mock(EmotionRegulationGenerationPhase.class);

        var config = CognitionConfig.none().with("regulation", true)
                                    .with("appraisal", true).with("coping", true);
        var participant = new EmotionRegulationTickParticipant(
                appraisalParticipant, config, generationPhase, null, null,
                null, null, null, null, null, copingMicroLoop);
        participant.tick(context);

        // No regulation call for direct-effect strategies
        Mockito.verifyNoInteractions(generationPhase);
        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void dispatchCopingEffects_driveModulations_dispatchedToOrchestrator() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.65, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC,
                                           PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(appraisal));

        var appraisalContext = mock(AppraisalContext.class);
        when(appraisalParticipant.currentContext("a1", "t1")).thenReturn(Optional.of(appraisalContext));

        var driveMod = new DriveModulation(
                DriveAxis.AUTONOMY, 0.3, "coping boosted autonomy");
        var copingState = CopingState.initial(
                                    "original", Map.of(), List.of(emotion))
                .withStrategy(new CopingStrategyAttempt(
                        CopingStrategy.PLANNING, 0, "plan"))
                .withAccumulatedDriveMod(driveMod);

        var copingMicroLoop = mock(CopingMicroLoop.class);
        when(copingMicroLoop.execute(any(), any(), any(), any(), any(), any(), any())).thenReturn(copingState);

        var driveOrchestrator = mock(DriveOrchestrator.class);

        var config = CognitionConfig.none().with("regulation", true)
                                    .with("appraisal", true).with("coping", true);
        var participant = new EmotionRegulationTickParticipant(
                appraisalParticipant, config, mock(EmotionRegulationGenerationPhase.class), null, null,
                null, null, null, null, null, copingMicroLoop, null,
                driveOrchestrator, null);
        participant.tick(context);

        verify(driveOrchestrator).recordAppraisalModulation(
                eq("a1"), eq("t1"),
                eq(Map.of(DriveAxis.AUTONOMY, 0.3)));
    }

    @Test
    void dispatchCopingEffects_multipleDriveModsSameAxis_summed() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.65, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC,
                                           PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(appraisal));
        when(appraisalParticipant.currentContext("a1", "t1")).thenReturn(
                Optional.of(mock(AppraisalContext.class)));

        var mod1 = new DriveModulation(
                DriveAxis.AUTONOMY, 0.2, "first");
        var mod2 = new DriveModulation(
                DriveAxis.AUTONOMY, 0.15, "second");
        var copingState = CopingState.initial(
                                    "original", Map.of(), List.of(emotion))
                .withStrategy(new CopingStrategyAttempt(
                        CopingStrategy.PLANNING, 0, "plan"))
                .withAccumulatedDriveMod(mod1)
                .withAccumulatedDriveMod(mod2);

        var copingMicroLoop = mock(CopingMicroLoop.class);
        when(copingMicroLoop.execute(any(), any(), any(), any(), any(), any(), any())).thenReturn(copingState);

        var driveOrchestrator = mock(DriveOrchestrator.class);

        var config = CognitionConfig.none().with("regulation", true)
                                    .with("appraisal", true).with("coping", true);
        var participant = new EmotionRegulationTickParticipant(
                appraisalParticipant, config, mock(EmotionRegulationGenerationPhase.class), null, null,
                null, null, null, null, null, copingMicroLoop, null,
                driveOrchestrator, null);
        participant.tick(context);

        verify(driveOrchestrator).recordAppraisalModulation(
                eq("a1"), eq("t1"),
                eq(Map.of(DriveAxis.AUTONOMY, 0.35)));
    }

    @Test
    void dispatchCopingEffects_attentionDirectives_queuedToMediator() {
        var emotion = new CognitiveEmotion(EmotionType.FEAR, 0.70, "subject1",
                                           Instant.now(), EmotionSource.INTRINSIC,
                                           PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(appraisal));

        var appraisalContext = mock(AppraisalContext.class);
        when(appraisalParticipant.currentContext("a1", "t1")).thenReturn(Optional.of(appraisalContext));

        var focusDir = new AttentionDirective(
                AttentionDirectiveType.FOCUS, "safety-plan", "focus on safety");
        var suppressDir = new AttentionDirective(
                AttentionDirectiveType.SUPPRESS, "threat-source", "suppress threat rumination");
        var copingState = CopingState.initial(
                                    "original", Map.of(), List.of(emotion))
                .withStrategy(new CopingStrategyAttempt(
                        CopingStrategy.PLANNING, 0, "plan"))
                .withAccumulatedAttentionDirective(focusDir)
                .withAccumulatedAttentionDirective(suppressDir);

        var copingMicroLoop = mock(CopingMicroLoop.class);
        when(copingMicroLoop.execute(any(), any(), any(), any(), any(), any(), any())).thenReturn(copingState);

        var attentionMediator = mock(CognitiveAttentionMediator.class);

        var config = CognitionConfig.none().with("regulation", true)
                                    .with("appraisal", true).with("coping", true);
        var participant = new EmotionRegulationTickParticipant(
                appraisalParticipant, config, mock(EmotionRegulationGenerationPhase.class), null, null,
                null, null, null, null, null, copingMicroLoop, null,
                null, attentionMediator);
        participant.tick(context);

        var briefingCaptor = ArgumentCaptor.forClass(AttentionBriefing.class);
        verify(attentionMediator).queueBriefing(briefingCaptor.capture());
        var briefing = briefingCaptor.getValue();
        assertThat(briefing.signals()).hasSize(2);
        assertThat(briefing.signals().get(0).category()).isEqualTo(SignalCategory.COPING_FOCUS);
        assertThat(briefing.signals().get(0).sourceNodeId()).isEqualTo("safety-plan");
        assertThat(briefing.signals().get(1).category()).isEqualTo(SignalCategory.COPING_SUPPRESS);
        assertThat(briefing.signals().get(1).sourceNodeId()).isEqualTo("threat-source");
    }
}
