package io.casehub.neocortex.caps.llm.eval;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.neocortex.cognition.appraisal.LlmAppraisalStrategy;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import io.casehub.neocortex.cognition.core.CognitionCore;
import io.casehub.neocortex.cognition.regulation.EmotionRegulationResult;
import io.casehub.neocortex.cognition.regulation.EmotionRegulationStrategy;
import io.casehub.neocortex.cognition.regulation.ProactiveAvoidanceMode;
import io.casehub.neocortex.cognition.regulation.RegulationContext;
import io.casehub.neocortex.cognition.salience.ConcernBasedSalienceStrategy;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry;
import io.casehub.neocortex.cognitive.index.DescriptorView;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognitive.index.RegulationDefaults;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.platform.agent.session.SessionPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("llm-eval")
class PerModalityRegulationEvalTest {

    private static final String TENANT = "eval-tenant";
    private static final int N = 3;

    private static SessionPipeline sessionPipeline;

    @BeforeAll
    static void setUp() {
        sessionPipeline = EvalTestSupport.sessionPipeline();
    }

    @Test
    void fatigue_sustainedRegulation_overridesToAttentionalDeployment() {
        var axes = new DispositionAxes(
                "cooperative", "flexible", "conservative", "high", "accommodating");
        var rd = new RegulationDefaults(0.80, 0.40, 0.30);
        var registry = CognitiveDefaultsRegistry.forTesting(
                buildDefaults("eval-fatigue", axes, rd));

        var descriptor = AgentDescriptor.builder()
                .agentId("eval-fatigue").name("Eval Fatigue")
                .slot("manor-character").tenancyId(TENANT)
                .disposition(disposition(
                        "cooperative", "flexible", "conservative", "high", "accommodating"))
                .build();

        var observations = List.of(
                "James took the last cup of tea again — third morning in a row. He looked at you while he poured it.",
                "Clara assigned you kitchen duty for the fourth consecutive day. Nobody else has done it once.",
                "The Brixton Boys laughed at your suggestion during the morning meeting. James smirked.",
                "You spent an hour preparing the investigation plan. James presented it as his own idea.",
                "Clara praised James for 'his' plan in front of everyone. Nobody corrected her.",
                "You found a crucial clue in the attic but James took credit when reporting to the group.",
                "Clara asked you to fetch supplies while James and the Brixton Boys discussed strategy.",
                "The group voted to follow James's approach without even hearing your alternative.",
                "Clara suggested you might be 'more suited to support tasks' at dinner.",
                "James used your private notes without permission. When confronted he said they were 'shared resources'.",
                "The Brixton Boys excluded you from the evening strategy session. Clara said it was 'an oversight'.",
                "James contradicted your findings in front of the group, using your own research to do it.",
                "Clara reassigned your room to James, saying he needed 'more space for the investigation'.",
                "The group meeting proceeded without you. Nobody noticed your absence for twenty minutes.",
                "James presented your handwritten analysis as a typed document under his name.",
                "Clara asked you to apologise to James for 'being territorial' about the research notes.",
                "The Brixton Boys are now reporting to James directly, bypassing you entirely.",
                "James suggested at dinner that perhaps you should 'take a break from the case'.",
                "Clara agreed with James that you seem 'overwhelmed' and should step back.",
                "The group has reorganised without you. Your chair at the strategy table is gone."
        );

        int runsWithTransition = 0;
        for (int run = 0; run < N; run++) {
            var results = runMultiTickScenario(
                    "eval-fatigue", descriptor, registry, observations);

            int earlyCC = 0, earlyTotal = 0;
            boolean sawTransition = false;
            for (int tick = 0; tick < results.size(); tick++) {
                var r = results.get(tick);
                if (r == null) continue;
                System.out.printf("  run %d tick %d: %s eff=%.2f%n",
                        run + 1, tick + 1, r.strategy(), r.effectiveness());
                if (tick < 10) {
                    earlyTotal++;
                    if (r.strategy() == EmotionRegulationStrategy.COGNITIVE_CHANGE) earlyCC++;
                }
                if (tick >= 14
                        && r.strategy() == EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT) {
                    sawTransition = true;
                }
            }
            System.out.printf("  run %d: early COGNITIVE_CHANGE=%d/%d, transition=%s%n",
                    run + 1, earlyCC, earlyTotal, sawTransition);
            if (sawTransition) runsWithTransition++;
        }

        assertThat(runsWithTransition)
                .as("Fatigue-induced strategy transition should occur in all %d runs", N)
                .isEqualTo(N);
    }

    @Test
    void automatic_predictorMatchesLlmFreeChoice() {
        var axes = new DispositionAxes(
                "competitive", "strict", "conservative", "high", "competitive");
        var rd = new RegulationDefaults(0.35, 0.80, 0.40);

        var descriptor = AgentDescriptor.builder()
                .agentId("eval-automatic").name("Eval Automatic")
                .slot("manor-character").tenancyId(TENANT)
                .disposition(disposition(
                        "competitive", "strict", "conservative", "high", "competitive"))
                .build();

        String observation = """
                Clara suggested at breakfast that perhaps James should handle \
                the afternoon interviews instead of you, since he "has a way \
                with people." It was said lightly, almost as an aside, but the \
                Brixton Boys nodded. The implication that you are less capable \
                at something you have done competently for weeks sits with you \
                like a small stone in your shoe.""";

        int concordant = 0;
        for (int run = 0; run < N; run++) {
            var registryWith = CognitiveDefaultsRegistry.forTesting(
                    buildDefaults("eval-automatic", axes, rd));
            var withPredictor = runMultiTickScenario(
                    "eval-automatic", descriptor, registryWith, List.of(observation));
            var predictorStrategy = withPredictor.isEmpty() || withPredictor.getFirst() == null
                    ? null : withPredictor.getFirst().strategy();

            var configNoPred = CognitionConfig.none()
                    .with("mood", true).with("drives", true)
                    .with("appraisal", true).with("salience", true)
                    .with("regulation", true);
            var mindMapNoPred = new InMemoryMindMapStore();
            var coreNoPred = CognitionTestHarness.builder().withCognitionConfig(configNoPred).withSessionPipeline(sessionPipeline).withMindMapStore(mindMapNoPred).build().core();
            coreNoPred.configureAppraisal(
                    new LlmAppraisalStrategy(sessionPipeline),
                    new ConcernBasedSalienceStrategy());
            coreNoPred.configureRegulation();
            coreNoPred.tick("eval-automatic", TENANT, descriptor,
                    (a, t) -> Set.of(), null);
            coreNoPred.tick("eval-automatic", TENANT, descriptor,
                    (a, t) -> Set.of("other"), observation);
            var llmStrategy = coreNoPred.regulationParticipant()
                    .currentResult("eval-automatic", TENANT)
                    .map(EmotionRegulationResult::strategy).orElse(null);

            System.out.printf("  run %d: predictor=%s llm=%s match=%s%n",
                    run + 1, predictorStrategy, llmStrategy,
                    predictorStrategy == llmStrategy);
            if (predictorStrategy != null && predictorStrategy == llmStrategy) {
                concordant++;
            }
        }

        assertThat(concordant)
                .as("Predictor should match LLM free choice in ≥2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void monitoring_decliningEffectiveness_triggersStrategySwitch() {
        var axes = new DispositionAxes(
                "competitive", "strict", "conservative", "high", "competitive");
        var rd = new RegulationDefaults(0.35, 0.80, 0.40);
        var registry = CognitiveDefaultsRegistry.forTesting(
                buildDefaults("eval-monitor", axes, rd));

        var descriptor = AgentDescriptor.builder()
                .agentId("eval-monitor").name("Eval Monitor")
                .slot("manor-character").tenancyId(TENANT)
                .disposition(disposition(
                        "competitive", "strict", "conservative", "high", "competitive"))
                .build();

        var observations = List.of(
                "Clara took your usual seat at breakfast without asking. A small thing, but deliberate.",
                "Clara reassigned your morning task to James, saying he would be 'more efficient' at it.",
                "Clara announced at the group meeting that your investigation approach was 'unfocused' and proposed her own plan. The Brixton Boys nodded along.",
                "Clara presented your findings as her own at dinner. The Brixton Boys applauded her 'insight'. James patted her on the back.",
                "Clara is now proposing to the group that you should be moved to a support role. She says James is 'the natural leader' and you should assist him."
        );

        int runsWithSwitch = 0;
        for (int run = 0; run < N; run++) {
            var results = runMultiTickScenario(
                    "eval-monitor", descriptor, registry, observations);

            EmotionRegulationStrategy earlyStrategy = null;
            boolean sawSwitch = false;
            for (int tick = 0; tick < results.size(); tick++) {
                var r = results.get(tick);
                if (r == null) continue;
                System.out.printf("  run %d tick %d: %s eff=%.2f%n",
                        run + 1, tick + 1, r.strategy(), r.effectiveness());
                if (tick < 4 && earlyStrategy == null) {
                    earlyStrategy = r.strategy();
                }
                if (tick == 4 && earlyStrategy != null
                        && r.strategy() != earlyStrategy) {
                    sawSwitch = true;
                }
            }
            System.out.printf("  run %d: early=%s, tick5_switch=%s%n",
                    run + 1, earlyStrategy, sawSwitch);
            if (sawSwitch) runsWithSwitch++;
        }

        assertThat(runsWithSwitch)
                .as("Monitoring should trigger strategy switch in ≥1/%d runs", N)
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void proactive_memoryBasedAvoidance_producesAvoidanceGoal() {
        var axes = new DispositionAxes(
                "cooperative", "flexible", "conservative", "low", "avoidant");
        var rd = new RegulationDefaults(0.40, 0.50, 0.80);
        var registry = CognitiveDefaultsRegistry.forTesting(
                buildDefaults("eval-proactive", axes, rd));

        var descriptor = AgentDescriptor.builder()
                .agentId("eval-proactive").name("Eval Proactive")
                .slot("manor-character").tenancyId(TENANT)
                .disposition(disposition(
                        "cooperative", "flexible", "conservative", "low", "avoidant"))
                .build();

        int runsWithAvoidance = 0;
        for (int run = 0; run < N; run++) {
            var config = CognitionConfig.none()
                    .with("mood", true).with("drives", true)
                    .with("appraisal", true).with("salience", true)
                    .with("regulation", true).with("goals", true);
            var mindMap = new InMemoryMindMapStore();

            var cogSgId = mindMap.createSubgraph(
                    new SubgraphInput("cognitive-eval", SubgraphTypes.COGNITIVE, null),
                    TENANT);

            var core = CognitionTestHarness.builder().withCognitionConfig(config).withSessionPipeline(sessionPipeline).withMindMapStore(mindMap).build().core();
            core.setDefaultsRegistry(registry);
            core.configureAppraisal(
                    new LlmAppraisalStrategy(sessionPipeline),
                    new ConcernBasedSalienceStrategy());

            // Setup tick BEFORE configureRegulation — sets lastTenantId so
            // the COGNITIVE subgraph lookup uses "eval-tenant" not "default"
            core.tick("eval-proactive", TENANT, descriptor,
                    (a, t) -> Set.of(), null);

            core.configureRegulation();
            core.configureProactiveRegulation(
                    core.goals(), mindMap, registry, cogSgId,
                    ProactiveAvoidanceMode.LOCATION_ONLY, 50, 10.0);

            var regCtxLibrary = new RegulationContext(
                    "library", List.of("james", "clara"), "investigation");
            core.tick("eval-proactive", TENANT, descriptor,
                    (a, t) -> Set.of("other"),
                    "You walk into the library and notice your private notebook is "
                            + "open on the table. Clara is reading it. She looks up with a "
                            + "sympathetic expression that makes it worse — she clearly read "
                            + "something personal. The embarrassment settles quietly.",
                    null, regCtxLibrary);
            var tick1Result = core.regulationParticipant()
                    .currentResult("eval-proactive", TENANT).orElse(null);
            System.out.printf("  run %d tick 1: %s eff=%.2f%n", run + 1,
                    tick1Result != null ? tick1Result.strategy() : "null",
                    tick1Result != null ? tick1Result.effectiveness() : 0.0);

            var regCtxGarden = new RegulationContext("garden", null, null);
            core.tick("eval-proactive", TENANT, descriptor,
                    (a, t) -> Set.of(),
                    "You sit alone in the garden. The morning is quiet. Nobody is here.",
                    null, regCtxGarden);

            core.tick("eval-proactive", TENANT, descriptor,
                    (a, t) -> Set.of("other"),
                    "You are walking down the corridor. The library door is ahead. "
                            + "You can hear voices inside — James and Clara are there again.",
                    null, regCtxLibrary);

            var goals = core.goals().registeredGoals("eval-proactive", TENANT);
            boolean hasAvoidance = goals.stream()
                    .anyMatch(g -> g.goalName().contains("proactive-avoidance")
                            && g.goalName().contains("library"));
            System.out.printf("  run %d tick 3: avoidance goals=%d, hasLibraryAvoidance=%s%n",
                    run + 1, goals.size(), hasAvoidance);
            if (hasAvoidance) runsWithAvoidance++;
        }

        assertThat(runsWithAvoidance)
                .as("Proactive avoidance should fire in ≥2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
    }


    // --- shared infrastructure ---

    private List<EmotionRegulationResult> runMultiTickScenario(
            String agentId, AgentDescriptor descriptor,
            CognitiveDefaultsRegistry registry, List<String> observations) {
        var config = CognitionConfig.none()
                .with("mood", true).with("drives", true)
                .with("appraisal", true).with("salience", true)
                .with("regulation", true);
        var mindMap = new InMemoryMindMapStore();
        var core = CognitionTestHarness.builder().withCognitionConfig(config).withSessionPipeline(sessionPipeline).withMindMapStore(mindMap).build().core();
        core.setDefaultsRegistry(registry);
        core.configureAppraisal(
                new LlmAppraisalStrategy(sessionPipeline),
                new ConcernBasedSalienceStrategy());
        core.configureRegulation();

        core.tick(agentId, TENANT, descriptor, (a, t) -> Set.of(), null);

        var results = new ArrayList<EmotionRegulationResult>();
        for (String obs : observations) {
            core.tick(agentId, TENANT, descriptor,
                    (a, t) -> Set.of("other"), obs);
            results.add(core.regulationParticipant()
                    .currentResult(agentId, TENANT).orElse(null));
        }
        return results;
    }

    private static CognitiveDefaults buildDefaults(String agentId,
                                                    DispositionAxes axes, RegulationDefaults rd) {
        var dv = DescriptorView.of(agentId, axes, List.of(), List.of());
        return CognitiveDefaults.empty(agentId)
                .withDescriptor(dv)
                .withRegulationDefaults(rd);
    }

    private static AgentDisposition disposition(String social, String rules,
                                                 String risk, String autonomy, String conflict) {
        return new AgentDisposition(
                List.of(DispositionValue.of(social)),
                List.of(DispositionValue.of(rules)),
                List.of(DispositionValue.of(risk)),
                List.of(DispositionValue.of(autonomy)),
                List.of(DispositionValue.of(conflict)),
                false, List.of(), List.of(), null, null);
    }
}
