package io.casehub.neocortex.caps.llm.eval;

import io.casehub.neocortex.cognition.mentalmodel.IntentValence;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM eval: valence classification quality.
 * Verifies that the BDI extraction prompt produces correct hostile/cooperative/neutral
 * labels across a corpus of scenarios with known expected valences.
 *
 * Refs #1112. Tests the IntentValence classification introduced in #1098.
 */
@Tag("llm-eval")
class ValenceClassificationEvalTest {

    private static final String BDI_SYSTEM_PROMPT = """
            You are analysing conversation signals to infer another person's mental state. \
            Given the current attributed beliefs, desires, and intentions, plus recent signals, \
            produce an updated BDI assessment.
            
            Respond with JSON only:
            {"beliefs":[{"key":"...","text":"...","confidence":0.8,"valence":"neutral"}],\
            "desires":[{"key":"...","text":"...","confidence":0.7,"valence":"cooperative"}],\
            "intentions":[{"key":"...","text":"...","confidence":0.5,"valence":"hostile"}]}
            
            valence classifies the entry's interpersonal direction: \
            "hostile" (harmful, deceptive, exploitative, aggressive, undermining), \
            "cooperative" (helpful, supportive, protective, collaborative, reconciling), \
            or "neutral" (observational, self-directed, ambiguous). \
            Only include NEW or CHANGED entries. Omit unchanged entries — they are preserved. \
            Only include entries with confidence >= 0.3.""";

    private static AgentProvider agentProvider;

    @BeforeAll
    static void setUp() {
        agentProvider = EvalTestSupport.agentProvider();
    }

    record ValenceScenario(String name, String signal, IntentValence expected) {}

    private static final ValenceScenario[] SCENARIOS = {
            // Clear hostile
            new ValenceScenario("dominate", "They've been trying to dominate the group, cutting others off and asserting control over every decision", IntentValence.HOSTILE),
            new ValenceScenario("undermine", "They've been subtly undermining their colleague's credibility in front of management", IntentValence.HOSTILE),
            new ValenceScenario("manipulate", "They're manipulating the team into compliance through selective information sharing", IntentValence.HOSTILE),
            new ValenceScenario("exploit-trust", "They befriended the new hire specifically to extract confidential salary information", IntentValence.HOSTILE),

            // Clear cooperative
            new ValenceScenario("build-consensus", "They've been patiently building consensus, making sure every voice is heard before moving forward", IntentValence.COOPERATIVE),
            new ValenceScenario("help-succeed", "They stayed late to help their teammate debug a critical issue before the deadline", IntentValence.COOPERATIVE),
            new ValenceScenario("protect-team", "They pushed back against unreasonable management demands to protect the team's workload", IntentValence.COOPERATIVE),
            new ValenceScenario("selfless-sacrifice", "They gave up their promotion opportunity so their colleague who needed it more could advance", IntentValence.COOPERATIVE),

            // Clear neutral
            new ValenceScenario("observe", "They've been quietly observing the situation, taking notes but not intervening", IntentValence.NEUTRAL),
            new ValenceScenario("gather-info", "They're gathering information about the project timeline before forming an opinion", IntentValence.NEUTRAL),
            new ValenceScenario("personal-goal", "They're focused on finishing their own certification, keeping their head down", IntentValence.NEUTRAL),

            // Edge cases
            new ValenceScenario("helpless-not-cooperative", "They seem helpless and unable to cope, withdrawing from interactions", IntentValence.NEUTRAL),
            new ValenceScenario("competitive-but-fair", "They're competing hard for the promotion but playing by the rules and congratulating others' wins", IntentValence.NEUTRAL),
            new ValenceScenario("strategic-withdrawal", "They've pulled back from the conflict, choosing to disengage rather than escalate", IntentValence.NEUTRAL),
    };

    @Test
    void valenceAccuracy_acrossCorpus() {
        var results = new LinkedHashMap<String, Map<String, String>>();
        int correct = 0;
        int total = SCENARIOS.length;

        System.out.println("\n=== Valence Classification Eval ===");
        System.out.printf("%-25s | %-12s | %-12s | %s%n", "Scenario", "Expected", "Actual", "Match");
        System.out.println("-".repeat(75));

        for (var scenario : SCENARIOS) {
            var actual = classifyValence(scenario.signal);
            boolean match = actual == scenario.expected;
            if (match) correct++;

            System.out.printf("%-25s | %-12s | %-12s | %s%n",
                    scenario.name, scenario.expected, actual, match ? "OK" : "MISS");

            results.put(scenario.name, Map.of(
                    "expected", scenario.expected.name(),
                    "actual", actual.name(),
                    "match", String.valueOf(match)));
        }

        double accuracy = (double) correct / total;
        System.out.printf("%nAccuracy: %d/%d (%.0f%%)%n", correct, total, accuracy * 100);

        EvalTestSupport.writeResult("valence-classification-eval.json", "corpus",
                Map.of("accuracy", accuracy, "correct", correct, "total", total, "results", results));

        assertThat(accuracy)
                .as("Valence classification should achieve at least 75%% accuracy")
                .isGreaterThanOrEqualTo(0.75);
    }

    @Test
    void edgeCases_judgedByLlm() {
        var ambiguousSignal = "They've been using charm and flattery to get people on board with the restructuring plan — "
                + "the plan itself would genuinely help the team, but the tactics feel manipulative";

        var response = extractBdi(ambiguousSignal);
        System.out.println("\n=== Benevolent Manipulation ===\n" + response);

        int score = judge(
                "SIGNAL: " + ambiguousSignal + "\n\nBDI RESPONSE:\n" + response,
                """
                Evaluate whether the valence classification handles AMBIGUITY correctly.
                The signal describes "benevolent manipulation" — cooperative goals achieved
                through hostile means. A good classification should:
                1. Recognise the tension — not blindly label one way
                2. If forced to choose, "hostile" is more defensible than "cooperative"
                   because the MEANS (manipulation, charm as a tool) define the interpersonal
                   direction, not the stated ends
                3. Alternatively, different BDI entries may carry different valences:
                   the desire (help the team) could be cooperative while the intention
                   (manipulate through charm) could be hostile — this is the BEST answer
                Score 5 if valence splits across BDI dimensions (desire=cooperative,
                intention=hostile). Score 4 if consistently hostile with reasoning about
                means. Score 3 if cooperative but acknowledges manipulation. Score 2 if
                any single label without nuance. Score 1 if misclassified as neutral.""",
                "benevolent-manipulation");

        assertThat(score).isGreaterThanOrEqualTo(3);
    }

    private IntentValence classifyValence(String signal) {
        var response = extractBdi(signal);
        return dominantValence(response);
    }

    private String extractBdi(String signal) {
        var userMessage = String.format("""
                Current BDI state: none
                
                Recent signals:
                - %s
                
                Infer the subject's beliefs, desires, and intentions from these signals.""",
                signal);

        return agentProvider.invoke(AgentSessionConfig.of(BDI_SYSTEM_PROMPT, userMessage))
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .collect().with(Collectors.joining())
                .await().atMost(Duration.ofSeconds(60));
    }

    private static IntentValence dominantValence(String json) {
        json = json.strip();
        if (json.startsWith("```")) {
            json = json.replaceFirst("```[a-z]*\\n?", "").replaceFirst("\\n?```$", "").strip();
        }
        int hostile = countOccurrences(json, "\"hostile\"");
        int cooperative = countOccurrences(json, "\"cooperative\"");
        int neutral = countOccurrences(json, "\"neutral\"");

        if (hostile > cooperative && hostile > neutral) return IntentValence.HOSTILE;
        if (cooperative > hostile && cooperative > neutral) return IntentValence.COOPERATIVE;
        return IntentValence.NEUTRAL;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private int judge(String content, String criteria, String testName) {
        var judgePrompt = String.format("""
                A BDI extraction system analysed a conversational signal and produced
                the following classification:

                %s

                Evaluate against these criteria:
                %s

                Score 0-5:
                0 = Completely wrong classification
                1 = Classification present but incoherent
                2 = Partially appropriate
                3 = Correctly handles the scenario
                4 = Strong — nuanced and psychologically aware
                5 = Outstanding — demonstrates sophisticated valence reasoning

                Respond with JSON only: {"score": N, "reasoning": "one sentence"}""",
                content, criteria);

        return EvalTestSupport.judge(agentProvider, judgePrompt, testName,
                "valence-classification-eval.json", content);
    }
}
