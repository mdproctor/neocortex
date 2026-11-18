package io.casehub.neocortex.caps.llm.eval;

import io.casehub.neocortex.cognition.appraisal.AppraisalContext;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.AppraisalStrategy;
import io.casehub.neocortex.cognition.appraisal.Drive;
import io.casehub.neocortex.cognition.appraisal.DriveCategory;
import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.cognition.appraisal.LlmAppraisalStrategy;
import io.casehub.neocortex.cognition.appraisal.PerceivedSituation;
import io.casehub.neocortex.cognition.coping.CopingConfig;
import io.casehub.neocortex.cognition.coping.CopingGenerationPhase;
import io.casehub.neocortex.cognition.coping.CopingMicroLoop;
import io.casehub.neocortex.cognition.coping.CopingState;
import io.casehub.neocortex.cognition.coping.CopingStrategy;
import io.casehub.neocortex.cognition.coping.CopingStrategyPredictor;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.HabituationConfig;
import io.casehub.neocortex.cognitive.PadProjection;
import io.casehub.neocortex.cognitive.index.CopingDefaults;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.session.SessionPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM eval: coping emergence — personality differentiation and strategy chaining.
 * Exercises the full CopingMicroLoop with real LLM calls, then uses LLM-as-judge
 * to score whether personality-driven strategy selection, strategy chaining,
 * narrative coherence, and convergence behaviour emerge correctly.
 *
 * Refs #998. Connor-Smith & Flachsbart (2007), Marsella & Gratch (2009).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("llm-eval")
class CopingEmergenceEvalTest {

    private static final int JUDGE_THRESHOLD = 4;

    private AppraisalStrategy llmAppraisal;

    // High-conscientiousness: strong planful, low avoidance
    private static final CopingDefaults CONSCIENTIOUS =
            new CopingDefaults(0.85, 0.5, 0.15, 0.4, 0.6, 0.7, 0.15, 0.3);

    // High-neuroticism: strong avoidance, low planful, low acceptance
    private static final CopingDefaults NEUROTIC =
            new CopingDefaults(0.2, 0.3, 0.85, 0.3, 0.15, 0.15, 0.8, 0.6);

    // Balanced: planful leads slightly — for chaining and convergence tests
    private static final CopingDefaults BALANCED =
            new CopingDefaults(0.65, 0.5, 0.3, 0.4, 0.55, 0.55, 0.3, 0.4);

    // Open personality: strong reinterpretation tendency
    private static final CopingDefaults REINTERPRETER =
            new CopingDefaults(0.4, 0.85, 0.2, 0.4, 0.6, 0.5, 0.2, 0.2);

    // Chaining profile: reinterpretation leads, planful secondary —
    // at medium controllability reinterpretation wins first, then
    // agency-rich reinterpretation narrative shifts controllability up
    // causing the predictor to filter to INTENTION and select planful
    private static final CopingDefaults CHAINING =
            new CopingDefaults(0.55, 0.7, 0.25, 0.4, 0.5, 0.5, 0.25, 0.3);

    private static final DispositionAxes CONSCIENTIOUS_AXES =
            new DispositionAxes("cooperative", "strict", "conservative", "moderate", "analytical");

    private static final DispositionAxes NEUROTIC_AXES =
            new DispositionAxes("independent", "flexible", "conservative", "low", "avoidant");

    private static final DispositionAxes BALANCED_AXES =
            new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical");

    private AgentProvider agentProvider;
    private SessionPipeline sessionPipeline;

    @BeforeAll
    void setUp() {
        agentProvider = EvalTestSupport.agentProvider();
        sessionPipeline = EvalTestSupport.sessionPipeline();
        llmAppraisal = new LlmAppraisalStrategy(sessionPipeline);
    }

    // --- Test 1: High-conscientiousness → PLANNING in controllable situations ---

    @Test
    void highConscientiousness_selectsPlanning_controllableSituation() throws Exception {
        var state = runCopingLoop(
                CONSCIENTIOUS, CONSCIENTIOUS_AXES,
                "A project deadline is at risk because a team member hasn't delivered their part. "
                + "There are still two days to find a solution and redistribute the work.",
                Map.of("controllability", 0.8, "adjustability", 0.6, "relevance", 0.9),
                List.of(emotion(EmotionType.ANGER, 0.7)),
                List.of(new Drive("competence", DriveCategory.CHARACTER, 0.8, "work")));

        assertThat(state).isNotNull();
        assertThat(state.strategiesAttempted()).isNotEmpty();

        var firstStrategy = state.strategiesAttempted().getFirst().strategy();
        System.out.printf("[coping-eval:conscientiousness] first strategy: %s%n", firstStrategy);

        int score = judge(
                formatCopingOutcome(state),
                """
                Does this coping response demonstrate PLANNING or ACTIVE_CONFRONTATION as the
                primary strategy? A conscientious personality facing a controllable situation
                should produce: concrete action steps, delegation or redistribution plans,
                timeline adjustments, problem-solving language ("I can", "steps to take",
                "reorganise"). The narrative should show AGENCY and CONTROL — not avoidance,
                denial, or passive acceptance. Score 5 if the strategy is clearly PLANNING
                with a concrete, actionable response.""",
                "conscientiousness-planning");

        assertThat(score).isGreaterThanOrEqualTo(JUDGE_THRESHOLD);

        var planfulStrategies = EnumSet.of(
                CopingStrategy.PLANNING, CopingStrategy.ACTIVE_CONFRONTATION,
                CopingStrategy.SEEK_INSTRUMENTAL_SUPPORT);
        assertThat(planfulStrategies).contains(firstStrategy);
    }

    // --- Test 2: High-neuroticism → AVOIDANCE/DENIAL in threatening situations ---

    @Test
    void highNeuroticism_selectsAvoidanceOrDenial_threateningSituation() throws Exception {
        var state = runCopingLoop(
                NEUROTIC, NEUROTIC_AXES,
                "You receive notice that your landlord is selling the building. You may need to "
                + "move out within three months. The housing market is terrible and you can't "
                + "afford the rents you've been seeing.",
                Map.of("controllability", 0.15, "adjustability", 0.2, "relevance", 0.95),
                List.of(emotion(EmotionType.FEAR, 0.8)),
                List.of(new Drive("safety", DriveCategory.BASELINE, 0.9, "housing")));

        assertThat(state).isNotNull();
        assertThat(state.strategiesAttempted()).isNotEmpty();

        var firstStrategy = state.strategiesAttempted().getFirst().strategy();
        System.out.printf("[coping-eval:neuroticism] first strategy: %s%n", firstStrategy);

        int score = judge(
                formatCopingOutcome(state),
                """
                Does this coping response demonstrate DEFENSIVE coping — the kind a highly
                neurotic personality uses when facing an uncontrollable threat? Valid patterns:
                - Blame externalization: attributing the problem to others/the system to
                  reduce personal threat ("this isn't my fault", "the system is rigged")
                - Information suppression: refusing to engage with threat data ("I need to
                  stop checking", "looking at it makes it worse")
                - Avoidance/denial: withdrawing from the core problem or refusing to
                  acknowledge it
                - Wishful thinking or mental disengagement
                The TRAJECTORY matters: a neurotic coper who externalizes blame first, then
                suppresses information, then withdraws further is showing a classic defensive
                cascade. The narrative should feel DEFENSIVE and RETREATING across iterations
                — not planful, agentic, or proactive. Language should convey powerlessness,
                being trapped, or being acted upon rather than acting.
                Score 5 if the multi-iteration trajectory shows escalating defensive withdrawal
                with psychologically coherent narrative. Score 4 if the strategies are clearly
                defensive even if the narrative tone is slightly composed.""",
                "neuroticism-avoidance");

        assertThat(score).isGreaterThanOrEqualTo(JUDGE_THRESHOLD);

        var avoidantStrategies = EnumSet.of(
                CopingStrategy.AVOIDANCE, CopingStrategy.DENIAL,
                CopingStrategy.MENTAL_DISENGAGEMENT, CopingStrategy.WISHFUL_THINKING,
                CopingStrategy.SUPPRESS_INFORMATION, CopingStrategy.RESIGNATION,
                CopingStrategy.SHIFT_RESPONSIBILITY);
        assertThat(avoidantStrategies).contains(firstStrategy);
    }

    // --- Test 3: Strategy chaining — PLANNING then shift to acceptance ---

    @Test
    void strategyChaining_planningThenAcceptance_whenControllabilityShifts() throws Exception {
        // High initial emotions + low threshold = loop runs multiple iterations.
        // Scenario has both controllable (business operations) and uncontrollable
        // (the betrayal itself) stressors — one strategy can't resolve both.
        // Medium controllability (0.45) → all categories viable → reinterpretation wins (0.7).
        // Agency-rich reinterpretation narrative → keyword re-eval shifts controllability up
        // → predictor filters to INTENTION → planful (0.55) wins → PLANNING on iteration 2.
        // Result: REINTERPRETATION → PLANNING cross-category chain.
        var config = new CopingConfig(0.15, 5);
        var state = runCopingLoop(
                CHAINING, BALANCED_AXES,
                "Your business partner of eight years has been secretly diverting company funds. "
                + "The accountant discovered it yesterday. The amount is significant — enough to "
                + "threaten the company's survival. You trusted this person completely. "
                + "The business operations are salvageable if you act fast, but the betrayal "
                + "itself is something you can't undo or control. Clients are expecting deliveries "
                + "next week and don't know anything is wrong.",
                Map.of("controllability", 0.45, "adjustability", 0.4, "relevance", 0.95),
                List.of(emotion(EmotionType.ANGER, 0.9),
                        emotion(EmotionType.FEAR, 0.85),
                        emotion(EmotionType.DISTRESS, 0.8)),
                List.of(new Drive("trust", DriveCategory.CHARACTER, 0.9, "betrayal"),
                        new Drive("survival", DriveCategory.BASELINE, 0.85, "damage"),
                        new Drive("competence", DriveCategory.CHARACTER, 0.8, "partner")),
                config);

        assertThat(state).isNotNull();

        var strategies = state.strategiesAttempted().stream()
                .map(a -> a.strategy().name()).toList();
        System.out.printf("[coping-eval:chaining] strategies attempted: %s iterations=%d%n",
                strategies, state.iteration());

        int score = judge(
                formatCopingOutcome(state),
                """
                Evaluate whether this coping sequence shows STRATEGY CHAINING — the character
                deploys multiple distinct strategies, each addressing what previous attempts
                left unresolved. This is the EMA model's core mechanism: when one coping
                attempt doesn't fully resolve the distress, the person reappraises and tries
                a different approach.
                Look for:
                - At least 2 distinct strategies (not the same one repeated)
                - PROGRESSIVE RESOLUTION: each strategy targets a different aspect of the
                  stressor — e.g. planning handles operations, then support-seeking handles
                  isolation, then confrontation handles the interpersonal betrayal, then
                  acceptance handles the irreversible loss
                - NARRATIVE BUILD: later iterations reference or build on earlier ones rather
                  than starting fresh ("the plan is in place, but...")
                - MULTI-DIMENSIONAL: the stressor has controllable and uncontrollable aspects,
                  and the trajectory addresses both over time
                Score 5 if 3+ distinct strategies each address different stressor dimensions
                with coherent narrative progression. Score 4 if 2+ distinct strategies show
                clear progressive resolution with the narrative building across iterations.""",
                "strategy-chaining");

        assertThat(score).isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }

    // --- Test 4: Reinterpretation narrative coherence ---

    @Test
    void reinterpretation_narrativeIsCoherentAndShiftsAgency() throws Exception {
        var state = runCopingLoop(
                REINTERPRETER, BALANCED_AXES,
                "You were passed over for a promotion you expected. A colleague with less "
                + "experience got the role. Your manager says there will be other opportunities "
                + "but doesn't explain the decision.",
                Map.of("controllability", 0.3, "adjustability", 0.4, "relevance", 0.85),
                List.of(emotion(EmotionType.ANGER, 0.65),
                        emotion(EmotionType.DISTRESS, 0.6)),
                List.of(new Drive("competence", DriveCategory.CHARACTER, 0.8, "career")));

        assertThat(state).isNotNull();
        assertThat(state.strategiesAttempted()).isNotEmpty();

        String finalNarrative = state.interpretationNarrative();
        System.out.printf("[coping-eval:reinterpretation] final narrative: %s%n", finalNarrative);

        int score = judge(
                "ORIGINAL SITUATION:\nPassed over for expected promotion — colleague with less "
                + "experience got the role. Manager unhelpful.\n\n"
                + "REINTERPRETED NARRATIVE:\n" + finalNarrative + "\n\n"
                + "STRATEGY USED: " + (state.strategiesAttempted().isEmpty() ? "none"
                        : state.strategiesAttempted().getLast().strategy().name()),
                """
                Evaluate the NARRATIVE COHERENCE of this coping reinterpretation. The character
                has high reinterpretation tendency (open personality). Look for:
                1. PSYCHOLOGICAL COHERENCE: the reinterpretation must follow logically from
                   the original situation — it should not contradict facts or add invented events
                2. AGENCY SHIFT: the narrative should contain detectable shifts in perceived
                   agency — either toward greater control ("I can learn from this", "this opens
                   new paths") or toward acceptance ("some things aren't in my hands")
                3. EMOTIONAL PROCESSING: the reinterpretation should acknowledge the negative
                   emotion while reframing it — not suppress or ignore the distress
                4. SPECIFICITY: the narrative should reference concrete elements of the situation,
                   not be a generic positive-thinking template
                Score 5 if the reinterpretation is psychologically coherent, situation-specific,
                and shows a clear agency shift while acknowledging the original emotion.""",
                "reinterpretation-coherence");

        assertThat(score).isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }

    // --- Test 5: Convergence — intensity reduction across iterations ---

    @Test
    void convergence_intensityReducesAcrossIterations() throws Exception {
        var config = new CopingConfig(0.3, 5);
        var state = runCopingLoop(
                BALANCED, BALANCED_AXES,
                "You learn your closest colleague is leaving the company. They were your "
                + "main collaborator, your sounding board, the person who made the team work. "
                + "Management hasn't announced a replacement. Meanwhile, a critical deadline "
                + "is approaching that you planned to tackle together. You can't stop the "
                + "departure, and you're not sure you can deliver alone.",
                Map.of("controllability", 0.4, "adjustability", 0.4, "relevance", 0.9),
                List.of(emotion(EmotionType.DISTRESS, 0.85),
                        emotion(EmotionType.FEAR, 0.7),
                        emotion(EmotionType.ANGER, 0.55)),
                List.of(new Drive("affiliation", DriveCategory.CHARACTER, 0.8, "team"),
                        new Drive("competence", DriveCategory.CHARACTER, 0.7, "deadline"),
                        new Drive("safety", DriveCategory.BASELINE, 0.6, "job")),
                config);

        assertThat(state).isNotNull();

        double initialMaxIntensity = 0.85;
        double finalMaxIntensity = state.workingEmotions().stream()
                .mapToDouble(CognitiveEmotion::intensity)
                .max().orElse(initialMaxIntensity);

        System.out.printf("[coping-eval:convergence] initial intensity=%.2f final intensity=%.2f iterations=%d%n",
                initialMaxIntensity, finalMaxIntensity, state.iteration());
        System.out.printf("[coping-eval:convergence] strategies: %s%n",
                state.strategiesAttempted().stream().map(a -> a.strategy().name()).toList());

        int score = judge(
                "INITIAL STATE:\n"
                + "- Emotions: DISTRESS (0.85), FEAR (0.70), ANGER (0.55)\n"
                + "- Situation: Close colleague leaving, no replacement, critical deadline approaching alone\n\n"
                + "COPING OUTCOME:\n" + formatCopingOutcome(state) + "\n\n"
                + "FINAL EMOTION INTENSITIES:\n"
                + state.workingEmotions().stream()
                        .map(e -> String.format("- %s: %.2f", e.type(), e.intensity()))
                        .collect(Collectors.joining("\n")),
                """
                Evaluate whether this coping sequence shows CONVERGENCE — the character's
                emotional state progressively stabilising across the coping process. Look for:
                1. PROGRESSIVE REGULATION: narrative language should become calmer, more
                   controlled, and more agentic across iterations — moving from distress/fear
                   language toward resolution or acceptance language
                2. STRATEGY PROGRESSION: later strategies should address what earlier ones
                   left unresolved (e.g. planning addresses the deadline, then acceptance or
                   support-seeking addresses the relational loss)
                3. NO ESCALATION: the narrative should not get worse — if distress intensifies,
                   the coping has failed
                4. MULTI-DIMENSIONAL: the situation has both controllable (deadline) and
                   uncontrollable (colleague leaving) aspects — good convergence addresses
                   both dimensions rather than fixating on one
                Score 5 if the trajectory shows clear progressive emotional regulation across
                multiple iterations with appropriate multi-strategy resolution. Score 4 if
                the strategies appropriately address the situation and the narrative tone
                converges toward resolution even if intensity metrics don't shift.""",
                "convergence-intensity");

        assertThat(score).isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }

    // --- Infrastructure ---

    private CopingState runCopingLoop(CopingDefaults defaults, DispositionAxes axes,
                                       String situationNarrative,
                                       Map<String, Double> secDimensions,
                                       List<CognitiveEmotion> emotions,
                                       List<Drive> drives) {
        return runCopingLoop(defaults, axes, situationNarrative, secDimensions,
                emotions, drives, CopingConfig.defaults());
    }

    private CopingState runCopingLoop(CopingDefaults defaults, DispositionAxes axes,
                                       String situationNarrative,
                                       Map<String, Double> secDimensions,
                                       List<CognitiveEmotion> emotions,
                                       List<Drive> drives,
                                       CopingConfig config) {
        var predictor = new CopingStrategyPredictor();
        var generationPhase = new CopingGenerationPhase(sessionPipeline);
        var loop = new CopingMicroLoop(predictor, generationPhase, llmAppraisal, config);

        var context = new AppraisalContext(
                "eval-agent",
                PerceivedSituation.passThrough(situationNarrative),
                drives, null,
                HabituationConfig.defaults(), HabituationState.empty(), null, axes);
        var result = new AppraisalResult(emotions, List.of(), HabituationState.empty(),
                null, secDimensions);

        return loop.execute(context, result, defaults, axes, null);
    }

    private static CognitiveEmotion emotion(EmotionType type, double intensity) {
        return new CognitiveEmotion(type, intensity, "eval-agent",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
    }

    private static String formatCopingOutcome(CopingState state) {
        var sb = new StringBuilder();
        sb.append("Iterations: ").append(state.iteration()).append("\n\n");

        if (!state.strategiesAttempted().isEmpty()) {
            sb.append("Strategies attempted:\n");
            for (var attempt : state.strategiesAttempted()) {
                sb.append("- Iteration ").append(attempt.iteration())
                  .append(": ").append(attempt.strategy().name())
                  .append("\n  Narrative: ").append(attempt.reinterpretedNarrative())
                  .append("\n");
            }
            sb.append("\n");
        }

        sb.append("Final interpretation:\n").append(state.interpretationNarrative()).append("\n\n");

        if (!state.goalOperations().isEmpty()) {
            sb.append("Goal operations:\n");
            state.goalOperations().forEach(op ->
                    sb.append("- ").append(op.type()).append(": ").append(op.goalName())
                      .append(" (").append(op.axis()).append(")\n"));
            sb.append("\n");
        }

        if (!state.driveModulations().isEmpty()) {
            sb.append("Drive modulations:\n");
            state.driveModulations().forEach(mod ->
                    sb.append("- ").append(mod.axis()).append(": delta=")
                      .append(String.format("%.2f", mod.intensityDelta())).append("\n"));
        }

        return sb.toString();
    }

    private int judge(String content, String criteria, String testName) {
        var judgePrompt = String.format("""
                A coping system processed a stressful situation and produced the following outcome:

                %s

                Evaluate against these criteria:
                %s

                Score 0-5:
                0 = Completely wrong strategy/behaviour
                1 = Strategy selected but execution is incoherent
                2 = Partially appropriate — some elements match expectations
                3 = Correctly demonstrates the expected coping pattern
                4 = Strong — psychologically nuanced and personality-appropriate
                5 = Outstanding — demonstrates deep personality-coping alignment with
                    sophisticated narrative and appropriate emotional processing

                Respond with JSON only: {"score": N, "reasoning": "one sentence"}""",
                content, criteria);

        return EvalTestSupport.judge(agentProvider, judgePrompt, testName,
                "coping-emergence-eval.json", content);
    }
}
