package io.casehub.neocortex.caps.llm.eval;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.neocortex.caps.CapsAttractor;
import io.casehub.neocortex.caps.CapsEngine;
import io.casehub.neocortex.caps.testing.InMemoryCapsEngine;
import io.casehub.neocortex.cognition.appraisal.LlmAppraisalStrategy;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import io.casehub.neocortex.cognition.core.CognitionCore;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognition.regulation.EmotionRegulationResult;
import io.casehub.neocortex.cognition.regulation.RegulationCascadeConfig;
import io.casehub.neocortex.cognition.regulation.RegulationStrategyPredictor;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("llm-eval")
class CapsRegulationComparisonEvalTest {

    private static final String TENANT = "eval-tenant";

    private static final Map<String, Double> DEFAULT_SOCIAL_INPUTS = Map.of(
            "perceived_responsiveness", 0.2,
            "target_distress", 0.0,
            "social_isolation", 1.0,
            "power_asymmetry", 0.0
    );

    private static SessionPipeline sessionPipeline;
    private static CognitiveDefaultsRegistry defaultsRegistry;

    record Scenario(String agentId, String name,
                    DispositionAxes axes, RegulationDefaults rd,
                    String observation,
                    String social, String rules, String risk,
                    String autonomy, String conflict) {}

    record ComparisonRow(String scenario,
                         String capsTopStrategy, double capsTopStrength,
                         String capsRunnerUp, double capsRunnerUpStrength,
                         String predictorStrategy, double predictorConfidence,
                         String llmFreeStrategy,
                         String cascadeStrategy) {}

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
    void pairedComparison() {
        var scenarios = defineScenarios();
        var rows = new ArrayList<ComparisonRow>();

        for (var s : scenarios) {
            System.out.printf("%n=== %s (%s) ===%n", s.name(), s.agentId());

            var capsEngine = new InMemoryCapsEngine();
            capsEngine.initializeAgent(TENANT, s.agentId(), s.axes());

            // 1. Run cascade tick (CAPS → predictor → LLM)
            var cascadeCore = runCascadeTick(s, capsEngine);
            var cascadeResult = cascadeCore.regulationParticipant().currentResult(s.agentId(), TENANT).orElse(null);

            // 2. Settle CAPS standalone for attractor data
            var capsState = capsEngine.loadState(TENANT, s.agentId());
            var settling = capsEngine.settle(capsState, DEFAULT_SOCIAL_INPUTS);
            var regulationAttractors = settling.attractors().stream()
                    .filter(a -> "regulation".equals(a.category()))
                    .sorted(Comparator.comparingDouble(CapsAttractor::strength).reversed())
                    .toList();

            String capsTop = regulationAttractors.isEmpty() ? "NONE" : regulationAttractors.get(0).nodeId().toUpperCase();
            double capsTopStrength = regulationAttractors.isEmpty() ? 0.0 : regulationAttractors.get(0).strength();
            String capsRunnerUp = regulationAttractors.size() < 2 ? "NONE" : regulationAttractors.get(1).nodeId().toUpperCase();
            double capsRunnerUpStrength = regulationAttractors.size() < 2 ? 0.0 : regulationAttractors.get(1).strength();

            System.out.printf("  CAPS top: %s (%.3f), runner-up: %s (%.3f), margin: %.3f%n",
                    capsTop, capsTopStrength, capsRunnerUp, capsRunnerUpStrength,
                    capsTopStrength - capsRunnerUpStrength);

            // 3. Run predictor standalone (using emotion type from cascade appraisal)
            String predictorStrategy = "NONE";
            double predictorConfidence = 0.0;
            var appraisal = cascadeCore.appraisalParticipant().currentResult(s.agentId(), TENANT);
            EmotionType peakType = null;
            double peakIntensity = 0.65;
            if (appraisal.isPresent() && !appraisal.get().emotions().isEmpty()) {
                var peak = appraisal.get().emotions().stream()
                        .max(java.util.Comparator.comparingDouble(e -> e.intensity()))
                        .orElse(null);
                if (peak != null) {
                    peakType = peak.type();
                    peakIntensity = peak.intensity();
                }
            }
            if (peakType != null) {
                var predictor = new RegulationStrategyPredictor();
                var prediction = predictor.predict(s.rd(), s.axes(), peakType, peakIntensity, 0.0, null);
                if (prediction.isPresent()) {
                    predictorStrategy = prediction.get().strategy().name();
                    predictorConfidence = prediction.get().confidence();
                }
            }
            System.out.printf("  Predictor: %s (confidence=%.2f)%n", predictorStrategy, predictorConfidence);

            // 4. Run LLM-only tick (no CAPS, no predictor)
            var freeResult = runFreeTick(s);

            String cascadeStrategy = cascadeResult != null ? cascadeResult.strategy().name() : "NONE";
            String freeStrategy = freeResult != null ? freeResult.strategy().name() : "NONE";
            System.out.printf("  Cascade: %s, LLM-free: %s%n", cascadeStrategy, freeStrategy);

            rows.add(new ComparisonRow(s.name(),
                    capsTop, capsTopStrength, capsRunnerUp, capsRunnerUpStrength,
                    predictorStrategy, predictorConfidence,
                    freeStrategy, cascadeStrategy));
        }

        // Print agreement summary
        printAgreementSummary(rows);

        // Threshold sweep
        printThresholdSweep(rows);

        assertThat(rows).as("All scenarios produced comparison data").hasSize(scenarios.size());
    }

    private List<Scenario> defineScenarios() {
        return List.of(
                new Scenario("eval-sit-select", "Situation Selection (avoidant shame)",
                        new DispositionAxes("cooperative", "flexible", "conservative", "low", "avoidant"),
                        new RegulationDefaults(0.40, 0.50, 0.60),
                        """
                        You misremembered Clara's birthday and gave her a gift \
                        yesterday — her birthday is actually next week. She thanked \
                        you graciously in front of everyone, which made it worse. \
                        Now you are about to walk into the drawing room where Clara \
                        and James are having tea. You know she will mention it kindly \
                        and the embarrassment will start again.""",
                        "cooperative", "flexible", "conservative", "low", "avoidant"),
                new Scenario("eval-sit-modify", "Situation Modification (bold competition)",
                        new DispositionAxes("competitive", "flexible", "bold", "high", "competitive"),
                        new RegulationDefaults(0.40, 0.40, 0.30),
                        """
                        Clara has written your name on the chore rota for kitchen duty \
                        — again. Third day in a row. James is smirking. The rota is \
                        pinned to the noticeboard with a thumbtack. Nobody else is in \
                        the corridor. You could simply rearrange the names, swap with \
                        someone, change the situation before anyone notices.""",
                        "competitive", "flexible", "bold", "high", "competitive"),
                new Scenario("eval-attn-deploy", "Attentional Deployment (fear/urgency)",
                        new DispositionAxes("cooperative", "flexible", "bold", "low", "protective"),
                        new RegulationDefaults(0.50, 0.50, 0.60),
                        """
                        The ceiling beam above Clara is cracking. Plaster dust is falling \
                        on her hair. She hasn't noticed — she's reading the inscription \
                        on the mantelpiece. The beam shifts with a groan. You have seconds. \
                        Your chest is tight. Your legs want to move before your brain \
                        decides where.""",
                        "cooperative", "flexible", "bold", "low", "protective"),
                new Scenario("eval-cog-change", "Cognitive Change (reappraisal)",
                        new DispositionAxes("cooperative", "flexible", "conservative", "high", "accommodating"),
                        new RegulationDefaults(0.80, 0.40, 0.30),
                        """
                        The breakfast tea has run out again. James took the last cup \
                        — he looked right at you while he poured it. He does this \
                        every single morning. Clara keeps restocking the tea and he \
                        keeps taking more than his share. It is a small thing but \
                        the daily repetition of the unfairness is getting under your \
                        skin.""",
                        "cooperative", "flexible", "conservative", "high", "accommodating"),
                new Scenario("eval-resp-mod", "Response Modulation (suppression)",
                        new DispositionAxes("competitive", "strict", "conservative", "high", "competitive"),
                        new RegulationDefaults(0.35, 0.80, 0.40),
                        """
                        Clara suggested at breakfast that perhaps you should let James \
                        lead today's search, since he found more clues yesterday. The \
                        Brixton Boys nodded. It was a polite suggestion but it implied \
                        you had not been pulling your weight. You disagree — you found \
                        the hidden passage — but correcting her publicly would seem \
                        defensive. Everyone is watching for your reaction.""",
                        "competitive", "strict", "conservative", "high", "competitive")
        );
    }

    private CognitionCore runCascadeTick(Scenario s, CapsEngine capsEngine) {
        var config = CognitionConfig.none()
                                    .with("mood", true).with("drives", true)
                                    .with("appraisal", true).with("salience", true)
                                    .with("regulation", true);
        var mindMap = new InMemoryMindMapStore();
        var core    = CognitionTestHarness.builder().withCognitionConfig(config).withSessionPipeline(sessionPipeline).withMindMapStore(mindMap).build().core();
        core.setDefaultsRegistry(defaultsRegistry);
        core.configureAppraisal(new LlmAppraisalStrategy(sessionPipeline), new ConcernBasedSalienceStrategy());
        core.configureRegulation(capsEngine, RegulationCascadeConfig.defaults());

        var descriptor = buildDescriptor(s);
        core.tick(s.agentId(), TENANT, descriptor, (a, t) -> Set.of(), null);
        core.tick(s.agentId(), TENANT, descriptor, (a, t) -> Set.of("other"), s.observation());

        printAppraisal(core, s.agentId(), "cascade");
        return core;
    }

    private EmotionRegulationResult runFreeTick(Scenario s) {
        var config = CognitionConfig.none()
                .with("mood", true).with("drives", true)
                .with("appraisal", true).with("salience", true)
                .with("regulation", true);
        var mindMap = new InMemoryMindMapStore();
        var core = CognitionTestHarness.builder().withCognitionConfig(config).withSessionPipeline(sessionPipeline).withMindMapStore(mindMap).build().core();
        core.configureAppraisal(new LlmAppraisalStrategy(sessionPipeline), new ConcernBasedSalienceStrategy());
        core.configureRegulation();

        var descriptor = buildDescriptor(s);
        core.tick(s.agentId(), TENANT, descriptor, (a, t) -> Set.of(), null);
        core.tick(s.agentId(), TENANT, descriptor, (a, t) -> Set.of("other"), s.observation());

        printAppraisal(core, s.agentId(), "free");
        return core.regulationParticipant().currentResult(s.agentId(), TENANT).orElse(null);
    }

    private void printAppraisal(CognitionCore core, String agentId, String label) {
        var appraisal = core.appraisalParticipant().currentResult(agentId, TENANT);
        if (appraisal.isPresent() && !appraisal.get().emotions().isEmpty()) {
            for (var e : appraisal.get().emotions()) {
                System.out.printf("    %s appraisal: %s intensity=%.2f%n", label, e.type(), e.intensity());
            }
        }
    }

    private void printAgreementSummary(List<ComparisonRow> rows) {
        System.out.printf("%n=== Agreement Summary ===%n");
        int capsMatchesPred = 0, capsMatchesLlm = 0, predMatchesLlm = 0;
        for (var r : rows) {
            if (r.capsTopStrategy().equals(r.predictorStrategy())) capsMatchesPred++;
            if (r.capsTopStrategy().equals(r.llmFreeStrategy())) capsMatchesLlm++;
            if (r.predictorStrategy().equals(r.llmFreeStrategy())) predMatchesLlm++;
        }
        int n = rows.size();
        System.out.printf("  CAPS-Predictor: %d/%d (%.0f%%)%n", capsMatchesPred, n, 100.0 * capsMatchesPred / n);
        System.out.printf("  CAPS-LLM:       %d/%d (%.0f%%)%n", capsMatchesLlm, n, 100.0 * capsMatchesLlm / n);
        System.out.printf("  Predictor-LLM:  %d/%d (%.0f%%)%n", predMatchesLlm, n, 100.0 * predMatchesLlm / n);
    }

    private void printThresholdSweep(List<ComparisonRow> rows) {
        System.out.printf("%n=== Threshold Sweep ===%n");
        System.out.printf("{\"thresholdSweep\": [%n");
        double[] thresholds = {0.1, 0.2, 0.3, 0.4, 0.5};
        double dominanceMargin = RegulationCascadeConfig.defaults().regulationDominanceMargin();
        for (int t = 0; t < thresholds.length; t++) {
            double threshold = thresholds[t];
            int pinCount = 0, pinMatchesPred = 0, pinMatchesLlm = 0;
            for (var r : rows) {
                boolean dominant = r.capsTopStrength() >= threshold
                        && (r.capsRunnerUpStrength() == 0
                            || r.capsTopStrength() - r.capsRunnerUpStrength() >= dominanceMargin);
                if (dominant) {
                    pinCount++;
                    if (r.capsTopStrategy().equals(r.predictorStrategy())) pinMatchesPred++;
                    if (r.capsTopStrategy().equals(r.llmFreeStrategy())) pinMatchesLlm++;
                }
            }
            System.out.printf("  {\"threshold\": %.1f, \"pinned\": %d, \"concordance_predictor\": %.2f, \"concordance_llm\": %.2f}%s%n",
                    threshold, pinCount,
                    pinCount > 0 ? (double) pinMatchesPred / pinCount : 0.0,
                    pinCount > 0 ? (double) pinMatchesLlm / pinCount : 0.0,
                    t < thresholds.length - 1 ? "," : "");
        }
        System.out.println("]}");
    }

    private static AgentDescriptor buildDescriptor(Scenario s) {
        return AgentDescriptor.builder()
                .agentId(s.agentId()).name(s.name()).slot("manor-character").tenancyId(TENANT)
                .disposition(new AgentDisposition(
                        List.of(DispositionValue.of(s.social())),
                        List.of(DispositionValue.of(s.rules())),
                        List.of(DispositionValue.of(s.risk())),
                        List.of(DispositionValue.of(s.autonomy())),
                        List.of(DispositionValue.of(s.conflict())),
                        false, List.of(), List.of(), null, null))
                .build();
    }

    private static CognitiveDefaults buildDefaults(String agentId,
                                                    DispositionAxes axes, RegulationDefaults rd) {
        var dv = DescriptorView.of(agentId, axes, List.of(), List.of());
        return CognitiveDefaults.empty(agentId)
                .withDescriptor(dv)
                .withRegulationDefaults(rd);
    }
}
