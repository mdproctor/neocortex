package io.casehub.neocortex.caps.llm;

import io.casehub.neocortex.caps.CapsNode;
import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.NodeRange;
import io.casehub.neocortex.caps.NodeType;
import io.casehub.neocortex.caps.WeightUpdateParameters;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalityPromptBuilderTest {

    private static final CapsTopology TOPOLOGY = new CapsTopology(1,
        Map.of(
            "reward", new CapsNode("reward", NodeType.INPUT, NodeRange.UNIPOLAR,
                "consequence", List.of("operant"), Map.of("rewarded", 0.8)),
            "social_threat", new CapsNode("social_threat", NodeType.INPUT, NodeRange.UNIPOLAR,
                "threat", List.of("attachment"), Map.of("excluded", 0.9)),
            "approach", new CapsNode("approach", NodeType.OUTPUT, NodeRange.UNIPOLAR,
                "approach_avoidance", List.of(), Map.of())
        ),
        List.of(), Map.of(), List.of(),
        new WeightUpdateParameters(0.01, 0.25, 0.1, 0.5, 0.95,
            Map.of(), 0.2, 100, 0.001,
            new double[]{-2.0, 2.0}, new double[]{0.1, 3.0}, 0.6));

    @Test
    void userPromptSuffix_containsAllAxes() {
        var meta = Map.of(
            "disposition.socialOrient", "competitive",
            "disposition.ruleFollowing", "flexible",
            "disposition.riskAppetite", "bold",
            "disposition.autonomy", "high",
            "disposition.conflictMode", "assertive");

        String suffix = PersonalityPromptBuilder.userPromptSuffix(meta);

        assertThat(suffix).contains("competitive");
        assertThat(suffix).contains("flexible");
        assertThat(suffix).contains("bold");
        assertThat(suffix).contains("high");
        assertThat(suffix).contains("assertive");
        assertThat(suffix).contains("Classify the situation as this character would perceive it");
    }

    @Test
    void personalitySystemPrompt_containsFrame() {
        var meta = Map.of(
            "disposition.riskAppetite", "bold",
            "disposition.ruleFollowing", "strict",
            "disposition.socialOrient", "competitive",
            "disposition.autonomy", "moderate",
            "disposition.conflictMode", "collaborative");

        var tension = DispositionTension.topTension(meta).orElseThrow();

        String sysPrompt = PersonalityPromptBuilder.personalitySystemPrompt(
            meta, tension, TOPOLOGY);

        assertThat(sysPrompt).contains("through a specific character's lens");
        assertThat(sysPrompt).contains(tension.narrative());
        assertThat(sysPrompt).contains("reward");
        assertThat(sysPrompt).contains("social_threat");
    }

    @Test
    void personalitySystemPrompt_excludesOutputNodes() {
        var meta = Map.of(
            "disposition.riskAppetite", "bold",
            "disposition.ruleFollowing", "strict");
        var tension = DispositionTension.topTension(meta).orElseThrow();

        String sysPrompt = PersonalityPromptBuilder.personalitySystemPrompt(
            meta, tension, TOPOLOGY);

        assertThat(sysPrompt).contains("reward");
        assertThat(sysPrompt).contains("social_threat");
        assertThat(sysPrompt).doesNotContain("- approach");
    }

    @Test
    void personalitySystemPrompt_includesJsonInstructions() {
        var meta = Map.of(
            "disposition.riskAppetite", "bold",
            "disposition.ruleFollowing", "strict");
        var tension = DispositionTension.topTension(meta).orElseThrow();

        String sysPrompt = PersonalityPromptBuilder.personalitySystemPrompt(
            meta, tension, TOPOLOGY);

        assertThat(sysPrompt).contains("JSON");
        assertThat(sysPrompt).contains("activations");
        assertThat(sysPrompt).contains("confidence");
    }

    @Test
    void hasDisposition_true() {
        assertThat(PersonalityPromptBuilder.hasDisposition(
            Map.of("disposition.socialOrient", "cooperative"))).isTrue();
    }

    @Test
    void hasDisposition_false() {
        assertThat(PersonalityPromptBuilder.hasDisposition(Map.of())).isFalse();
        assertThat(PersonalityPromptBuilder.hasDisposition(null)).isFalse();
        assertThat(PersonalityPromptBuilder.hasDisposition(
            Map.of("event-type", "observation"))).isFalse();
    }

    @Test
    void userPromptSuffix_missingAxes_includesAvailable() {
        var meta = Map.of(
            "disposition.socialOrient", "cooperative",
            "disposition.riskAppetite", "calculated");

        String suffix = PersonalityPromptBuilder.userPromptSuffix(meta);

        assertThat(suffix).contains("cooperative");
        assertThat(suffix).contains("calculated");
    }
}
