package io.casehub.neocortex.cognition.usermodel;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.cognitive.index.CognitiveMemoryRetriever;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.memory.relationship.QualitySignal;
import io.casehub.platform.agent.AgentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.casehub.neocortex.cognition.relationship.RelationshipStageConfig;
import io.casehub.neocortex.session.testing.StubPipelines;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

class UserModelOrchestratorTest {

    private UserProfileMemory profileMemory;

    @BeforeEach
    void setUp() {
        profileMemory = mock(UserProfileMemory.class);
        when(profileMemory.lookup(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void tick_noSignals_returnsUnchanged() {
        var config = UserModelConfig.defaults();
        var orchestrator = new UserModelOrchestrator(profileMemory, config, null);

        var result = orchestrator.tick("agent-1", "user-1", "tenant-1");
        assertThat(result).isInstanceOf(UserModelTick.Unchanged.class);
    }

    @Test
    void tick_withSignals_updatesProfile() {
        var config = UserModelConfig.defaults();
        var orchestrator = new UserModelOrchestrator(profileMemory, config, null);

        orchestrator.record(new InteractionSignal.CustomSignal("user asked about weather", QualitySignal.POSITIVE),
                "agent-1", "user-1", "tenant-1");

        var result = orchestrator.tick("agent-1", "user-1", "tenant-1");
        assertThat(result).isNotInstanceOf(UserModelTick.Unchanged.class);
    }

    @Test
    void tick_withMemoryRetriever_includesMemoriesInLlmPrompt() {
        var capturedPrompt = new AtomicReference<String>();
        var pipeline = StubPipelines.create(
            init -> new AgentSession() {
                @Override public Multi<AgentEvent> query(String prompt) {
                    capturedPrompt.set(prompt);
                    return Multi.createFrom().item(new AgentEvent.TextDelta(
                        "{\"communicationStyle\":\"friendly\",\"topicsOfInterest\":\"tech\"," +
                        "\"preferences\":\"concise\",\"synthesisNotes\":\"engaged\"}"));
                }
                @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
                @Override public void close(Duration timeout) {}
            });

        var mockStore = mock(CaseMemoryStore.class);
        when(mockStore.query(any())).thenReturn(List.of(
            new Memory("m1", Subject.of("unknown", "agent-1"), new MemoryDomain("experience"),
                "tenant-1", null, "Agent helped user resolve billing dispute efficiently",
                Map.of(), Instant.now(), Confidence.unknown(0.8), null, null, null, null, null),
            new Memory("m2", Subject.of("unknown", "agent-1"), new MemoryDomain("experience"),
                "tenant-1", null, "User preferred step-by-step explanations over summaries",
                Map.of(), Instant.now(), Confidence.unknown(0.9), null, null, null, null, null)
        ));
        var retriever = new CognitiveMemoryRetriever(mockStore, List.of());

        var config = new UserModelConfig(
                1, Duration.ZERO, 0.01, 1.0, 0.5,
                RelationshipStageConfig.defaults(),
                Duration.ofHours(1), Duration.ofDays(7),
                "user-model", "user-profile", 50);
        var orchestrator = new UserModelOrchestrator(profileMemory, config, pipeline, retriever);

        orchestrator.record(new InteractionSignal.CustomSignal("user asked about weather", QualitySignal.POSITIVE),
                "agent-1", "user-1", "tenant-1");

        orchestrator.tick("agent-1", "user-1", "tenant-1");

        assertThat(capturedPrompt.get()).isNotNull();
        assertThat(capturedPrompt.get()).contains("Relevant experience memories:");
        assertThat(capturedPrompt.get()).contains("Agent helped user resolve billing dispute efficiently");
        assertThat(capturedPrompt.get()).contains("User preferred step-by-step explanations over summaries");
    }

    @Test
    void tick_withoutMemoryRetriever_promptOmitsMemorySection() {
        var capturedPrompt = new AtomicReference<String>();
        var pipeline = StubPipelines.create(
            init -> new AgentSession() {
                @Override public Multi<AgentEvent> query(String prompt) {
                    capturedPrompt.set(prompt);
                    return Multi.createFrom().item(new AgentEvent.TextDelta(
                        "{\"communicationStyle\":\"friendly\",\"topicsOfInterest\":\"tech\"," +
                        "\"preferences\":\"concise\",\"synthesisNotes\":\"engaged\"}"));
                }
                @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
                @Override public void close(Duration timeout) {}
            });

        var config = new UserModelConfig(
                1, Duration.ZERO, 0.01, 1.0, 0.5,
                RelationshipStageConfig.defaults(),
                Duration.ofHours(1), Duration.ofDays(7),
                "user-model", "user-profile", 50);
        var orchestrator = new UserModelOrchestrator(profileMemory, config, pipeline);

        orchestrator.record(new InteractionSignal.CustomSignal("user asked about weather", QualitySignal.POSITIVE),
                "agent-1", "user-1", "tenant-1");

        orchestrator.tick("agent-1", "user-1", "tenant-1");

        assertThat(capturedPrompt.get()).isNotNull();
        assertThat(capturedPrompt.get()).doesNotContain("Relevant experience memories:");
    }

    @Test
    void tick_withMemoryRetriever_emptyMemories_promptOmitsMemorySection() {
        var capturedPrompt = new AtomicReference<String>();
        var pipeline = StubPipelines.create(
            init -> new AgentSession() {
                @Override public Multi<AgentEvent> query(String prompt) {
                    capturedPrompt.set(prompt);
                    return Multi.createFrom().item(new AgentEvent.TextDelta(
                        "{\"communicationStyle\":\"friendly\",\"topicsOfInterest\":\"tech\"," +
                        "\"preferences\":\"concise\",\"synthesisNotes\":\"engaged\"}"));
                }
                @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
                @Override public void close(Duration timeout) {}
            });

        var mockStore = mock(CaseMemoryStore.class);
        when(mockStore.query(any())).thenReturn(List.of());
        var retriever = new CognitiveMemoryRetriever(mockStore, List.of());

        var config = new UserModelConfig(
                1, Duration.ZERO, 0.01, 1.0, 0.5,
                RelationshipStageConfig.defaults(),
                Duration.ofHours(1), Duration.ofDays(7),
                "user-model", "user-profile", 50);
        var orchestrator = new UserModelOrchestrator(profileMemory, config, pipeline, retriever);

        orchestrator.record(new InteractionSignal.CustomSignal("user asked about weather", QualitySignal.POSITIVE),
                "agent-1", "user-1", "tenant-1");

        orchestrator.tick("agent-1", "user-1", "tenant-1");

        assertThat(capturedPrompt.get()).isNotNull();
        assertThat(capturedPrompt.get()).doesNotContain("Relevant experience memories:");
    }

    @Test
    void tick_withMemoryRetriever_truncatesLongMemoryText() {
        var capturedPrompt = new AtomicReference<String>();
        var pipeline = StubPipelines.create(
            init -> new AgentSession() {
                @Override public Multi<AgentEvent> query(String prompt) {
                    capturedPrompt.set(prompt);
                    return Multi.createFrom().item(new AgentEvent.TextDelta(
                        "{\"communicationStyle\":\"friendly\",\"topicsOfInterest\":\"tech\"," +
                        "\"preferences\":\"concise\",\"synthesisNotes\":\"engaged\"}"));
                }
                @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
                @Override public void close(Duration timeout) {}
            });

        var longText = "A".repeat(200);
        var mockStore = mock(CaseMemoryStore.class);
        when(mockStore.query(any())).thenReturn(List.of(
            new Memory("m1", Subject.of("unknown", "agent-1"), new MemoryDomain("experience"),
                "tenant-1", null, longText,
                Map.of(), Instant.now(), Confidence.unknown(0.8), null, null, null, null, null)
        ));
        var retriever = new CognitiveMemoryRetriever(mockStore, List.of());

        var config = new UserModelConfig(
                1, Duration.ZERO, 0.01, 1.0, 0.5,
                RelationshipStageConfig.defaults(),
                Duration.ofHours(1), Duration.ofDays(7),
                "user-model", "user-profile", 50);
        var orchestrator = new UserModelOrchestrator(profileMemory, config, pipeline, retriever);

        orchestrator.record(new InteractionSignal.CustomSignal("user asked about weather", QualitySignal.POSITIVE),
                "agent-1", "user-1", "tenant-1");

        orchestrator.tick("agent-1", "user-1", "tenant-1");

        assertThat(capturedPrompt.get()).contains("Relevant experience memories:");
        assertThat(capturedPrompt.get()).doesNotContain(longText);
        assertThat(capturedPrompt.get()).contains("A".repeat(120));
    }
}
