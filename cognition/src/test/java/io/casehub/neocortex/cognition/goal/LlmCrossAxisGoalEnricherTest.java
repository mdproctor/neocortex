package io.casehub.neocortex.cognition.goal;

import io.casehub.neocortex.session.testing.StubPipelines;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.narrative.DerivedTheme;
import io.casehub.neocortex.cognition.narrative.IndividualEpisode;
import io.casehub.neocortex.cognition.narrative.NarrativeScope;
import io.casehub.neocortex.cognition.narrative.NarrativeState;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import io.smallrye.mutiny.Uni;
import java.time.Duration;

class LlmCrossAxisGoalEnricherTest {

    private LlmCrossAxisGoalEnricher enricherWith(String response) {
        var e = new LlmCrossAxisGoalEnricher(StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                return Multi.createFrom().item(new AgentEvent.TextDelta(response));
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration timeout) {}
        }));
        return e;
    }

    @Test
    void enriches_whenLlmReturnsText() {
        var enricher = enricherWith("Explore and master new knowledge domains");

        var proposal = proposal(DriveAxis.CURIOSITY, 0.8);
        var theme = theme("knowledge-seeking", 0.8,
                Map.of(DriveAxis.CURIOSITY, 0.7, DriveAxis.COMPETENCE, 0.5));
        var narrative = narrative(theme);

        var result = enricher.enrich("test-agent", proposal, narrative, theme);

        assertThat(result).isNotNull();
        assertThat(result.goalDescription()).isEqualTo("Explore and master new knowledge domains");
        assertThat(result.formationReason()).contains("LLM-enriched cross-axis");
        assertThat(result.formationReason()).contains("knowledge-seeking");
        assertThat(result.axis()).isEqualTo(DriveAxis.CURIOSITY);
        assertThat(result.goalName()).isEqualTo("test-goal");
        assertThat(result.driveIntensity()).isEqualTo(0.8);
    }

    @Test
    void returnsNull_whenLlmReturnsError() {
        var enricher = new LlmCrossAxisGoalEnricher(StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                return Multi.createFrom().empty();
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration timeout) {}
        }));

        var proposal = proposal(DriveAxis.CURIOSITY, 0.8);
        var theme = theme("test", 0.8, Map.of(DriveAxis.CURIOSITY, 0.7));

        var result = enricher.enrich("test-agent", proposal, narrative(theme), theme);

        assertThat(result).isNull();
    }

    @Test
    void returnsNull_whenLlmReturnsBlank() {
        var enricher = enricherWith("   ");

        var proposal = proposal(DriveAxis.CURIOSITY, 0.8);
        var theme = theme("test", 0.8, Map.of(DriveAxis.CURIOSITY, 0.7));

        var result = enricher.enrich("test-agent", proposal, narrative(theme), theme);

        assertThat(result).isNull();
    }

    @Test
    void returnsNull_whenInvocationThrows() {
        var enricher = new LlmCrossAxisGoalEnricher(StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                return Multi.createFrom().failure(new RuntimeException("timeout"));
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration timeout) {}
        }));

        var proposal = proposal(DriveAxis.CURIOSITY, 0.8);
        var theme = theme("test", 0.8, Map.of(DriveAxis.CURIOSITY, 0.7));

        var result = enricher.enrich("test-agent", proposal, narrative(theme), theme);

        assertThat(result).isNull();
    }

    @Test
    void promptIncludesEpisodes() {
        var capturedPrompt = new String[1];
        var enricher = new LlmCrossAxisGoalEnricher(StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                capturedPrompt[0] = prompt;
                return Multi.createFrom().item(new AgentEvent.TextDelta("enriched goal"));
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration timeout) {}
        }));

        var episode = new IndividualEpisode("ep-1", Instant.now(), null,
                List.of(), "Had a breakthrough in research", 0.8, List.of());
        var theme = theme("research", 0.8, Map.of(DriveAxis.CURIOSITY, 0.7));
        var narrative = new NarrativeState("s1", "t1", NarrativeScope.INDIVIDUAL,
                List.of(episode, theme), Instant.now(), 0);

        enricher.enrich("test-agent", proposal(DriveAxis.CURIOSITY, 0.8), narrative, theme);

        assertThat(capturedPrompt[0]).contains("breakthrough in research");
    }

    private DriveGoalProposal proposal(DriveAxis axis, double intensity) {
        return new DriveGoalProposal(axis, "test-goal", "Test compound goal",
                "test reason", intensity);
    }

    private DerivedTheme theme(String label, double salience,
                                Map<DriveAxis, Double> weights) {
        return new DerivedTheme("t-" + label, Instant.now(), null,
                List.of(), label, salience, weights, List.of());
    }

    private NarrativeState narrative(DerivedTheme... themes) {
        return new NarrativeState("s1", "t1", NarrativeScope.INDIVIDUAL,
                List.of(themes), Instant.now(), 0);
    }
}
