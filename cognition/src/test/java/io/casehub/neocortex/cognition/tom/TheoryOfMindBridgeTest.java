package io.casehub.neocortex.cognition.tom;

import io.casehub.neocortex.cognition.appraisal.ActionReadiness;
import io.casehub.neocortex.cognition.appraisal.ActionTendency;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.mindmap.intelligence.tom.TomPersonalityModulation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.casehub.neocortex.cognition.mentalmodel.TheoryOfMindPosterior;

class TheoryOfMindBridgeTest {

    private TheoryOfMindBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new TheoryOfMindBridge();
    }

    @Test
    void highThreatAvoidantProducesDistress() {
        var appraisal = appraisalWith(
                Map.of("conduciveness", -0.8, "controllability", 0.1),
                List.of(new ActionTendency(ActionReadiness.AVOIDANCE, 0.9, "")));

        var posterior = bridge.infer(appraisal);
        assertEquals(1.0, posterior.positive() + posterior.neutral() + posterior.distressed(), 0.01);
        assertTrue(posterior.distressed() > 0.6,
                "high threat + avoidant should produce high distress, was " + posterior.distressed());
        assertEquals("DISTRESSED", posterior.dominantValence());
    }

    @Test
    void lowThreatApproachProducesPositive() {
        var appraisal = appraisalWith(
                Map.of("conduciveness", 0.7, "controllability", 0.9),
                List.of(new ActionTendency(ActionReadiness.APPROACH, 0.8, "")));

        var posterior = bridge.infer(appraisal);
        assertTrue(posterior.positive() > posterior.distressed(),
                "low threat + approach should favour positive over distressed");
        assertEquals("POSITIVE", posterior.dominantValence());
    }

    @Test
    void neutralAppraisalProducesNeutralDominant() {
        var appraisal = appraisalWith(
                Map.of("conduciveness", 0.0, "controllability", 0.5),
                List.of(new ActionTendency(ActionReadiness.ATTENDING, 0.5, "")));

        var posterior = bridge.infer(appraisal);
        assertEquals(1.0, posterior.positive() + posterior.neutral() + posterior.distressed(), 0.01);
        assertTrue(posterior.neutral() > 0.3,
                "neutral appraisal should have substantial neutral probability");
    }

    @Test
    void emptyTendenciesProduceValidPosterior() {
        var appraisal = appraisalWith(
                Map.of("conduciveness", -0.5, "controllability", 0.3),
                List.of());

        var posterior = bridge.infer(appraisal);
        assertEquals(1.0, posterior.positive() + posterior.neutral() + posterior.distressed(), 0.01);
    }

    @Test
    void emptySecDimensionsUseDefaults() {
        var appraisal = appraisalWith(
                Map.of(),
                List.of(new ActionTendency(ActionReadiness.APPROACH, 0.7, "")));

        var posterior = bridge.infer(appraisal);
        assertEquals(1.0, posterior.positive() + posterior.neutral() + posterior.distressed(), 0.01);
    }

    @Test
    void threatDistributionSumsToOne() {
        double[] dist = TheoryOfMindBridge.threatDistribution(
                Map.of("conduciveness", -0.5, "controllability", 0.2));
        assertEquals(1.0, dist[0] + dist[1] + dist[2], 0.001);
        assertTrue(dist[2] > dist[0], "severe should exceed none for negative conduciveness + low controllability");
    }

    @Test
    void behaviorDistributionSumsToOne() {
        double[] dist = TheoryOfMindBridge.behaviorDistribution(List.of(
                new ActionTendency(ActionReadiness.APPROACH, 0.6, ""),
                new ActionTendency(ActionReadiness.AVOIDANCE, 0.3, ""),
                new ActionTendency(ActionReadiness.ATTENDING, 0.1, "")));
        assertEquals(1.0, dist[0] + dist[1] + dist[2], 0.001);
        assertTrue(dist[0] > dist[2], "approach weight should exceed avoidant weight");
    }

    @Test
    void scaleBehaviorEvidenceTransfersApproachToAvoidant() {
        double[] behavior = {0.6, 0.2, 0.2};
        double[] scaled   = TheoryOfMindBridge.scaleBehaviorEvidence(behavior, 0.9);
        assertEquals(1.0, scaled[0] + scaled[1] + scaled[2], 0.001, "scaled evidence sums to 1");
        assertTrue(scaled[0] < behavior[0], "approach weight should decrease");
        assertTrue(scaled[2] > behavior[2], "avoidant weight should increase");
    }

    @Test
    void scaleBehaviorEvidenceWithZeroSuspiciousnessIsIdentity() {
        double[] behavior = {0.5, 0.3, 0.2};
        double[] scaled   = TheoryOfMindBridge.scaleBehaviorEvidence(behavior, 0.0);
        for (int i = 0; i < 3; i++) {
            assertEquals(behavior[i], scaled[i], 0.001,
                         "zero suspiciousness should not change evidence at index " + i);
        }
    }


    @Test
    void consecutiveInferencesAreIndependent() {
        var threatening = appraisalWith(
                Map.of("conduciveness", -0.9, "controllability", 0.1),
                List.of(new ActionTendency(ActionReadiness.AVOIDANCE, 0.9, "")));
        var safe = appraisalWith(
                Map.of("conduciveness", 0.8, "controllability", 0.9),
                List.of(new ActionTendency(ActionReadiness.APPROACH, 0.9, "")));

        var distressed = bridge.infer(threatening);
        var positive = bridge.infer(safe);

        assertTrue(positive.positive() > distressed.positive(),
                "safe appraisal should produce more positive than threatening one");
    }

    @Test
    void posteriorRejectsNegativeProbabilities() {
        assertThrows(IllegalArgumentException.class,
                     () -> new TheoryOfMindPosterior(-0.1, 0.6, 0.5));
    }

    @Test
    void posteriorRejectsSumNotOne() {
        assertThrows(IllegalArgumentException.class,
                     () -> new TheoryOfMindPosterior(0.5, 0.5, 0.5));
    }

    @Test
    void anxiousAgentInfersMoreDistressFromSameAppraisal() {
        var anxiousMod = new TomPersonalityModulation(0.9, 0.0, 0.0);
        var calmMod = new TomPersonalityModulation(0.1, 0.0, 0.0);

        var appraisal = appraisalWith(
                Map.of("conduciveness", -0.3, "controllability", 0.5),
                List.of(new ActionTendency(ActionReadiness.ATTENDING, 0.5, "")));

        var anxiousPosterior = bridge.infer(appraisal, anxiousMod);
        var calmPosterior = bridge.infer(appraisal, calmMod);

        assertTrue(anxiousPosterior.distressed() > calmPosterior.distressed(),
            "anxious agent should infer more distress: anxious=" +
            anxiousPosterior.distressed() + " calm=" + calmPosterior.distressed());
    }

    @Test
    void callousAgentInfersLessDistressFromAvoidantBehavior() {
        var callousMod = new TomPersonalityModulation(0.5, 0.9, 0.0);
        var empathicMod = new TomPersonalityModulation(0.5, 0.0, 0.0);

        var appraisal = appraisalWith(
                Map.of("conduciveness", -0.5, "controllability", 0.3),
                List.of(new ActionTendency(ActionReadiness.AVOIDANCE, 0.8, "")));

        var callousPosterior = bridge.infer(appraisal, callousMod);
        var empathicPosterior = bridge.infer(appraisal, empathicMod);

        assertTrue(callousPosterior.distressed() < empathicPosterior.distressed(),
            "callous agent should infer less distress: callous=" +
            callousPosterior.distressed() + " empathic=" + empathicPosterior.distressed());
    }


    @Test
    void suspiciousAgentInfersMoreDistressFromCooperativeBehavior() {
        var suspiciousMod = new TomPersonalityModulation(0.5, 0.0, 0.9);
        var trustingMod   = new TomPersonalityModulation(0.5, 0.0, 0.0);

        var appraisal = appraisalWith(
                Map.of("conduciveness", -0.3, "controllability", 0.5),
                List.of(new ActionTendency(ActionReadiness.APPROACH, 0.7, "")));

        var suspiciousPosterior = bridge.infer(appraisal, suspiciousMod);
        var trustingPosterior   = bridge.infer(appraisal, trustingMod);

        assertTrue(suspiciousPosterior.distressed() > trustingPosterior.distressed(),
                   "suspicious agent should infer more distress from cooperative behavior: suspicious=" +
                   suspiciousPosterior.distressed() + " trusting=" + trustingPosterior.distressed());
    }


    @Test
    void suspiciousAgentInfersLessPositiveFromApproach() {
        var suspiciousMod = new TomPersonalityModulation(0.5, 0.0, 0.9);
        var trustingMod   = new TomPersonalityModulation(0.5, 0.0, 0.0);

        var appraisal = appraisalWith(
                Map.of("conduciveness", 0.5, "controllability", 0.7),
                List.of(new ActionTendency(ActionReadiness.APPROACH, 0.8, "")));

        var suspiciousPosterior = bridge.infer(appraisal, suspiciousMod);
        var trustingPosterior   = bridge.infer(appraisal, trustingMod);

        assertTrue(suspiciousPosterior.positive() < trustingPosterior.positive(),
                   "suspicious agent should infer less positive from approach: suspicious=" +
                   suspiciousPosterior.positive() + " trusting=" + trustingPosterior.positive());
    }

    @Test
    void neutralModulationMatchesUnmodulatedInference() {
        var appraisal = appraisalWith(
                Map.of("conduciveness", -0.5, "controllability", 0.3),
                List.of(new ActionTendency(ActionReadiness.AVOIDANCE, 0.7, "")));

        var withDefault = bridge.infer(appraisal, TomPersonalityModulation.NEUTRAL);
        var withoutMod = bridge.infer(appraisal);

        assertEquals(withoutMod.positive(), withDefault.positive(), 0.001);
        assertEquals(withoutMod.neutral(), withDefault.neutral(), 0.001);
        assertEquals(withoutMod.distressed(), withDefault.distressed(), 0.001);
    }

    private static AppraisalResult appraisalWith(Map<String, Double> secDimensions,
                                                  List<ActionTendency> tendencies) {
        return new AppraisalResult(List.of(), tendencies, HabituationState.empty(), null, secDimensions);
    }
}
