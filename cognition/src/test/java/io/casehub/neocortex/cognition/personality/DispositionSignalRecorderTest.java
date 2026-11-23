package io.casehub.neocortex.cognition.personality;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.neocortex.cognition.situational.PersonalityState;
import io.casehub.neocortex.cognition.situational.PersonalityStateService;
import io.casehub.neocortex.cognition.situational.ReflectionLedger;
import io.casehub.neocortex.memory.relationship.QualitySignal;
import io.casehub.neocortex.memory.relationship.RelationshipEvent;
import io.casehub.neocortex.memory.relationship.RelationshipRecorded;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Map;
import org.junit.jupiter.api.Assertions;

class DispositionSignalRecorderTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final String TENANT = "t1";
    private static final String AGENT = "agent1";

    private PersonalityStateService stateService;
    private AgentRegistry agentRegistry;
    private DispositionSignalWriter signalWriter;
    private DispositionSignalRecorder recorder;

    @BeforeEach
    void setUp() {
        stateService = mock(PersonalityStateService.class);
        agentRegistry = mock(AgentRegistry.class);
        signalWriter = mock(DispositionSignalWriter.class);

        var state = PersonalityState.initial(Set.of("Anxiousness"), NOW);
        when(stateService.read(any(), any(), any())).thenReturn(state);

        var sources = List.<TraitPressureSource<?>>of(
                new RelationshipPressureSource(),
                new InteractionPressureSource());
        recorder = new DispositionSignalRecorder(sources, stateService, agentRegistry, signalWriter);
    }

    @Test
    void neutralRelationshipShortCircuits() {
        var event = new RelationshipEvent(AGENT, "other", TENANT, null, null, NOW,
                "experience", QualitySignal.NEUTRAL, "test interaction", null, Map.of());
        recorder.onRelationshipRecorded(new RelationshipRecorded(event, "mem-1"));

        verify(stateService, never()).read(any(), any(), any());
        verify(signalWriter, never()).enqueue(any(), any(), any());
    }

    @Test
    void neutralInteractionShortCircuits() {
        var event = new InteractionAppraised(TENANT, AGENT, null, QualitySignal.NEUTRAL, NOW);
        recorder.onInteractionAppraised(event);

        verify(stateService, never()).read(any(), any(), any());
        verify(signalWriter, never()).enqueue(any(), any(), any());
    }

    @Test
    void positiveInteractionActivatesDominant() {
        var descriptor = descriptorWith(List.of(
                new DispositionValue("ti", 0.35), new DispositionValue("ne", 0.20)));
        when(agentRegistry.findById(AGENT, TENANT)).thenReturn(Optional.of(descriptor));

        var event = new InteractionAppraised(TENANT, AGENT, "subject1", QualitySignal.POSITIVE, NOW);
        recorder.onInteractionAppraised(event);

        verify(signalWriter).enqueue(eq(TENANT), eq(AGENT), eq(List.of("ti")));
    }

    @Test
    void negativeInteractionActivatesAuxiliary() {
        var descriptor = descriptorWith(List.of(
                new DispositionValue("ti", 0.35), new DispositionValue("ne", 0.20)));
        when(agentRegistry.findById(AGENT, TENANT)).thenReturn(Optional.of(descriptor));

        var event = new InteractionAppraised(TENANT, AGENT, "subject1", QualitySignal.NEGATIVE, NOW);
        recorder.onInteractionAppraised(event);

        verify(signalWriter).enqueue(eq(TENANT), eq(AGENT), eq(List.of("ne")));
    }

    @Test
    void disabledAgentSkipsRecording() {
        var ledger = ReflectionLedger.initial(NOW).withDisabled(true);
        var state = PersonalityState.initial(Set.of("Anxiousness"), NOW).withReflection(ledger);
        when(stateService.read(any(), any(), any())).thenReturn(state);

        var descriptor = descriptorWith(List.of(new DispositionValue("ti", 0.35)));
        when(agentRegistry.findById(AGENT, TENANT)).thenReturn(Optional.of(descriptor));

        var event = new InteractionAppraised(TENANT, AGENT, null, QualitySignal.POSITIVE, NOW);
        recorder.onInteractionAppraised(event);

        verify(signalWriter, never()).enqueue(any(), any(), any());
    }

    @Test
    void duplicateSourcesFailFast() {
        var sources = List.<TraitPressureSource<?>>of(
                new RelationshipPressureSource(), new RelationshipPressureSource());
        try {
            new DispositionSignalRecorder(sources, stateService, agentRegistry, signalWriter);
            Assertions.fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("Duplicate");
        }
    }

    private AgentDescriptor descriptorWith(List<DispositionValue> profile) {
        var disposition = mock(AgentDisposition.class);
        when(disposition.dispositionProfile()).thenReturn(profile);
        var descriptor = mock(AgentDescriptor.class);
        when(descriptor.disposition()).thenReturn(disposition);
        return descriptor;
    }
}
