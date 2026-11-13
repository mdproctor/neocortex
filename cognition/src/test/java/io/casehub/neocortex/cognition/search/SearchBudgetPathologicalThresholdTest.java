package io.casehub.neocortex.cognition.search;

import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.HabituationConfig;
import io.casehub.neocortex.cognitive.PadProjection;
import io.casehub.neocortex.cognitive.SearchBudgetConfig;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchBudgetPathologicalThresholdTest {

    private static final String AGENT  = "agent1";
    private static final String TENANT = "tenant1";

    @Test
    void convergenceCollapsesWhenThresholdExceeded() {
        // anxiousness (high arousal) = 0.8, habituation failure = true (count 10 > tolerance 5, novelty 0.5 > threshold 0.3)
        // 0.8 + 1.0 = 1.8 > default pathologicalThreshold 1.4 → convergence must be 0.0
        var habituation = buildHabituation("hash1", 10, 0.5);

        var emotion = new CognitiveEmotion(EmotionType.FEAR, 0.8,
                                           AGENT, Instant.now(), EmotionSource.INTRINSIC,
                                           new PadProjection(-0.5, 0.8, -0.3));
        var appraisalResult = new AppraisalResult(List.of(emotion), List.of(), habituation);

        var harness = CognitionTestHarness.builder()
                                          .agentFromDefaults(AGENT, CognitiveDefaults.empty(AGENT)
                                                                                     .withSearchBudgetConfig(new SearchBudgetConfig(6.0, 0.5, 6.0, 0.5, 0.7, 3.0, 0.5, 6.0, 0.3, 1.4, null, 0.85, null, null))
                                                                                     .withHabituationConfig(HabituationConfig.defaults()))
                                          .withHabituationState(AGENT, habituation)
                                          .withAppraisalResult(AGENT, appraisalResult)
                                          .build();

        harness.observe(AGENT, TENANT, "test observation");
        harness.tick(AGENT, TENANT);

        var budget = harness.searchBudget(AGENT, TENANT);
        assertTrue(budget.isPresent());
        assertEquals(0.0, budget.get().convergence(), 0.001,
                     "Convergence should collapse to 0.0 when anxiousness + habituation failure > pathologicalThreshold");
    }

    @Test
    void convergenceUnchangedWhenBelowThreshold() {
        // anxiousness = 0.2, no habituation failure (count 2 < tolerance 5) → 0.2 + 0.0 = 0.2 < 1.4
        var habituation = buildHabituation("hash1", 2, 0.1);

        var emotion = new CognitiveEmotion(EmotionType.FEAR, 0.2,
                                           AGENT, Instant.now(), EmotionSource.INTRINSIC,
                                           new PadProjection(-0.3, 0.2, -0.1));
        var appraisalResult = new AppraisalResult(List.of(emotion), List.of(), habituation);

        var harness = CognitionTestHarness.builder()
                                          .agentFromDefaults(AGENT, CognitiveDefaults.empty(AGENT)
                                                                                     .withSearchBudgetConfig(new SearchBudgetConfig(6.0, 0.5, 6.0, 0.5, 0.7, 3.0, 0.5, 6.0, 0.3, 1.4, null, 0.85, null, null))
                                                                                     .withHabituationConfig(HabituationConfig.defaults()))
                                          .withHabituationState(AGENT, habituation)
                                          .withAppraisalResult(AGENT, appraisalResult)
                                          .build();

        harness.observe(AGENT, TENANT, "test observation");
        harness.tick(AGENT, TENANT);

        var budget = harness.searchBudget(AGENT, TENANT);
        assertTrue(budget.isPresent());
        assertTrue(budget.get().convergence() > 0.0,
                   "Convergence should not collapse when below pathological threshold");
    }

    @Test
    void nullHabituationSkipsCheck() {
        // No habituation state available → should not collapse even with high arousal
        var harness = CognitionTestHarness.builder()
                                          .agentFromDefaults(AGENT, CognitiveDefaults.empty(AGENT)
                                                                                     .withSearchBudgetConfig(new SearchBudgetConfig(6.0, 0.5, 6.0, 0.5, 0.7, 3.0, 0.5, 6.0, 0.3, 1.4, null, 0.85, null, null)))
                                          .build();

        harness.observe(AGENT, TENANT, "test observation");
        harness.tick(AGENT, TENANT);

        var budget = harness.searchBudget(AGENT, TENANT);
        assertTrue(budget.isPresent());
        assertTrue(budget.get().convergence() > 0.0,
                   "Convergence should not collapse when no habituation state available");
    }

    @Test
    void habituationFailureWithoutHighArousalDoesNotCollapse() {
        // Habituation failure present but low arousal: 0.1 + 1.0 = 1.1 < 1.4
        var habituation = buildHabituation("hash1", 6, 0.5);
        // count=6 > tolerance=5, novelty=0.5 > threshold=0.3 → failure=true, but arousal only 0.1

        var emotion = new CognitiveEmotion(EmotionType.FEAR, 0.1,
                                           AGENT, Instant.now(), EmotionSource.INTRINSIC,
                                           new PadProjection(-0.1, 0.1, -0.1));
        var appraisalResult = new AppraisalResult(List.of(emotion), List.of(), habituation);

        var harness = CognitionTestHarness.builder()
                                          .agentFromDefaults(AGENT, CognitiveDefaults.empty(AGENT)
                                                                                     .withSearchBudgetConfig(new SearchBudgetConfig(6.0, 0.5, 6.0, 0.5, 0.7, 3.0, 0.5, 6.0, 0.3, 1.4, null, 0.85, null, null))
                                                                                     .withHabituationConfig(HabituationConfig.defaults()))
                                          .withHabituationState(AGENT, habituation)
                                          .withAppraisalResult(AGENT, appraisalResult)
                                          .build();

        harness.observe(AGENT, TENANT, "test observation");
        harness.tick(AGENT, TENANT);

        var budget = harness.searchBudget(AGENT, TENANT);
        assertTrue(budget.isPresent());
        assertTrue(budget.get().convergence() > 0.0,
                   "Low arousal + habituation failure should stay below pathological threshold");
    }

    // --- helpers ---

    private HabituationState buildHabituation(String hash, int count, double novelty) {
        var state = HabituationState.empty();
        for (int i = 0; i < count; i++) {
            state = state.withObservation(hash, novelty);
        }
        return state;
    }
}
