package io.casehub.neocortex.caps.testing;

import io.casehub.neocortex.caps.SettlingResult;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CognitiveEmergenceSampleTest extends CognitiveEmergenceTest {

    @Test
    void rewardedAgentApproachesSimilarSituations() {
        var scenario = scenario("agent1", new DispositionAxes(
            "cooperative", "moderate", "bold", "moderate", "cooperative"));

        scenario.withExperience(
            "Was rewarded and praised for helping others", 0.7, 0.8, 5);
        scenario.applyExperiences();

        SettlingResult result = scenario.settle(Map.of("reward", 0.8));

        assertAttractorPresent(result, "approach");
    }

    @Test
    void threatenedCautiousAgentWithdraws() {
        var scenario = scenario("agent2", new DispositionAxes(
            "cooperative", "moderate", "conservative", "moderate", "avoidant"));

        scenario.withExperience(
            "Was attacked and hurt in a violent confrontation", 0.9, -0.8, 3);
        scenario.applyExperiences();

        SettlingResult result = scenario.settle(
            Map.of("physical_threat", 0.9));

        assertAttractorPresent(result, "cautious_approach");
    }

    @Test
    void dispositionInfluencesBehavior() {
        var boldDisposition = new DispositionAxes(
            "cooperative", "moderate", "bold", "moderate", "assertive");
        var cautiousDisposition = new DispositionAxes(
            "cooperative", "moderate", "conservative", "moderate", "avoidant");

        var boldScenario = scenario("bold-agent", boldDisposition);
        var cautiousScenario = scenario("cautious-agent", cautiousDisposition);

        SettlingResult boldResult = boldScenario.settle(
            Map.of("social_threat", 0.6));
        SettlingResult cautiousResult = cautiousScenario.settle(
            Map.of("social_threat", 0.6));

        double boldApproach = attractorStrength(boldResult, "approach");
        double cautiousApproach = attractorStrength(cautiousResult, "approach");

        assertThat(boldApproach).as("Bold agent should approach more than cautious")
            .isGreaterThan(cautiousApproach);
    }

    @Test
    void classifierDrivenSettling() {
        var scenario = scenario("agent3", new DispositionAxes(
            "cooperative", "moderate", "calculated", "moderate", "analytical"));

        SettlingResult result = scenario.settleFromDescription(
            "He was rejected and abandoned by his close friend who betrayed him");

        assertAttractorPresent(result, "distrust");
    }
}
