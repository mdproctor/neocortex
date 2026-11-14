package io.casehub.neocortex.cognition.appraisal;

import io.casehub.neocortex.cognitive.AlmaPadTable;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmotionMappingIntegrationTest {

    private static final String TENANT = "test-tenant";

    @Test
    void hopeFromNoveltyFlowsThroughHarness() {
        var secResults = List.of(
                SecResult.of("relevance", SecDimensions.RELEVANCE, 0.7, SecDimensions.NOVELTY, 0.7),
                SecResult.of("implication", SecDimensions.CONDUCIVENESS, 0.5));

        var emotions = EmotionMapper.mapEmotions(secResults, "discovery");
        var result = new AppraisalResult(emotions, EmotionMapper.mapTendencies(secResults), HabituationState.empty());

        assertThat(emotions).extracting("type")
                .as("Novel positive scenario should produce HOPE and JOY")
                .contains(EmotionType.HOPE, EmotionType.JOY);

        var hope = emotions.stream().filter(e -> e.type() == EmotionType.HOPE).findFirst().orElseThrow();
        assertThat(hope.intensity())
                .as("HOPE intensity from relevance(0.7) * novelty(0.7)")
                .isGreaterThanOrEqualTo(0.4);
        assertThat(hope.pad().pleasure())
                .as("HOPE PAD should have positive pleasure")
                .isGreaterThan(0.0);

        var pad = intensityWeightedPad(emotions);
        assertThat(pad[0])
                .as("Intensity-weighted pleasure should be positive (HOPE + JOY)")
                .isGreaterThan(0.0);

        var harness = CognitionTestHarness.builder()
                .agentFromAxes("test-agent", "neutral", "neutral", "high", "neutral", "cooperative")
                .withAppraisalResult("test-agent", result)
                .build();

        harness.recordMood("test-agent", TENANT, pad[0], pad[1], pad[2]);
        harness.tick("test-agent", TENANT);

        var mood = harness.currentMood("test-agent", TENANT);
        assertThat(mood).isPresent();
        assertThat(mood.get().pleasure())
                .as("Mood pleasure should reflect HOPE + JOY positive shift")
                .isGreaterThan(0.0);
    }

    @Test
    void fearWithUrgencyHasBoostedArousal() {
        var secResults = List.of(
                SecResult.of("relevance", SecDimensions.RELEVANCE, 0.8, SecDimensions.URGENCY, 0.8),
                SecResult.of("implication", SecDimensions.CONDUCIVENESS, -0.6),
                SecResult.of("coping", SecDimensions.CONTROLLABILITY, 0.5));

        var emotions = EmotionMapper.mapEmotions(secResults, "security-breach");

        assertThat(emotions).extracting("type")
                .as("Urgent threat should produce FEAR via widened threshold")
                .contains(EmotionType.FEAR);

        var fear = emotions.stream().filter(e -> e.type() == EmotionType.FEAR).findFirst().orElseThrow();
        var canonicalArousal = AlmaPadTable.lookup(EmotionType.FEAR).arousal() * fear.intensity();
        assertThat(fear.pad().arousal())
                .as("Urgency should boost FEAR arousal above canonical baseline")
                .isGreaterThan(canonicalArousal);

        var pad = intensityWeightedPad(emotions);
        assertThat(pad[1])
                .as("Intensity-weighted arousal across all emotions should be elevated")
                .isGreaterThan(0.2);

        var harness = CognitionTestHarness.builder()
                .agentFromAxes("test-agent", "neutral", "neutral", "neutral", "neutral", "cooperative")
                .withAppraisalResult("test-agent", new AppraisalResult(emotions,
                        EmotionMapper.mapTendencies(secResults), HabituationState.empty()))
                .build();

        harness.recordMood("test-agent", TENANT, pad[0], pad[1], pad[2]);
        harness.tick("test-agent", TENANT);

        var mood = harness.currentMood("test-agent", TENANT);
        assertThat(mood).isPresent();
        assertThat(mood.get().arousal())
                .as("Mood arousal should reflect urgency-boosted FEAR")
                .isGreaterThan(0.15);
    }

    @Test
    void shameWithUrgencyHasElevatedArousalAboveBaseline() {
        var secResults = List.of(
                SecResult.of("relevance", SecDimensions.RELEVANCE, 0.8, SecDimensions.URGENCY, 0.7),
                SecResult.of("normative",
                        SecDimensions.INTERNAL_STANDARDS, 0.2,
                        SecDimensions.EXTERNAL_STANDARDS, 0.8));

        var emotions = EmotionMapper.mapEmotions(secResults, "public-confrontation");
        assertThat(emotions).extracting("type").contains(EmotionType.SHAME);

        var shame = emotions.stream().filter(e -> e.type() == EmotionType.SHAME).findFirst().orElseThrow();
        var canonicalArousal = AlmaPadTable.lookup(EmotionType.SHAME).arousal() * shame.intensity();
        assertThat(shame.pad().arousal())
                .as("Public confrontation urgency should boost SHAME arousal above canonical baseline")
                .isGreaterThan(canonicalArousal + 0.1);

        assertThat(shame.pad().pleasure())
                .as("SHAME PAD should have negative pleasure")
                .isLessThan(0.0);

        var pad = intensityWeightedPad(emotions);

        var harness = CognitionTestHarness.builder()
                .agentFromAxes("test-agent", "neutral", "high", "low", "neutral", "cooperative")
                .withAppraisalResult("test-agent", new AppraisalResult(emotions,
                        EmotionMapper.mapTendencies(secResults), HabituationState.empty()))
                .build();

        harness.recordMood("test-agent", TENANT, pad[0], pad[1], pad[2]);
        harness.tick("test-agent", TENANT);

        var mood = harness.currentMood("test-agent", TENANT);
        assertThat(mood).isPresent();
        assertThat(mood.get().pleasure())
                .as("Mood pleasure should reflect SHAME negative shift")
                .isLessThan(0.0);
    }

    private static double[] intensityWeightedPad(List<CognitiveEmotion> emotions) {
        double totalIntensity = emotions.stream().mapToDouble(CognitiveEmotion::intensity).sum();
        if (totalIntensity <= 0) return new double[]{0, 0, 0};
        double p = emotions.stream().mapToDouble(e -> e.intensity() * e.pad().pleasure()).sum() / totalIntensity;
        double a = emotions.stream().mapToDouble(e -> e.intensity() * e.pad().arousal()).sum() / totalIntensity;
        double d = emotions.stream().mapToDouble(e -> e.intensity() * e.pad().dominance()).sum() / totalIntensity;
        return new double[]{p, a, d};
    }
}
