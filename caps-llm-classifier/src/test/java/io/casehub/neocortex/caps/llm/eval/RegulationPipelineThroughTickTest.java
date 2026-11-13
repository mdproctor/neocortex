package io.casehub.neocortex.caps.llm.eval;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.neocortex.cognition.appraisal.AppraisalContext;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.AppraisalStrategy;
import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognition.regulation.EmotionRegulationGenerationPhase;
import io.casehub.neocortex.cognition.regulation.EmotionRegulationResult;
import io.casehub.neocortex.cognition.regulation.EmotionRegulationStrategy;
import io.casehub.neocortex.cognition.regulation.ShameContext;
import io.casehub.neocortex.cognition.regulation.StrategyFailureSignal;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognitive.index.RegulationDefaults;
import io.casehub.neocortex.cognition.salience.ConcernBasedSalienceStrategy;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.PadProjection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mechanical (no LLM) pipeline test: verifies tick wiring from
 * CognitionCore.tick() → AppraisalTickParticipant → EmotionRegulationTickParticipant.
 *
 * Uses stubbed AppraisalStrategy and EmotionRegulationGenerationPhase to prove
 * the pipeline propagates results correctly without requiring API calls.
 */
class RegulationPipelineThroughTickTest {

    private static final String AGENT = "pipe-agent";
    private static final String TENANT = "pipe-tenant";

    @Test
    void betrayal_angerRegulatesViaCognitiveChange() {
        var emotions = List.of(
                new CognitiveEmotion(EmotionType.ANGER, 0.85, "betrayer",
                        Instant.now(), EmotionSource.INTRINSIC,
                        new PadProjection(-0.6, 0.7, 0.3)));
        var expected = new EmotionRegulationResult(
                EmotionRegulationStrategy.COGNITIVE_CHANGE,
                List.of("ANGER"), List.of("AFFILIATION"),
                0.85, 0.38, 0.55, "You reframe the betrayal as information.",
                null, null);

        assertRegulationPipeline(emotions, expected);
    }

    @Test
    void mortalDanger_fearRegulatesViaAttentionalDeployment() {
        var emotions = List.of(
                new CognitiveEmotion(EmotionType.FEAR, 0.92, "threat",
                        Instant.now(), EmotionSource.INTRINSIC,
                        new PadProjection(-0.8, 0.9, -0.5)));
        var expected = new EmotionRegulationResult(
                EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT,
                List.of("FEAR"), List.of("AUTONOMY"),
                0.92, 0.30, 0.67, "You redirect focus to the escape route.",
                null, null);

        assertRegulationPipeline(emotions, expected);
    }

    @Test
    void publicHumiliation_reproachRegulatesViaResponseModulation() {
        var emotions = List.of(
                new CognitiveEmotion(EmotionType.REPROACH, 0.78, "audience",
                        Instant.now(), EmotionSource.INTRINSIC,
                        new PadProjection(-0.5, 0.6, -0.3)));
        var expected = new EmotionRegulationResult(
                EmotionRegulationStrategy.RESPONSE_MODULATION,
                List.of("REPROACH"), List.of("COMPETENCE"),
                0.78, 0.40, 0.49, "You suppress the visible reaction.",
                null, null);

        assertRegulationPipeline(emotions, expected);
    }

    @Test
    void lowIntensity_noRegulationActivates() {
        var emotions = List.of(
                new CognitiveEmotion(EmotionType.JOY, 0.31, "friend",
                        Instant.now(), EmotionSource.INTRINSIC,
                        new PadProjection(0.5, 0.2, 0.3)));

        var config = CognitionConfig.none()
                .with("mood", true)
                .with("drives", true)
                .with("appraisal", true)
                .with("salience", true)
                .with("regulation", true);

        var descriptor = AgentDescriptor.builder()
                .agentId(AGENT).name("PipeAgent").slot("default").tenancyId(TENANT)
                .build();

        AppraisalStrategy stubbedAppraisal = ctx -> new AppraisalResult(emotions, List.of(), HabituationState.empty());

        var core = CognitionTestHarness.builder().withCognitionConfig(config).build().core();
        core.configureAppraisal(stubbedAppraisal, new ConcernBasedSalienceStrategy());

        // regulation with null generation phase — gate logic only
        core.configureRegulation();

        core.tick(AGENT, TENANT, descriptor, (a, t) -> Set.of(), null);
        core.tick(AGENT, TENANT, descriptor, (a, t) -> Set.of("other"), "A friend smiles warmly at you.");

        var appraisalResult = core.appraisalParticipant().currentResult(AGENT, TENANT);
        assertThat(appraisalResult).as("Appraisal result stored after tick").isPresent();
        assertThat(appraisalResult.get().emotions()).hasSize(1);

        // intensity 0.31, baseline 0.3 → discrepancy 0.01 → effort = 0.5 * 0.01 / 0.7 ≈ 0.007 < 0.01 noise floor
        var regResult = core.regulationParticipant().currentResult(AGENT, TENANT);
        assertThat(regResult).as("Low intensity should not trigger regulation").isEmpty();
    }

    private void assertRegulationPipeline(List<CognitiveEmotion> emotions,
                                           EmotionRegulationResult expected) {
        var config = CognitionConfig.none()
                .with("mood", true)
                .with("drives", true)
                .with("appraisal", true)
                .with("salience", true)
                .with("regulation", true);

        var descriptor = AgentDescriptor.builder()
                .agentId(AGENT).name("PipeAgent").slot("default").tenancyId(TENANT)
                .build();

        AppraisalStrategy stubbedAppraisal = ctx -> new AppraisalResult(emotions, List.of(), HabituationState.empty());

        EmotionRegulationGenerationPhase stubbedGeneration = new EmotionRegulationGenerationPhase(null) {
            @Override
            public EmotionRegulationResult generate(CognitionTickContext context,
                                                     AppraisalResult appraisal,
                                                     List<EmotionRegulationResult> history,
                                                     double regulationEffort,
                                                     double regulationFatigue,
                                                     StrategyFailureSignal failureSignal,
                                                     EmotionRegulationStrategy pinnedStrategy,
                                                     DispositionAxes axes,
                                                     EmotionType dominantEmotionType,
                                                     RegulationDefaults regulationDefaults,
                                                     ShameContext shameContext,
                                                     String situationContext,
                                                     java.util.@org.jspecify.annotations.Nullable Map<String, io.casehub.neocortex.cognition.prompt.observation.RegulationTracker.TrajectoryResult> distressTrajectories) {
                return expected;
            }
        };

        var core = CognitionTestHarness.builder().withCognitionConfig(config).build().core();
        core.configureAppraisal(stubbedAppraisal, new ConcernBasedSalienceStrategy());
        core.configureRegulation(stubbedGeneration);

        assertThat(core.regulationParticipant()).as("Regulation participant registered").isNotNull();

        // Tick 1: seed drives (no observation)
        core.tick(AGENT, TENANT, descriptor, (a, t) -> Set.of(), null);
        // Tick 2: observation triggers appraisal → regulation
        core.tick(AGENT, TENANT, descriptor, (a, t) -> Set.of("other"), "Test observation.");

        // Verify appraisal produced the stubbed result
        var appraisalResult = core.appraisalParticipant().currentResult(AGENT, TENANT);
        assertThat(appraisalResult).as("Appraisal result stored after tick").isPresent();
        assertThat(appraisalResult.get().emotions()).hasSameSizeAs(emotions);

        // Verify regulation activated and produced the expected result
        var regResult = core.regulationParticipant().currentResult(AGENT, TENANT);
        assertThat(regResult).as("Regulation result present").isPresent();
        assertThat(regResult.get().strategy()).isEqualTo(expected.strategy());
        assertThat(regResult.get().effectiveness()).isEqualTo(expected.effectiveness());
    }
}
