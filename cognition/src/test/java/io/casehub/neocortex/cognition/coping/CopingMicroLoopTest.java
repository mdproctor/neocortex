package io.casehub.neocortex.cognition.coping;

import io.casehub.neocortex.cognition.appraisal.AppraisalContext;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.CopingCheck;
import io.casehub.neocortex.cognition.appraisal.Drive;
import io.casehub.neocortex.cognition.appraisal.DriveCategory;
import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.cognition.appraisal.ImplicationCheck;
import io.casehub.neocortex.cognition.appraisal.NormativeCheck;
import io.casehub.neocortex.cognition.appraisal.PerceivedSituation;
import io.casehub.neocortex.cognition.appraisal.RelevanceCheck;
import io.casehub.neocortex.cognition.appraisal.SecCheck;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.HabituationConfig;
import io.casehub.neocortex.cognitive.PadProjection;
import io.casehub.neocortex.cognitive.index.CopingDefaults;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CopingMicroLoopTest {

    private static final List<SecCheck> KEYWORD_CHECKS = List.of(
            new RelevanceCheck(), new ImplicationCheck(),
            new CopingCheck(), new NormativeCheck());

    private static final CopingDefaults PLANFUL =
            new CopingDefaults(0.85, 0.4, 0.2, 0.4, 0.6);

    private static final Map<String, Double> HIGH_CTRL_DIMS =
            Map.of("controllability", 0.7, "adjustability", 0.5, "relevance", 0.8);

    private static CognitiveEmotion emotion(EmotionType type, double intensity) {
        return new CognitiveEmotion(type, intensity, "test-agent",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
    }

    private static final List<Drive> SAFETY_DRIVES = List.of(
            new Drive("safety", DriveCategory.BASELINE, 0.8, "threat"));

    private static AppraisalContext context(String narrative) {
        return new AppraisalContext("agent-1",
                PerceivedSituation.passThrough(narrative),
                List.of(), null,
                HabituationConfig.defaults(), HabituationState.empty(), null);
    }

    private static AppraisalContext contextWithDrives(String narrative) {
        return new AppraisalContext("agent-1",
                PerceivedSituation.passThrough(narrative),
                SAFETY_DRIVES, null,
                HabituationConfig.defaults(), HabituationState.empty(), null);
    }

    private static AppraisalResult result(List<CognitiveEmotion> emotions, Map<String, Double> dims) {
        return new AppraisalResult(emotions, List.of(), HabituationState.empty(), null, dims);
    }

    // --- Convergence: emotions below threshold ---

    @Test
    void lowIntensityEmotions_convergeImmediately() {
        var stubPhase = stubGenerationPhase("reinterpreted", CopingStrategy.PLANNING);
        var loop = new CopingMicroLoop(new CopingStrategyPredictor(), stubPhase, KEYWORD_CHECKS);

        var state = loop.execute(
                context("mild annoyance"),
                result(List.of(emotion(EmotionType.ANGER, 0.3)), HIGH_CTRL_DIMS),
                PLANFUL, null, null);

        assertThat(state).isNotNull();
        assertThat(state.iteration()).isEqualTo(0);
        assertThat(state.strategiesAttempted()).isEmpty();
    }

    // --- Single iteration: LLM called once, converges ---

    @Test
    void singleIteration_generatesAndConverges() {
        var stubPhase = stubGenerationPhase(
                "I can manage this. There are options I haven't tried.",
                CopingStrategy.PLANNING);
        var loop = new CopingMicroLoop(new CopingStrategyPredictor(), stubPhase, KEYWORD_CHECKS);

        var state = loop.execute(
                context("blocked and frustrated"),
                result(List.of(emotion(EmotionType.ANGER, 0.7)), HIGH_CTRL_DIMS),
                PLANFUL, null, null);

        assertThat(state).isNotNull();
        assertThat(state.strategiesAttempted()).isNotEmpty();
        assertThat(state.strategiesAttempted().getFirst().strategy()).isEqualTo(CopingStrategy.PLANNING);
        assertThat(state.interpretationNarrative()).contains("manage");
    }

    // --- Oscillation detection: same strategy breaks loop ---

    @Test
    void oscillation_sameStrategyTwice_breaksLoop() {
        var callCount = new AtomicInteger(0);
        var stubPhase = new CopingGenerationPhase(null) {
            @Override
            public CopingResult generate(AppraisalContext ctx, CopingState state,
                                          CopingStrategy pinned, CopingDefaults defaults) {
                callCount.incrementAndGet();
                return new CopingResult(CopingStrategy.DENIAL,
                        "The safety threat has failed and lost. This is stuck and impossible.",
                        List.of(), List.of(), List.of());
            }
        };
        var config = new CopingConfig(0.3, 5);
        var loop = new CopingMicroLoop(new CopingStrategyPredictor(), stubPhase, KEYWORD_CHECKS, config);

        var state = loop.execute(
                contextWithDrives("safety threat has failed"),
                result(List.of(emotion(EmotionType.FEAR, 0.8)),
                        Map.of("controllability", 0.1, "adjustability", 0.2)),
                PLANFUL, null, null);

        assertThat(state).isNotNull();
        assertThat(callCount.get()).isEqualTo(2);
        assertThat(state.strategiesAttempted()).hasSize(2);
        assertThat(state.strategiesAttempted().stream()
                .allMatch(a -> a.strategy() == CopingStrategy.DENIAL)).isTrue();
    }

    @Test
    void oscillation_ABA_pattern_breaksLoop() {
        var callCount = new AtomicInteger(0);
        var sequence = new CopingStrategy[]{
                CopingStrategy.DENIAL, CopingStrategy.AVOIDANCE, CopingStrategy.DENIAL};
        var stubPhase = new CopingGenerationPhase(null) {
            @Override
            public CopingResult generate(AppraisalContext ctx, CopingState state,
                                          CopingStrategy pinned, CopingDefaults defaults) {
                int idx = callCount.getAndIncrement();
                var strategy = sequence[Math.min(idx, sequence.length - 1)];
                return new CopingResult(strategy,
                        "The safety threat has failed and lost. This is stuck and impossible.",
                        List.of(), List.of(), List.of());
            }
        };
        var config = new CopingConfig(0.3, 5);
        var loop = new CopingMicroLoop(new CopingStrategyPredictor(), stubPhase, KEYWORD_CHECKS, config);

        var state = loop.execute(
                contextWithDrives("safety threat has failed"),
                result(List.of(emotion(EmotionType.FEAR, 0.8)),
                        Map.of("controllability", 0.1, "adjustability", 0.2)),
                PLANFUL, null, null);

        assertThat(state).isNotNull();
        assertThat(callCount.get()).isEqualTo(3);
        assertThat(state.strategiesAttempted()).hasSize(3);
        assertThat(state.strategiesAttempted().get(0).strategy()).isEqualTo(CopingStrategy.DENIAL);
        assertThat(state.strategiesAttempted().get(1).strategy()).isEqualTo(CopingStrategy.AVOIDANCE);
        assertThat(state.strategiesAttempted().get(2).strategy()).isEqualTo(CopingStrategy.DENIAL);
    }

    // --- Iteration cap: max iterations reached ---

    @Test
    void iterationCap_stopsAtMaxIterations() {
        var callCount = new AtomicInteger(0);
        var strategies = new CopingStrategy[]{
                CopingStrategy.DENIAL, CopingStrategy.AVOIDANCE, CopingStrategy.RESIGNATION};
        var stubPhase = new CopingGenerationPhase(null) {
            @Override
            public CopingResult generate(AppraisalContext ctx, CopingState state,
                                          CopingStrategy pinned, CopingDefaults defaults) {
                int idx = callCount.getAndIncrement();
                var strategy = strategies[Math.min(idx, strategies.length - 1)];
                return new CopingResult(strategy,
                        "safety has failed and is lost. Trapped and powerless, stuck in danger.",
                        List.of(), List.of(), List.of());
            }
        };
        var config = new CopingConfig(0.3, 3);
        var loop = new CopingMicroLoop(new CopingStrategyPredictor(), stubPhase, KEYWORD_CHECKS, config);

        var state = loop.execute(
                contextWithDrives("safety threat has failed"),
                result(List.of(emotion(EmotionType.FEAR, 0.9)),
                        Map.of("controllability", 0.1, "adjustability", 0.1)),
                PLANFUL, null, null);

        assertThat(state).isNotNull();
        assertThat(state.iteration()).isLessThanOrEqualTo(3);
        assertThat(callCount.get()).isLessThanOrEqualTo(3);
    }

    // --- Null generation: LLM failure breaks loop ---

    @Test
    void nullGeneration_breaksLoop() {
        var stubPhase = new CopingGenerationPhase(null) {
            @Override
            public CopingResult generate(AppraisalContext ctx, CopingState state,
                                          CopingStrategy pinned, CopingDefaults defaults) {
                return null;
            }
        };
        var loop = new CopingMicroLoop(new CopingStrategyPredictor(), stubPhase, KEYWORD_CHECKS);

        var state = loop.execute(
                context("crisis"),
                result(List.of(emotion(EmotionType.ANGER, 0.8)), HIGH_CTRL_DIMS),
                PLANFUL, null, null);

        assertThat(state).isNotNull();
        assertThat(state.strategiesAttempted()).isEmpty();
    }

    // --- Effects accumulation ---

    @Test
    void goalOperations_accumulateAcrossIterations() {
        var callCount = new AtomicInteger(0);
        var stubPhase = new CopingGenerationPhase(null) {
            @Override
            public CopingResult generate(AppraisalContext ctx, CopingState state,
                                          CopingStrategy pinned, CopingDefaults defaults) {
                int idx = callCount.getAndIncrement();
                if (idx == 0) {
                    return new CopingResult(CopingStrategy.PLANNING,
                            "I can manage this situation with a plan.",
                            List.of(new GoalOperation(GoalOperationType.CREATE,
                                    "find-route", "Find alternate path",
                                    DriveAxis.COMPETENCE, "Problem solving")),
                            List.of(), List.of());
                }
                return new CopingResult(CopingStrategy.SEEK_INSTRUMENTAL_SUPPORT,
                        "I should ask someone able to help for advice.",
                        List.of(new GoalOperation(GoalOperationType.CREATE,
                                "ask-for-help", "Seek guidance",
                                DriveAxis.AFFILIATION, "Social support")),
                        List.of(), List.of());
            }
        };
        var config = new CopingConfig(0.3, 3);
        var loop = new CopingMicroLoop(new CopingStrategyPredictor(), stubPhase, KEYWORD_CHECKS, config);

        var state = loop.execute(
                context("blocked and frustrated"),
                result(List.of(emotion(EmotionType.ANGER, 0.8)), HIGH_CTRL_DIMS),
                PLANFUL, null, null);

        assertThat(state).isNotNull();
        assertThat(state.goalOperations().size()).isGreaterThanOrEqualTo(1);
    }

    // --- SEC re-evaluation updates dimensions ---

    @Test
    void reEvaluation_updatesDimensionsFromNarrative() {
        var loop = new CopingMicroLoop(new CopingStrategyPredictor(),
                stubGenerationPhase("test", CopingStrategy.PLANNING), KEYWORD_CHECKS);

        var initialState = CopingState.initial("I feel trapped and helpless.",
                Map.of("controllability", 0.5), List.of(emotion(EmotionType.FEAR, 0.7)));

        var reEvaluated = loop.reEvaluate(context("trapped"), initialState);

        assertThat(reEvaluated.secDimensions()).isNotEmpty();
        assertThat(reEvaluated.secDimensions()).containsKey("controllability");
    }

    @Test
    void reEvaluation_agencyNarrative_increasesControllability() {
        var loop = new CopingMicroLoop(new CopingStrategyPredictor(),
                stubGenerationPhase("test", CopingStrategy.PLANNING), KEYWORD_CHECKS);

        var lowAgency = CopingState.initial("trapped and helpless, impossible stuck",
                Map.of("controllability", 0.2), List.of(emotion(EmotionType.FEAR, 0.7)));
        var highAgency = lowAgency.withInterpretation(
                "I can choose to take control and decide on a possible option");

        var reEvalLow = loop.reEvaluate(context("test"), lowAgency);
        var reEvalHigh = loop.reEvaluate(context("test"), highAgency);

        double lowCtrl = reEvalLow.secDimensions().getOrDefault("controllability", 0.5);
        double highCtrl = reEvalHigh.secDimensions().getOrDefault("controllability", 0.5);

        assertThat(highCtrl).isGreaterThan(lowCtrl);
    }

    // --- Coping opportunity threshold ---

    @Test
    void hasCopingOpportunity_aboveThreshold() {
        var loop = new CopingMicroLoop(new CopingStrategyPredictor(),
                stubGenerationPhase("test", CopingStrategy.PLANNING), KEYWORD_CHECKS);
        assertThat(loop.hasCopingOpportunity(List.of(emotion(EmotionType.ANGER, 0.7)))).isTrue();
    }

    @Test
    void hasCopingOpportunity_belowThreshold() {
        var loop = new CopingMicroLoop(new CopingStrategyPredictor(),
                stubGenerationPhase("test", CopingStrategy.PLANNING), KEYWORD_CHECKS);
        assertThat(loop.hasCopingOpportunity(List.of(emotion(EmotionType.ANGER, 0.4)))).isFalse();
    }

    @Test
    void hasCopingOpportunity_emptyEmotions() {
        var loop = new CopingMicroLoop(new CopingStrategyPredictor(),
                stubGenerationPhase("test", CopingStrategy.PLANNING), KEYWORD_CHECKS);
        assertThat(loop.hasCopingOpportunity(List.of())).isFalse();
    }

    // --- Helper: stub generation phase that returns a fixed result ---

    private static CopingGenerationPhase stubGenerationPhase(String narrative, CopingStrategy strategy) {
        return new CopingGenerationPhase(null) {
            @Override
            public CopingResult generate(AppraisalContext ctx, CopingState state,
                                          CopingStrategy pinned, CopingDefaults defaults) {
                return new CopingResult(strategy, narrative, List.of(), List.of(), List.of());
            }
        };
    }
}
