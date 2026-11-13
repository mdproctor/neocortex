package io.casehub.neocortex.caps.llm.eval;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.neocortex.cognition.appraisal.LlmAppraisalStrategy;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import io.casehub.neocortex.cognition.core.CognitionCore;
import io.casehub.neocortex.cognition.regulation.EmotionRegulationStrategy;
import io.casehub.neocortex.cognition.salience.ConcernBasedSalienceStrategy;
import io.casehub.platform.agent.session.SessionPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM eval: end-to-end tick tests for appraisal → regulation per scenario.
 *
 * Each scenario runs N times through CognitionCore.tick() with LLM appraisal
 * and regulation. Asserts regulation activates in at least 2/N runs.
 *
 * Scenarios test distinct emotion pathways through the EmotionMapper:
 * - Betrayal → ANGER/SHAME/REPROACH (negative conduciveness + low standards)
 * - Mortal danger → FEAR (negative conduciveness + low controllability)
 * - Public humiliation → REPROACH/SHAME (low external/internal standards)
 */
@Tag("llm-eval")
class EmotionRegulationTickEvalTest {

    private static final String AGENT = "eval-agent";
    private static final String TENANT = "eval-tenant";
    private static final int N = 3;

    private static SessionPipeline sessionPipeline;

    @BeforeAll
    static void setUp() {
        sessionPipeline = EvalTestSupport.sessionPipeline();
    }

    private static final String BETRAYAL = """
            Your trusted estate manager has been systematically embezzling \
            from the estate for fifteen years. You just found forged documents \
            proving he stole over £200,000. He is standing in front of you now, \
            smiling his usual warm smile, completely unaware that you know. \
            Your hands are shaking. Your vision is narrowing.""";

    private static final String MORTAL_DANGER = """
            The ceiling beam directly above Clara is cracking. Dust is falling \
            on her hair. She hasn't noticed. The beam is going to collapse in \
            seconds. There is no time to explain. You must act now or she dies.""";

    private static final String PUBLIC_HUMILIATION = """
            Everyone just discovered your medal is a forgery. Clara held it up \
            and the fake gold flaked off in her hands. James is staring at you \
            with open contempt. The Brixton Boys are laughing. Your entire \
            identity has collapsed in front of everyone who matters.""";

    @Test
    void betrayal_regulationActivatesViaTick() {
        int activated = runScenario("betrayal", BETRAYAL);
        System.out.printf("Betrayal regulation: %d/%d activated%n", activated, N);
        assertThat(activated)
                .as("Betrayal should activate regulation in at least 2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void mortalDanger_regulationActivatesViaTick() {
        int activated = runScenario("mortal-danger", MORTAL_DANGER);
        System.out.printf("Mortal danger regulation: %d/%d activated%n", activated, N);
        assertThat(activated)
                .as("Mortal danger should activate regulation in at least 2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void publicHumiliation_regulationActivatesViaTick() {
        int activated = runScenario("public-humiliation", PUBLIC_HUMILIATION);
        System.out.printf("Public humiliation regulation: %d/%d activated%n", activated, N);
        assertThat(activated)
                .as("Public humiliation should activate regulation in at least 2/%d runs", N)
                .isGreaterThanOrEqualTo(2);
    }

    private int runScenario(String label, String observation) {
        int activated = 0;
        for (int i = 0; i < N; i++) {
            try {
                var result = runSingleTick(observation);
                if (result != null) {
                    activated++;
                    System.out.printf("  [%s] run %d: %s effectiveness=%.2f%n",
                            label, i + 1, result.strategy(), result.effectiveness());
                    assertThat(result.strategy()).isIn(EnumSet.allOf(EmotionRegulationStrategy.class));
                    assertThat(result.effectiveness()).isBetween(0.0, 1.0);
                    assertThat(result.narrative()).isNotBlank();
                } else {
                    System.out.printf("  [%s] run %d: no regulation activated%n", label, i + 1);
                }
            } catch (Exception e) {
                System.out.printf("  [%s] run %d failed: %s%n", label, i + 1, e.getMessage());
            }
        }
        return activated;
    }

    private io.casehub.neocortex.cognition.regulation.EmotionRegulationResult runSingleTick(String observation) {
        var agentId = AGENT + "-" + System.nanoTime();
        var descriptor = AgentDescriptor.builder()
                .agentId(agentId).name("EvalAgent").slot("default").tenancyId(TENANT)
                .build();

        var config = CognitionConfig.none()
                .with("mood", true)
                .with("drives", true)
                .with("appraisal", true)
                .with("salience", true)
                .with("regulation", true);

        var core = CognitionTestHarness.builder().withCognitionConfig(config).withSessionPipeline(sessionPipeline).build().core();
        core.configureAppraisal(new LlmAppraisalStrategy(sessionPipeline), new ConcernBasedSalienceStrategy());
        core.configureRegulation();

        core.tick(agentId, TENANT, descriptor, (a, t) -> Set.of(), null);
        core.tick(agentId, TENANT, descriptor, (a, t) -> Set.of("other"), observation);

        return core.regulationParticipant().currentResult(agentId, TENANT).orElse(null);
    }
}
