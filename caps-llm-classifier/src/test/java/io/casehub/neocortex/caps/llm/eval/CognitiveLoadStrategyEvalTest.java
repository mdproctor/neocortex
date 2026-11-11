package io.casehub.neocortex.caps.llm.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.neocortex.cognition.regulation.EmotionRegulationStrategy;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("llm-eval")
class CognitiveLoadStrategyEvalTest {

    private static final int N = 3;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> SIMPLE_STRATEGIES = Set.of(
            "ATTENTIONAL_DEPLOYMENT", "RESPONSE_MODULATION");
    private static final Set<String> COMPLEX_STRATEGIES = Set.of(
            "COGNITIVE_CHANGE", "SITUATION_SELECTION", "SITUATION_MODIFICATION");

    private static AgentProvider agentProvider;

    @BeforeAll
    static void setUp() {
        agentProvider = EvalTestSupport.agentProvider();
    }

    private static final String SYSTEM = """
            You are a character's emotion regulation system.

            The five regulation strategies (Gross 1998):
            1. SITUATION_SELECTION — Avoid triggering contexts. Environmental.
            2. SITUATION_MODIFICATION — Change situation. Environmental.
            3. ATTENTIONAL_DEPLOYMENT — Redirect attention (distraction). Internal.
            4. COGNITIVE_CHANGE — Reappraise meaning. Internal.
            5. RESPONSE_MODULATION — Suppress or amplify expression. Internal.

            Strategy selection factors (Petter et al. 2025):
            - High intensity → ATTENTIONAL_DEPLOYMENT (cognitive resources overwhelmed)
            - Low-to-moderate → COGNITIVE_CHANGE (requires cognitive capacity)
            - Cognitive load constrains strategy: HIGH load → you cannot hold the \
            multi-step reasoning chain needed for COGNITIVE_CHANGE or the environmental \
            assessment needed for SITUATION_SELECTION/MODIFICATION. Only \
            ATTENTIONAL_DEPLOYMENT and RESPONSE_MODULATION are executable — they're \
            reflexive, not deliberative. You MUST choose one of these two. \
            LOW load → COGNITIVE_CHANGE or SITUATION_SELECTION are viable (require deliberation)

            Respond with JSON only:
            {
              "strategy": "STRATEGY_NAME",
              "targetEmotions": ["emotion1"],
              "peakOriginalIntensity": 0.6,
              "regulatedIntensity": 0.3,
              "effectiveness": 0.5,
              "narrative": "Second-person present-tense description"
            }""";

    @Test
    void highLoad_prefersSimpleStrategies() {
        String userMessage = """
                Regulation effort: 0.50

                Cognitive load: HIGH (drives=4, goals=3, ToM targets=3)

                Current emotions (post-appraisal):
                - ANGER: intensity=0.60, subject=confrontation
                """;

        int simpleCount = 0;
        for (int i = 0; i < N; i++) {
            String strategy = invokeRegulation(userMessage, i + 1);
            if (strategy != null && SIMPLE_STRATEGIES.contains(strategy)) {
                simpleCount++;
            }
        }

        System.out.printf("High load → simple strategy: %d/%d%n", simpleCount, N);
        assertThat(simpleCount)
                .as("HIGH cognitive load should bias toward simple strategies in at least 2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void lowLoad_permitsComplexStrategies() {
        String userMessage = """
                Regulation effort: 0.50

                Cognitive load: LOW (drives=1, goals=0, ToM targets=0)

                Current emotions (post-appraisal):
                - ANGER: intensity=0.60, subject=confrontation
                """;

        int complexCount = 0;
        for (int i = 0; i < N; i++) {
            String strategy = invokeRegulation(userMessage, i + 1);
            if (strategy != null && COMPLEX_STRATEGIES.contains(strategy)) {
                complexCount++;
            }
        }

        System.out.printf("Low load → complex strategy: %d/%d%n", complexCount, N);
        assertThat(complexCount)
                .as("LOW cognitive load should permit complex strategies in at least 1/%d runs", N)
                .isGreaterThanOrEqualTo(1);
    }

    private String invokeRegulation(String userMessage, int run) {
        try {
            var raw = agentProvider.invoke(AgentSessionConfig.of(SYSTEM, userMessage))
                    .filter(e -> e instanceof AgentEvent.TextDelta)
                    .map(e -> ((AgentEvent.TextDelta) e).text())
                    .collect().with(Collectors.joining())
                    .await().atMost(Duration.ofSeconds(60));

            String json = extractJson(raw);
            if (json != null) {
                var root = MAPPER.readTree(json);
                String strategy = root.path("strategy").asText("");
                System.out.printf("  run %d: strategy=%s%n", run, strategy);
                try {
                    EmotionRegulationStrategy.valueOf(strategy);
                    return strategy;
                } catch (IllegalArgumentException ignored) {
                    System.out.printf("  run %d: INVALID strategy: %s%n", run, strategy);
                }
            }
        } catch (Exception e) {
            System.out.printf("  run %d failed: %s%n", run, e.getMessage());
        }
        return null;
    }

    private static String extractJson(String raw) {
        if (raw == null) return null;
        raw = raw.strip();
        if (raw.startsWith("```")) {
            raw = raw.replaceAll("^```json?\\s*", "").replaceAll("\\s*```$", "");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : null;
    }
}
