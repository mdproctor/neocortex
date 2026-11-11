package io.casehub.neocortex.cognition.regulation;

import io.casehub.neocortex.caps.AgentCapsState;
import io.casehub.neocortex.caps.CapsAttractor;
import io.casehub.neocortex.caps.CapsEngine;
import io.casehub.neocortex.caps.ConvergenceType;
import io.casehub.neocortex.caps.SettlingResult;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.AppraisalTickParticipant;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.PadProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.mockito.AdditionalMatchers;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegulationCascadeTest {

    private AppraisalTickParticipant appraisalParticipant;
    private EmotionRegulationGenerationPhase generationPhase;
    private CapsEngine capsEngine;
    private CognitionTickContext context;
    private CognitionConfig config;

    @BeforeEach
    void setUp() {
        appraisalParticipant = mock(AppraisalTickParticipant.class);
        generationPhase = mock(EmotionRegulationGenerationPhase.class);
        capsEngine = mock(CapsEngine.class);
        context = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of());
        config = CognitionConfig.none().with("regulation", true).with("appraisal", true);
    }

    private void setupAppraisal(double intensity) {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, intensity, "subject1",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
        var appraisal = new AppraisalResult(List.of(emotion), List.of(), null);
        when(appraisalParticipant.currentResult("a1", "t1")).thenReturn(Optional.of(appraisal));
    }

    private void setupCapsSettling(CapsAttractor... attractors) {
        var capsState = new AgentCapsState("a1", "t1", 1, Map.of(), Map.of());
        when(capsEngine.loadState("t1", "a1")).thenReturn(capsState);
        var settlingResult = new SettlingResult(Map.of(), 10,
                ConvergenceType.CONVERGED, 0.5, List.of(attractors));
        when(capsEngine.settle(eq(capsState), any())).thenReturn(settlingResult);
    }

    @Test
    void capsSettlingPinsStrategy() {
        setupAppraisal(0.65);
        setupCapsSettling(new CapsAttractor("regulation", "cognitive_change", 0.5, false, 1));

        var participant = new EmotionRegulationTickParticipant(
                appraisalParticipant, config, generationPhase, null, null, null, null,
                capsEngine, RegulationCascadeConfig.defaults());
        participant.tick(context);

        verify(generationPhase).generate(
                eq(context), any(), any(), anyDouble(), anyDouble(), isNull(),
                eq(EmotionRegulationStrategy.COGNITIVE_CHANGE),
                isNull(), eq(EmotionType.ANGER), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void capsAmbivalentFallsThrough() {
        setupAppraisal(0.65);
        setupCapsSettling(
                new CapsAttractor("regulation", "cognitive_change", 0.40, false, 1),
                new CapsAttractor("regulation", "attentional_deployment", 0.35, false, 1));

        var participant = new EmotionRegulationTickParticipant(
                appraisalParticipant, config, generationPhase, null, null, null, null,
                capsEngine, RegulationCascadeConfig.defaults());
        participant.tick(context);

        verify(generationPhase).generate(
                eq(context), any(), any(), anyDouble(), anyDouble(), isNull(),
                isNull(),
                isNull(), eq(EmotionType.ANGER), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void safetyHardGateSkipsCaps() {
        setupAppraisal(0.85);

        var participant = new EmotionRegulationTickParticipant(
                appraisalParticipant, config, generationPhase, null, null, null, null,
                capsEngine, RegulationCascadeConfig.defaults());
        participant.tick(context);

        verify(capsEngine, never()).settle(any(), any());
        verify(generationPhase).generate(
                eq(context), any(), any(), anyDouble(), anyDouble(), isNull(),
                eq(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT),
                isNull(), eq(EmotionType.ANGER), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void capsWeightUpdateAfterRegulation() {
        setupAppraisal(0.65);
        setupCapsSettling(new CapsAttractor("regulation", "cognitive_change", 0.5, false, 1));

        var regulationResult = new EmotionRegulationResult(
                EmotionRegulationStrategy.COGNITIVE_CHANGE,
                List.of("ANGER"), List.of(),
                0.65, 0.3, 0.7,
                "reappraised the situation", null, null);
        when(generationPhase.generate(
                any(CognitionTickContext.class), any(AppraisalResult.class), any(),
                anyDouble(), anyDouble(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(regulationResult);
        when(capsEngine.updateWeights(any(), any(io.casehub.neocortex.caps.WeightUpdateContext.class)))
                .thenReturn(new AgentCapsState("a1", "t1", 2, Map.of(), Map.of()));

        var participant = new EmotionRegulationTickParticipant(
                appraisalParticipant, config, generationPhase, null, null, null, null,
                capsEngine, RegulationCascadeConfig.defaults());
        participant.tick(context);

        verify(capsEngine).updateWeights(
                any(AgentCapsState.class), any(io.casehub.neocortex.caps.WeightUpdateContext.class));
    }
}
