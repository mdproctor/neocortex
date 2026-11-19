package io.casehub.neocortex.cognition.innerlife;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.drive.DriveProfile;
import io.casehub.neocortex.memory.reflection.ReflectionOrchestrator;
import io.casehub.neocortex.session.testing.StubPipelines;
import io.casehub.neocortex.summarisation.EventLevel;
import io.casehub.neocortex.summarisation.LevelEvent;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InnerLifeOrchestratorTest {

    private ReflectionOrchestrator reflectionOrchestrator;
    private DriveOrchestrator driveOrchestrator;
    private AgentDescriptor descriptor;

    @BeforeEach
    void setUp() {
        reflectionOrchestrator = mock(ReflectionOrchestrator.class);
        driveOrchestrator = mock(DriveOrchestrator.class);
        descriptor = mock(AgentDescriptor.class);
        when(descriptor.agentId()).thenReturn("agent-1");
        when(descriptor.tenancyId()).thenReturn("tenant-1");
        when(descriptor.name()).thenReturn("TestAgent");
        when(descriptor.briefing()).thenReturn("A test agent");
        var disposition = mock(AgentDisposition.class);
        when(disposition.dispositionProfile()).thenReturn(List.of());
        when(descriptor.disposition()).thenReturn(disposition);
        when(driveOrchestrator.currentDrives("agent-1", "tenant-1"))
                .thenReturn(java.util.Optional.of(new DriveProfile(
                        "agent-1", "tenant-1",
                        java.util.Map.of(io.casehub.neocortex.cognition.drive.DriveAxis.CURIOSITY,
                                new io.casehub.neocortex.cognition.drive.DriveIntensity(
                                        io.casehub.neocortex.cognition.drive.DriveAxis.CURIOSITY, 0.5, "test")),
                        0.5, io.casehub.neocortex.cognition.drive.DriveAxis.CURIOSITY,
                        java.time.Instant.now())));
    }

    private InnerLifeOrchestrator makeOrchestrator(CivilityConstraint... constraints) {
        return makeOrchestrator(null, constraints);
    }

    private InnerLifeOrchestrator makeOrchestrator(io.casehub.platform.agent.session.SessionPipeline pipeline, CivilityConstraint... constraints) {
        return new InnerLifeOrchestrator(
                reflectionOrchestrator, List.of(constraints),
                InnerLifeConfig.defaults(), driveOrchestrator, pipeline);
    }

    private static io.casehub.platform.agent.session.SessionPipeline stubPipeline(String... deltas) {
        return StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                var items = java.util.Arrays.stream(deltas).map(AgentEvent.TextDelta::new).toArray(AgentEvent[]::new);
                return Multi.createFrom().items(items);
            }
            @Override public io.smallrye.mutiny.Uni<Void> interrupt() { return io.smallrye.mutiny.Uni.createFrom().voidItem(); }
            @Override public void close(java.time.Duration timeout) {}
        });
    }

    private LevelEvent<String> event(String text) {
        return new LevelEvent<>(text, System.currentTimeMillis(), new EventLevel("L1", 1), null);
    }

    @Test
    void tickReturnsSilentWhenCivilityDenied() {
        var orch = makeOrchestrator(ctx -> new CivilityCheck.Denied("test deny"));

        var result = orch.tick(descriptor, "channel context");

        assertThat(result).isInstanceOf(InnerLifeTick.Silent.class);
        assertThat(((InnerLifeTick.Silent) result).reason()).contains("test deny");
        verify(reflectionOrchestrator, never()).reflect(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void tickReturnsSilentWhenNoObservations() {
        var orch = makeOrchestrator(ctx -> new CivilityCheck.Permitted());

        var result = orch.tick(descriptor, "context");

        assertThat(result).isInstanceOf(InnerLifeTick.Silent.class);
        verify(reflectionOrchestrator, never()).reflect(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void tickReturnsSilentWhenBelowMotivationThreshold() {
        var orch = makeOrchestrator(stubPipeline("{\"score\": 0.2, \"content\": \"\", \"channelHint\": null}"), ctx -> new CivilityCheck.Permitted());

        for (int i = 0; i < 5; i++) {
            orch.observe(event("novel content " + i + " unique " + System.nanoTime()), descriptor);
        }

        when(reflectionOrchestrator.reflect(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of("I noticed some things"));

        var result = orch.tick(descriptor, "context");

        assertThat(result).isInstanceOf(InnerLifeTick.Silent.class);
    }

    @Test
    void tickReturnsInitiatedWhenAboveMotivationThreshold() {
        var orch = makeOrchestrator(stubPipeline("{\"score\": 0.85, \"content\": \"Hello everyone!\", \"channelHint\": \"#general\"}"), ctx -> new CivilityCheck.Permitted());

        for (int i = 0; i < 5; i++) {
            orch.observe(event("novel content " + i + " unique " + System.nanoTime()), descriptor);
        }

        when(reflectionOrchestrator.reflect(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of("I have something to say"));

        var result = orch.tick(descriptor, "context");

        assertThat(result).isInstanceOf(InnerLifeTick.Initiated.class);
        var initiated = (InnerLifeTick.Initiated) result;
        assertThat(initiated.content()).isEqualTo("Hello everyone!");
        assertThat(initiated.channelHint()).isEqualTo("#general");
        assertThat(initiated.motivationScore()).isEqualTo(0.85);
    }

    @Test
    void tickReturnsSilentOnMalformedLlmResponse() {
        var orch = makeOrchestrator(stubPipeline("not valid json"), ctx -> new CivilityCheck.Permitted());

        for (int i = 0; i < 5; i++) {
            orch.observe(event("novel content " + i + " unique " + System.nanoTime()), descriptor);
        }

        when(reflectionOrchestrator.reflect(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of("thinking"));

        var result = orch.tick(descriptor, "context");

        assertThat(result).isInstanceOf(InnerLifeTick.Silent.class);
        assertThat(((InnerLifeTick.Silent) result).reason()).contains("parse failure");
    }

    @Test
    void multipleTextDeltasAreConcatenated() {
        var orch = makeOrchestrator(stubPipeline("{\"score\": 0.9, ", "\"content\": \"Hi!\", ", "\"channelHint\": null}"), ctx -> new CivilityCheck.Permitted());

        for (int i = 0; i < 5; i++) {
            orch.observe(event("novel content " + i + " unique " + System.nanoTime()), descriptor);
        }

        when(reflectionOrchestrator.reflect(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of("thoughts"));

        var result = orch.tick(descriptor, "context");

        assertThat(result).isInstanceOf(InnerLifeTick.Initiated.class);
        assertThat(((InnerLifeTick.Initiated) result).content()).isEqualTo("Hi!");
    }

    @Test
    void tickDoesNotCallDriveOrchestratorTick() {
        var orch = makeOrchestrator(ctx -> new CivilityCheck.Permitted());
        orch.observe(event("something happened"), descriptor);

        orch.tick(descriptor, "context");

        verify(driveOrchestrator, never()).tick(any(), any(), any());
    }

    @Test
    void tickReturnsSilentWhenDrivesNotCurrent() {
        when(driveOrchestrator.currentDrives("agent-1", "tenant-1"))
                .thenReturn(java.util.Optional.empty());
        var orch = makeOrchestrator();
        orch.observe(event("observation"), descriptor);
        var result = orch.tick(descriptor, "context");
        assertThat(result).isInstanceOf(InnerLifeTick.Silent.class);
        assertThat(((InnerLifeTick.Silent) result).reason())
                .isEqualTo("drives not current");
    }

    @Test
    void tickReturnsNonVerbalWhenAffiliationHighAndSilenceLong() {
        var driveProfile = new DriveProfile(
                "agent-1", "tenant-1",
                java.util.Map.of(
                        io.casehub.neocortex.cognition.drive.DriveAxis.AFFILIATION,
                        new io.casehub.neocortex.cognition.drive.DriveIntensity(
                                io.casehub.neocortex.cognition.drive.DriveAxis.AFFILIATION, 0.8, "loneliness"),
                        io.casehub.neocortex.cognition.drive.DriveAxis.CURIOSITY,
                        new io.casehub.neocortex.cognition.drive.DriveIntensity(
                                io.casehub.neocortex.cognition.drive.DriveAxis.CURIOSITY, 0.3, "idle")),
                0.55,
                io.casehub.neocortex.cognition.drive.DriveAxis.AFFILIATION,
                java.time.Instant.now());
        when(driveOrchestrator.currentDrives("agent-1", "tenant-1"))
                .thenReturn(java.util.Optional.of(driveProfile));

        var nvConfig = new NonVerbalConfig(java.time.Duration.ofMillis(1), 0.6, "knock");
        var config = new InnerLifeConfig(
                InnerLifeConfig.DEFAULT_MOTIVATION_THRESHOLD,
                ContentQualityGate.defaults(),
                InnerLifeConfig.DEFAULT_MAX_REFLECTION_SOURCES,
                InnerLifeConfig.DEFAULT_MAX_OBSERVATIONS_IN_PROMPT,
                InnerLifeConfig.DEFAULT_WINDOW_DURATION,
                InnerLifeConfig.DEFAULT_EVICTION_TIMEOUT,
                nvConfig);
        var orch = new InnerLifeOrchestrator(
                reflectionOrchestrator, List.of(ctx -> new CivilityCheck.Permitted()),
                config, driveOrchestrator, null);

        // first tick seeds agent state with lastActivityTimestamp
        orch.tick(descriptor, "context");

        // sleep past the silence threshold
        try {Thread.sleep(10);} catch (InterruptedException e) {Thread.currentThread().interrupt();}

        var result = orch.tick(descriptor, "context");

        assertThat(result).isInstanceOf(InnerLifeTick.NonVerbal.class);
        var nv = (InnerLifeTick.NonVerbal) result;
        assertThat(nv.tag()).isEqualTo("knock");
        assertThat(nv.motivationScore()).isEqualTo(0.8);
    }

    @Test
    void tickDoesNotReturnNonVerbalWhenAffiliationBelowThreshold() {
        var driveProfile = new DriveProfile(
                "agent-1", "tenant-1",
                java.util.Map.of(
                        io.casehub.neocortex.cognition.drive.DriveAxis.AFFILIATION,
                        new io.casehub.neocortex.cognition.drive.DriveIntensity(
                                io.casehub.neocortex.cognition.drive.DriveAxis.AFFILIATION, 0.3, "minimal"),
                        io.casehub.neocortex.cognition.drive.DriveAxis.CURIOSITY,
                        new io.casehub.neocortex.cognition.drive.DriveIntensity(
                                io.casehub.neocortex.cognition.drive.DriveAxis.CURIOSITY, 0.5, "exploring")),
                0.4,
                io.casehub.neocortex.cognition.drive.DriveAxis.CURIOSITY,
                java.time.Instant.now());
        when(driveOrchestrator.currentDrives("agent-1", "tenant-1"))
                .thenReturn(java.util.Optional.of(driveProfile));

        var nvConfig = new NonVerbalConfig(java.time.Duration.ofMillis(1), 0.6, "knock");
        var config = new InnerLifeConfig(
                InnerLifeConfig.DEFAULT_MOTIVATION_THRESHOLD,
                ContentQualityGate.defaults(),
                InnerLifeConfig.DEFAULT_MAX_REFLECTION_SOURCES,
                InnerLifeConfig.DEFAULT_MAX_OBSERVATIONS_IN_PROMPT,
                InnerLifeConfig.DEFAULT_WINDOW_DURATION,
                InnerLifeConfig.DEFAULT_EVICTION_TIMEOUT,
                nvConfig);
        var orch = new InnerLifeOrchestrator(
                reflectionOrchestrator, List.of(ctx -> new CivilityCheck.Permitted()),
                config, driveOrchestrator, null);

        // seed state, sleep past silence threshold
        orch.tick(descriptor, "context");
        try {Thread.sleep(10);} catch (InterruptedException e) {Thread.currentThread().interrupt();}

        var result = orch.tick(descriptor, "context");

        assertThat(result).isNotInstanceOf(InnerLifeTick.NonVerbal.class);
    }

    @Test
    void tickWithAdditionalSectionsAppendsToSystemPrompt() {
        var sectionRendered = new java.util.concurrent.atomic.AtomicBoolean(false);
        io.casehub.neocortex.cognition.prompt.CognitionPromptRenderer section = ctx -> {
            sectionRendered.set(true);
            return "Use expressive gestures when speaking.";
        };

        var orch = makeOrchestrator(
                stubPipeline("{\"score\": 0.9, \"content\": \"Hey!\", \"channelHint\": null}"),
                ctx -> new CivilityCheck.Permitted());

        for (int i = 0; i < 5; i++) {
            orch.observe(event("novel content " + i + " unique " + System.nanoTime()), descriptor);
        }

        when(reflectionOrchestrator.reflect(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of("thoughts"));

        var result = orch.tick(descriptor, "context", List.of(section));

        assertThat(sectionRendered.get()).isTrue();
        assertThat(result).isInstanceOf(InnerLifeTick.Initiated.class);
    }

    @Test
    void reportDroppedDecrementsConsecutiveInitiations() {
        var orch = makeOrchestrator(
                stubPipeline("{\"score\": 0.9, \"content\": \"Hello!\", \"channelHint\": null}"),
                ctx -> new CivilityCheck.Permitted());

        for (int i = 0; i < 5; i++) {
            orch.observe(event("novel content " + i + " unique " + System.nanoTime()), descriptor);
        }

        when(reflectionOrchestrator.reflect(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of("reflections"));

        // Trigger an initiation
        var tick1 = orch.tick(descriptor, "context");
        assertThat(tick1).isInstanceOf(InnerLifeTick.Initiated.class);

        // Report it was dropped
        orch.reportDropped("agent-1", "tenant-1", java.time.Instant.now());

        // Observe a response — should not go below 0
        orch.observeResponse(descriptor);

        // Now new observations and another tick — verify the agent can still initiate
        for (int i = 0; i < 5; i++) {
            orch.observe(event("fresh content " + i + " new " + System.nanoTime()), descriptor);
        }
        var tick2 = orch.tick(descriptor, "context");
        assertThat(tick2).isInstanceOf(InnerLifeTick.Initiated.class);
    }

    @Test
    void reportDroppedWithNoStateDoesNotThrow() {
        var orch = makeOrchestrator();
        // No prior tick — no agent state exists
        orch.reportDropped("unknown-agent", "unknown-tenant", java.time.Instant.now());
        // Should not throw
    }


}
