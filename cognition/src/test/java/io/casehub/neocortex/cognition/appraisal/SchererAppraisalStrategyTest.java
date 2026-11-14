package io.casehub.neocortex.cognition.appraisal;

import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.HabituationConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchererAppraisalStrategyTest {

    private final RelevanceCheck relevance = new RelevanceCheck();
    private final ImplicationCheck implication = new ImplicationCheck();
    private final CopingCheck coping = new CopingCheck();
    private final NormativeCheck normative = new NormativeCheck();

    @Test
    void fullPipeline_threatScenario() {
        var strategy = new SchererAppraisalStrategy(
                relevance, implication, coping, normative,
                SchererAppraisalConfig.allEnabled());

        var ctx = context("the danger threatens our curiosity research — trapped with no escape",
                List.of(new Drive("curiosity", DriveCategory.BASELINE, 0.8, "research")));

        var result = strategy.appraise(ctx);

        assertThat(result.emotions()).isNotEmpty();
        assertThat(result.actionTendencies()).isNotEmpty();
    }

    @Test
    void fullPipeline_positiveScenario() {
        var strategy = new SchererAppraisalStrategy(
                relevance, implication, coping, normative,
                SchererAppraisalConfig.allEnabled());

        var ctx = context("curiosity research achieved great success and progressed well",
                List.of(new Drive("curiosity", DriveCategory.BASELINE, 0.8, "research")));

        var result = strategy.appraise(ctx);

        assertThat(result.emotions()).extracting("type").contains(EmotionType.JOY);
        assertThat(result.actionTendencies()).extracting("readiness")
                .contains(ActionReadiness.APPROACH);
    }

    @Test
    void disabledSecs_reducedOutput() {
        var allOn = new SchererAppraisalStrategy(
                relevance, implication, coping, normative,
                SchererAppraisalConfig.allEnabled());
        var relevanceOnly = new SchererAppraisalStrategy(
                relevance, implication, coping, normative,
                new SchererAppraisalConfig(true, false, false, false));

        var ctx = context("danger threatens our curiosity research — betrayal and trapped",
                List.of(new Drive("curiosity", DriveCategory.BASELINE, 0.8, "research")));

        var full = allOn.appraise(ctx);
        var partial = relevanceOnly.appraise(ctx);

        assertThat(full.emotions().size()).isGreaterThanOrEqualTo(partial.emotions().size());
    }

    @Test
    void noRelevance_emptyResult() {
        var strategy = new SchererAppraisalStrategy(
                relevance, implication, coping, normative,
                SchererAppraisalConfig.allEnabled());

        var ctx = context("the weather is pleasant",
                List.of(new Drive("curiosity", DriveCategory.BASELINE, 0.8, "research")));

        var result = strategy.appraise(ctx);

        assertThat(result.emotions()).isEmpty();
    }

    @Test
    void habituationUpdated() {
        var strategy = new SchererAppraisalStrategy(
                relevance, implication, coping, normative,
                SchererAppraisalConfig.allEnabled());

        var ctx = context("curiosity drives the research",
                List.of(new Drive("curiosity", DriveCategory.BASELINE, 0.8, "")));

        var result = strategy.appraise(ctx);

        assertThat(result.updatedHabituation().observationCounts()).isNotEmpty();
    }

    @Test
    void nullChecks_skipped() {
        var strategy = new SchererAppraisalStrategy(
                relevance, null, null, null,
                SchererAppraisalConfig.allEnabled());

        var ctx = context("curiosity research",
                List.of(new Drive("curiosity", DriveCategory.BASELINE, 0.8, "")));

        var result = strategy.appraise(ctx);

        assertThat(result).isNotNull();
    }

    @Test
    void experimentComparison_copingChangesBehavior() {
        var withCoping = new SchererAppraisalStrategy(
                relevance, implication, coping, normative,
                SchererAppraisalConfig.allEnabled());
        var noCoping = new SchererAppraisalStrategy(
                relevance, implication, null, normative,
                new SchererAppraisalConfig(true, true, false, true));

        var ctx = context("danger threatens our curiosity research — trapped with no escape",
                List.of(new Drive("curiosity", DriveCategory.BASELINE, 0.8, "research")));

        var withResult = withCoping.appraise(ctx);
        var noResult = noCoping.appraise(ctx);

        var withTendencies = withResult.actionTendencies().stream().map(ActionTendency::readiness).toList();
        var noTendencies = noResult.actionTendencies().stream().map(ActionTendency::readiness).toList();

        assertThat(withTendencies).as("Coping check should influence action tendencies")
                .isNotEqualTo(noTendencies);
    }

    private static AppraisalContext context(String observation, List<Drive> drives) {
        return new AppraisalContext("test-agent", 
                PerceivedSituation.passThrough(observation),
                drives, null, HabituationConfig.defaults(),
                HabituationState.empty(), null);
    }
}
