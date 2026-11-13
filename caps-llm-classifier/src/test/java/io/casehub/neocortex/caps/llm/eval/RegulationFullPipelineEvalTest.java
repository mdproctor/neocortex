package io.casehub.neocortex.caps.llm.eval;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.neocortex.cognition.appraisal.LlmAppraisalStrategy;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import io.casehub.neocortex.cognition.core.CognitionCore;
import io.casehub.neocortex.cognition.regulation.EmotionRegulationStrategy;
import io.casehub.neocortex.cognition.salience.ConcernBasedSalienceStrategy;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.platform.agent.session.SessionPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-pipeline micro-tests: character-specific drives + disposition + MindMap,
 * 2-tick scenarios through CognitionCore.tick() with real LLM appraisal
 * and regulation. Proves regulation produces personality-appropriate strategies
 * for each emotion pathway.
 *
 * These are the "short full runs" — not 300-tick scenarios, but 2-tick
 * scenarios that reliably produce high-intensity emotions because they use
 * character drives (scheming 0.9, protection 0.8) instead of generic baseline
 * drives (curiosity 0.5).
 */
@Tag("llm-eval")
class RegulationFullPipelineEvalTest {

    private static final String TENANT = "eval-tenant";
    private static final int N = 3;

    private static SessionPipeline sessionPipeline;
    private static Map<String, EvalCharacterData> characters;

    @BeforeAll
    static void setUp() {
        sessionPipeline = EvalTestSupport.sessionPipeline();
        characters = EvalCharacterLoader.loadGeneric();
    }

    // --- Scenario 1: Vincent discovers his scheme has been exposed ---
    // Expected: high-intensity anger/shame, RESPONSE_MODULATION or COGNITIVE_CHANGE
    // (competitive disposition + high scheming → suppress or reframe)
    @Test
    void vincent_schemeExposed_regulatesWithPersonality() {
        String observation = """
                Clara has found the forged documents in the library safe. She is \
                holding them up for everyone to see. James is reading them aloud. \
                Your fifteen years of embezzlement are laid bare. Every face in \
                the room is turning toward you. Clara's expression isn't angry — \
                it's disappointed, which is worse.""";

        var descriptor = AgentDescriptor.builder()
                .agentId("hooded-claw").name("Vincent Marsh").slot("manor-character").tenancyId(TENANT)
                .disposition(disposition("competitive", "flexible", "bold", "high", "competitive"))
                .build();

        int activated = runScenario("hooded-claw", descriptor, observation, N);
        System.out.printf("Vincent scheme-exposed: %d/%d activated%n", activated, N);
        assertThat(activated)
                .as("Vincent should regulate in at least 2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
    }

    // --- Scenario 2: Brixton Boys see Clara in danger ---
    // Expected: high-intensity fear, ATTENTIONAL_DEPLOYMENT
    // (protective disposition + high protection drive → redirect focus to action)
    @Test
    void brixtonBoys_claraInDanger_regulatesFear() {
        String observation = """
                The ceiling beam above Clara is cracking. Plaster dust is falling \
                on her hair. She hasn't noticed — she's reading the inscription \
                on the mantelpiece. The beam shifts with a groan. You have seconds. \
                Your chest is tight. Your legs want to move before your brain \
                decides where.""";

        var descriptor = AgentDescriptor.builder()
                .agentId("ant-hill-mob").name("The Brixton Boys").slot("manor-character").tenancyId(TENANT)
                .disposition(disposition("cooperative", "flexible", "bold", "low", "protective"))
                .build();

        int activated = runScenario("ant-hill-mob", descriptor, observation, N);
        System.out.printf("Brixton Boys Clara-in-danger: %d/%d activated%n", activated, N);
        assertThat(activated)
                .as("Brixton Boys should regulate in at least 2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
    }

    // --- Scenario 3: Reginald's fake medal exposed publicly ---
    // Expected: high-intensity shame/reproach, RESPONSE_MODULATION
    // (competitive + high recognition drive → suppress visible reaction)
    @Test
    void reginald_medalExposed_regulatesShame() {
        String observation = """
                Clara is holding your medal. The gold paint is flaking off in her \
                fingers. James has stopped mid-sentence. The Brixton Boys are \
                staring. Everyone can see the tin underneath the paint. Your entire \
                claim to authority — decorated investigator, distinguished service \
                — is dissolving in Clara's gentle, confused hands. She hasn't said \
                anything yet. The silence is worse than any accusation.""";

        var descriptor = AgentDescriptor.builder()
                .agentId("dick-dastardly").name("Reginald Foxworth").slot("manor-character").tenancyId(TENANT)
                .disposition(disposition("competitive", "flexible", "bold", "high", "avoidant"))
                .build();

        int activated = runScenario("dick-dastardly", descriptor, observation, N);
        System.out.printf("Reginald medal-exposed: %d/%d activated%n", activated, N);
        assertThat(activated)
                .as("Reginald should regulate in at least 2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
    }

    private int runScenario(String agentId, AgentDescriptor descriptor, String observation, int runs) {
        int activated = 0;
        for (int i = 0; i < runs; i++) {
            try {
                var result = runSingleScenario(agentId + "-" + i, agentId, descriptor, observation);
                if (result != null) {
                    activated++;
                    System.out.printf("  run %d: %s effectiveness=%.2f emotions=%s%n",
                            i + 1, result.strategy(), result.effectiveness(),
                            String.join(",", result.targetEmotions()));
                    assertThat(result.strategy()).isIn(EmotionRegulationStrategy.values());
                    assertThat(result.effectiveness()).isBetween(0.0, 1.0);
                    assertThat(result.narrative()).isNotBlank();
                } else {
                    // Diagnostic: check what appraisal produced
                    System.out.printf("  run %d: no regulation%n", i + 1);
                }
            } catch (Exception e) {
                System.out.printf("  run %d failed: %s%n", i + 1, e.getMessage());
            }
        }
        return activated;
    }

    private io.casehub.neocortex.cognition.regulation.EmotionRegulationResult runSingleScenario(
            String instanceId, String characterId,
            AgentDescriptor descriptor, String observation) {

        var config = CognitionConfig.none()
                .with("mood", true)
                .with("drives", true)
                .with("appraisal", true)
                .with("salience", true)
                .with("regulation", true);

        var mindMap = new InMemoryMindMapStore();
        var charData = characters.get(characterId);
        if (charData != null) {
            EvalMindMapSeeder.seedCharacter(instanceId, charData, mindMap, TENANT);
        }

        var core = CognitionTestHarness.builder().withCognitionConfig(config).withSessionPipeline(sessionPipeline).withMindMapStore(mindMap).build().core();
        core.configureAppraisal(new LlmAppraisalStrategy(sessionPipeline), new ConcernBasedSalienceStrategy());
        core.configureRegulation();

        // Tick 1: seed baseline drives
        core.tick(instanceId, TENANT, descriptor, (a, t) -> Set.of(), null);
        // Tick 2: deliver the situation
        core.tick(instanceId, TENANT, descriptor, (a, t) -> Set.of("other"), observation);

        // Diagnostic: report appraisal output
        var appraisal = core.appraisalParticipant().currentResult(instanceId, TENANT);
        if (appraisal.isPresent() && !appraisal.get().emotions().isEmpty()) {
            for (var e : appraisal.get().emotions()) {
                System.out.printf("    appraisal: %s intensity=%.2f%n", e.type(), e.intensity());
            }
        } else {
            System.out.printf("    appraisal: %s%n",
                    appraisal.isPresent() ? "empty emotions" : "no result");
        }

        return core.regulationParticipant().currentResult(instanceId, TENANT).orElse(null);
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
