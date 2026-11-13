package io.casehub.neocortex.caps.llm.eval;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.neocortex.cognition.appraisal.LlmAppraisalStrategy;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import io.casehub.neocortex.cognition.regulation.EmotionRegulationResult;
import io.casehub.neocortex.cognition.regulation.EmotionRegulationStrategy;
import io.casehub.neocortex.cognition.salience.ConcernBasedSalienceStrategy;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry;
import io.casehub.neocortex.cognitive.index.DescriptorView;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognitive.index.RegulationDefaults;
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
class PerStrategyRegulationEvalTest {

    private static final String TENANT = "eval-tenant";
    private static final int N = 3;

    private static SessionPipeline sessionPipeline;
    private static CognitiveDefaultsRegistry defaultsRegistry;

    @BeforeAll
    static void setUp() {
        sessionPipeline = EvalTestSupport.sessionPipeline();
        defaultsRegistry = CognitiveDefaultsRegistry.forTesting(
                buildDefaults("eval-sit-select",
                        new DispositionAxes("cooperative", "flexible", "conservative", "low", "avoidant"),
                        new RegulationDefaults(0.40, 0.50, 0.60)),
                buildDefaults("eval-sit-modify",
                        new DispositionAxes("competitive", "flexible", "bold", "high", "competitive"),
                        new RegulationDefaults(0.40, 0.40, 0.30)),
                buildDefaults("eval-attn-deploy",
                        new DispositionAxes("cooperative", "flexible", "bold", "low", "protective"),
                        new RegulationDefaults(0.50, 0.50, 0.60)),
                buildDefaults("eval-cog-change",
                        new DispositionAxes("cooperative", "flexible", "conservative", "high", "accommodating"),
                        new RegulationDefaults(0.80, 0.40, 0.30)),
                buildDefaults("eval-resp-mod",
                        new DispositionAxes("competitive", "strict", "conservative", "high", "competitive"),
                        new RegulationDefaults(0.35, 0.80, 0.40))
        );
    }

    @Test
    void situationSelection_shameWithAvoidantConflict_selectsAvoidance() {
        String observation = """
                             You misremembered Clara's birthday and gave her a gift \
                             yesterday — her birthday is actually next week. She thanked \
                             you graciously in front of everyone, which made it worse. \
                             Now you are about to walk into the drawing room where Clara \
                             and James are having tea. You know she will mention it kindly \
                             and the embarrassment will start again.""";

        var descriptor = AgentDescriptor.builder()
                                        .agentId("eval-sit-select").name("Eval SitSelect").slot("manor-character").tenancyId(TENANT)
                                        .disposition(disposition("cooperative", "flexible", "conservative", "low", "avoidant"))
                                        .build();

        var results = runScenario("eval-sit-select", descriptor, observation, N);
        int matched = countMatching(results, Set.of(EmotionRegulationStrategy.SITUATION_SELECTION));
        System.out.printf("SITUATION_SELECTION: %d/%d matched%n", matched, N);
        assertThat(matched)
                .as("SITUATION_SELECTION should be selected in at least 2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
        for (var r : results) {
            if (r != null && r.strategy() == EmotionRegulationStrategy.SITUATION_SELECTION) {
                assertThat(r.regulationAxis()).as("environmental strategy must have regulationAxis").isNotNull();
                assertThat(r.environmentalDirective()).as("environmental strategy must have directive").isNotBlank();
            }
        }
    }

    @Test
    void attentionalDeployment_fearUncontrollable_redirectsFocus() {
        String observation = """
                The ceiling beam above Clara is cracking. Plaster dust is falling \
                on her hair. She hasn't noticed — she's reading the inscription \
                on the mantelpiece. The beam shifts with a groan. You have seconds. \
                Your chest is tight. Your legs want to move before your brain \
                decides where.""";

        var descriptor = AgentDescriptor.builder()
                .agentId("eval-attn-deploy").name("Eval AttnDeploy").slot("manor-character").tenancyId(TENANT)
                .disposition(disposition("cooperative", "flexible", "bold", "low", "protective"))
                .build();

        var results = runScenario("eval-attn-deploy", descriptor, observation, N);
        int matched = countMatching(results, Set.of(EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT));
        System.out.printf("ATTENTIONAL_DEPLOYMENT: %d/%d matched%n", matched, N);
        assertThat(matched)
                .as("ATTENTIONAL_DEPLOYMENT should be selected in at least 2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void situationModification_controllableWithFallthrough_modifiesEnvironment() {
        String observation = """
                             Clara has written your name on the chore rota for kitchen duty \
                             — again. Third day in a row. James is smirking. The rota is \
                             pinned to the noticeboard with a thumbtack. Nobody else is in \
                             the corridor. You could simply rearrange the names, swap with \
                             someone, change the situation before anyone notices.""";

        var descriptor = AgentDescriptor.builder()
                                        .agentId("eval-sit-modify").name("Eval SitModify").slot("manor-character").tenancyId(TENANT)
                                        .disposition(disposition("competitive", "flexible", "bold", "high", "competitive"))
                                        .build();

        var results = runScenario("eval-sit-modify", descriptor, observation, N);
        int matched = countMatching(results, Set.of(
                EmotionRegulationStrategy.SITUATION_MODIFICATION,
                EmotionRegulationStrategy.SITUATION_SELECTION));
        System.out.printf("SITUATION_MODIFICATION (environmental): %d/%d matched%n", matched, N);
        assertThat(matched)
                .as("Environmental strategy should be selected in at least 2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
        for (var r : results) {
            if (r != null && r.isEnvironmentalStrategy()) {
                assertThat(r.regulationAxis()).as("environmental strategy must have regulationAxis").isNotNull();
                assertThat(r.environmentalDirective()).as("environmental strategy must have directive").isNotBlank();
            }
        }
    }

    @Test
    void cognitiveChange_controllableWithHighReappraisal_reappraises() {
        String observation = """
                             The breakfast tea has run out again. James took the last cup \
                             — he looked right at you while he poured it. He does this \
                             every single morning. Clara keeps restocking the tea and he \
                             keeps taking more than his share. It is a small thing but \
                             the daily repetition of the unfairness is getting under your \
                             skin.""";

        var descriptor = AgentDescriptor.builder()
                                        .agentId("eval-cog-change").name("Eval CogChange").slot("manor-character").tenancyId(TENANT)
                                        .disposition(disposition("cooperative", "flexible", "conservative", "high", "accommodating"))
                                        .build();

        var results = runScenario("eval-cog-change", descriptor, observation, N);
        int matched = countMatching(results, Set.of(EmotionRegulationStrategy.COGNITIVE_CHANGE));
        System.out.printf("COGNITIVE_CHANGE: %d/%d matched%n", matched, N);
        assertThat(matched)
                .as("COGNITIVE_CHANGE should be selected in at least 2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void responseModulation_otherDirectedWithHighSuppression_suppresses() {
        String observation = """
                             Clara suggested at breakfast that perhaps you should let James \
                             lead today's search, since he found more clues yesterday. The \
                             Brixton Boys nodded. It was a polite suggestion but it implied \
                             you had not been pulling your weight. You disagree — you found \
                             the hidden passage — but correcting her publicly would seem \
                             defensive. Everyone is watching for your reaction.""";

        var descriptor = AgentDescriptor.builder()
                                        .agentId("eval-resp-mod").name("Eval RespMod").slot("manor-character").tenancyId(TENANT)
                                        .disposition(disposition("competitive", "strict", "conservative", "high", "competitive"))
                                        .build();

        var results = runScenario("eval-resp-mod", descriptor, observation, N);
        int matched = countMatching(results, Set.of(EmotionRegulationStrategy.RESPONSE_MODULATION));
        System.out.printf("RESPONSE_MODULATION: %d/%d matched%n", matched, N);
        assertThat(matched)
                .as("RESPONSE_MODULATION should be selected in at least 2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
    }


    // --- shared infrastructure ---

    private List<EmotionRegulationResult> runScenario(String agentId, AgentDescriptor descriptor,
                                                      String observation, int runs) {
        var results = new ArrayList<EmotionRegulationResult>();
        for (int i = 0; i < runs; i++) {
            try {
                var result = runSingleScenario(agentId, descriptor, observation);
                results.add(result);
                if (result != null) {
                    System.out.printf("  run %d: %s effectiveness=%.2f emotions=%s%n",
                                      i + 1, result.strategy(), result.effectiveness(),
                                      String.join(",", result.targetEmotions()));
                } else {
                    System.out.printf("  run %d: no regulation%n", i + 1);
                }
            } catch (Exception e) {
                System.out.printf("  run %d failed: %s%n", i + 1, e.getMessage());
                results.add(null);
            }
        }
        return results;
    }

    private EmotionRegulationResult runSingleScenario(String characterId,
                                                      AgentDescriptor descriptor, String observation) {
        var config = CognitionConfig.none()
                                    .with("mood", true)
                                    .with("drives", true)
                                    .with("appraisal", true)
                                    .with("salience", true)
                                    .with("regulation", true);

        var mindMap = new InMemoryMindMapStore();
        var core    = CognitionTestHarness.builder().withCognitionConfig(config).withSessionPipeline(sessionPipeline).withMindMapStore(mindMap).build().core();
        core.setDefaultsRegistry(defaultsRegistry);
        core.configureAppraisal(new LlmAppraisalStrategy(sessionPipeline), new ConcernBasedSalienceStrategy());
        core.configureRegulation();

        core.tick(characterId, TENANT, descriptor, (a, t) -> Set.of(), null);
        core.tick(characterId, TENANT, descriptor, (a, t) -> Set.of("other"), observation);

        var appraisal = core.appraisalParticipant().currentResult(characterId, TENANT);
        if (appraisal.isPresent() && !appraisal.get().emotions().isEmpty()) {
            for (var e : appraisal.get().emotions()) {
                System.out.printf("    appraisal: %s intensity=%.2f%n", e.type(), e.intensity());
            }
        } else {
            System.out.printf("    appraisal: %s%n",
                              appraisal.isPresent() ? "empty emotions" : "no result");
        }

        return core.regulationParticipant().currentResult(characterId, TENANT).orElse(null);
    }

    private static int countMatching(List<EmotionRegulationResult> results,
                                      Set<EmotionRegulationStrategy> expected) {
        return (int) results.stream()
                .filter(r -> r != null && expected.contains(r.strategy()))
                .count();
    }

    private static CognitiveDefaults buildDefaults(String agentId,
                                                    DispositionAxes axes, RegulationDefaults rd) {
        var dv = DescriptorView.of(agentId, axes, List.of(), List.of());
        return CognitiveDefaults.empty(agentId)
                .withDescriptor(dv)
                .withRegulationDefaults(rd);
    }

    private static AgentDisposition disposition(String social, String rules, String risk,
                                                 String autonomy, String conflict) {
        return new AgentDisposition(
                List.of(DispositionValue.of(social)),
                List.of(DispositionValue.of(rules)),
                List.of(DispositionValue.of(risk)),
                List.of(DispositionValue.of(autonomy)),
                List.of(DispositionValue.of(conflict)),
                false, List.of(), List.of(), null, null);
    }
}
