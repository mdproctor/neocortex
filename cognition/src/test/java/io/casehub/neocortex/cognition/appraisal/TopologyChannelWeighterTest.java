package io.casehub.neocortex.cognition.appraisal;

import io.casehub.neocortex.caps.SituationActivation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class TopologyChannelWeighterTest {

    @Test
    void emptyActivationsReturnDefault() {
        var profile = TopologyChannelWeighter.weight(List.of());
        assertThat(profile.isDefault()).isTrue();
    }

    @Test
    void nullActivationsReturnDefault() {
        var profile = TopologyChannelWeighter.weight(null);
        assertThat(profile.isDefault()).isTrue();
    }

    @Test
    void threatActivationElevatesSomaticAndActionTendency() {
        var activations = List.of(
                new SituationActivation("physical_threat", 0.8));
        var profile = TopologyChannelWeighter.weight(activations);

        assertThat(profile.somatic()).isCloseTo(0.35, offset(0.01));
        assertThat(profile.actionTendency()).isCloseTo(0.40, offset(0.01));
        assertThat(profile.attentional()).isCloseTo(0.10, offset(0.01));
    }

    @Test
    void socialActivationElevatesAttentional() {
        var activations = List.of(
                new SituationActivation("acceptance", 0.9));
        var profile = TopologyChannelWeighter.weight(activations);

        assertThat(profile.attentional()).isCloseTo(0.35, offset(0.01));
        assertThat(profile.somatic()).isCloseTo(0.20, offset(0.01));
    }

    @Test
    void autonomyActivationElevatesActionTendency() {
        var activations = List.of(
                new SituationActivation("powerlessness", 0.7));
        var profile = TopologyChannelWeighter.weight(activations);

        assertThat(profile.actionTendency()).isCloseTo(0.40, offset(0.01));
    }

    @Test
    void resourceActivationElevatesTemporal() {
        var activations = List.of(
                new SituationActivation("scarcity", 0.6));
        var profile = TopologyChannelWeighter.weight(activations);

        assertThat(profile.temporal()).isCloseTo(0.30, offset(0.01));
    }

    @Test
    void multipleActivationsBlendByConfidence() {
        var activations = List.of(
                new SituationActivation("physical_threat", 0.8),
                new SituationActivation("acceptance", 0.4));
        var profile = TopologyChannelWeighter.weight(activations);

        // Threat (0.8 conf) dominates over social (0.4 conf)
        // somatic should be between threat's 0.35 and social's 0.20, closer to threat
        assertThat(profile.somatic()).isGreaterThan(0.28);
        assertThat(profile.actionTendency()).isGreaterThan(0.33);
        // Attentional should be between threat's 0.10 and social's 0.35, but threat-weighted
        assertThat(profile.attentional()).isLessThan(0.25);
    }

    @Test
    void sameCategoryActivationsReinforce() {
        var activations = List.of(
                new SituationActivation("physical_threat", 0.6),
                new SituationActivation("psychological_threat", 0.5));
        var profile = TopologyChannelWeighter.weight(activations);

        // Both map to threat category — result should be threat profile
        assertThat(profile.somatic()).isCloseTo(0.35, offset(0.01));
        assertThat(profile.actionTendency()).isCloseTo(0.40, offset(0.01));
    }

    @Test
    void unknownNodeIdIsIgnored() {
        var activations = List.of(
                new SituationActivation("unknown_node", 0.9),
                new SituationActivation("physical_threat", 0.5));
        var profile = TopologyChannelWeighter.weight(activations);

        // Unknown node ignored — only threat contributes
        assertThat(profile.somatic()).isCloseTo(0.35, offset(0.01));
    }

    @Test
    void allUnknownNodesReturnDefault() {
        var activations = List.of(
                new SituationActivation("unknown_a", 0.8),
                new SituationActivation("unknown_b", 0.6));
        var profile = TopologyChannelWeighter.weight(activations);

        assertThat(profile.isDefault()).isTrue();
    }

    @Test
    void attendingTendencyBoostsAttentionalChannel() {
        var activations = List.of(
                new SituationActivation("agency_granted", 0.7),
                new SituationActivation("mastery", 0.6));
        var baseLine = TopologyChannelWeighter.weight(activations);

        var tendencies = List.of(
                new ActionTendency(ActionReadiness.ATTENDING, 0.85, ""));
        var boosted = TopologyChannelWeighter.weight(activations, tendencies);

        assertThat(boosted.attentional())
                .as("ATTENDING tendency should boost attentional channel")
                .isGreaterThan(baseLine.attentional());
    }

    @Test
    void avoidanceTendencyBoostsSomaticAndActionTendency() {
        var activations = List.of(
                new SituationActivation("rejection", 0.7),
                new SituationActivation("social_threat", 0.6));
        var baseLine = TopologyChannelWeighter.weight(activations);

        var tendencies = List.of(
                new ActionTendency(ActionReadiness.AVOIDANCE, 0.85, ""));
        var boosted = TopologyChannelWeighter.weight(activations, tendencies);

        assertThat(boosted.somatic())
                .as("AVOIDANCE tendency should boost somatic channel")
                .isGreaterThan(baseLine.somatic());
        assertThat(boosted.actionTendency())
                .as("AVOIDANCE tendency should boost action tendency channel")
                .isGreaterThanOrEqualTo(baseLine.actionTendency());
        assertThat(boosted.attentional())
                .as("AVOIDANCE should reduce attentional (Easterbrook narrowing)")
                .isLessThan(baseLine.attentional());
    }

    @Test
    void antagonismTendencyBoostsSomatic() {
        var activations = List.of(
                new SituationActivation("dominance", 0.7),
                new SituationActivation("competition", 0.5));
        var baseLine = TopologyChannelWeighter.weight(activations);

        var tendencies = List.of(
                new ActionTendency(ActionReadiness.ANTAGONISM, 0.80, ""));
        var boosted = TopologyChannelWeighter.weight(activations, tendencies);

        assertThat(boosted.somatic())
                .as("ANTAGONISM tendency should boost somatic channel")
                .isGreaterThan(baseLine.somatic());
        assertThat(boosted.attentional())
                .as("ANTAGONISM should reduce attentional")
                .isLessThan(baseLine.attentional());
    }

    @Test
    void approachTendencyBoostsAttentionalAndTemporal() {
        var activations = List.of(
                new SituationActivation("mastery", 0.6),
                new SituationActivation("agency_granted", 0.5));
        var baseLine = TopologyChannelWeighter.weight(activations);

        var tendencies = List.of(
                new ActionTendency(ActionReadiness.APPROACH, 0.80, ""));
        var boosted = TopologyChannelWeighter.weight(activations, tendencies);

        assertThat(boosted.attentional())
                .as("APPROACH tendency should boost attentional channel (scanning)")
                .isGreaterThan(baseLine.attentional());
        assertThat(boosted.temporal())
                .as("APPROACH tendency should boost temporal channel (planning)")
                .isGreaterThan(baseLine.temporal());
    }

    @Test
    void submissionTendencyBoostsTemporal() {
        var activations = List.of(
                new SituationActivation("submission", 0.7),
                new SituationActivation("controlled", 0.5));
        var baseLine = TopologyChannelWeighter.weight(activations);

        var tendencies = List.of(
                new ActionTendency(ActionReadiness.SUBMISSION, 0.80, ""));
        var boosted = TopologyChannelWeighter.weight(activations, tendencies);

        assertThat(boosted.temporal())
                .as("SUBMISSION tendency should boost temporal channel (waiting, yielding)")
                .isGreaterThan(baseLine.temporal());
        assertThat(boosted.actionTendency())
                .as("SUBMISSION should reduce action tendency (passive)")
                .isLessThan(baseLine.actionTendency());
    }

    @Test
    void dominanceTendencyBoostsActionTendencyAndSomatic() {
        var activations = List.of(
                new SituationActivation("dominance", 0.7),
                new SituationActivation("competition", 0.5));
        var baseLine = TopologyChannelWeighter.weight(activations);

        var tendencies = List.of(
                new ActionTendency(ActionReadiness.DOMINANCE, 0.80, ""));
        var boosted = TopologyChannelWeighter.weight(activations, tendencies);

        assertThat(boosted.actionTendency())
                .as("DOMINANCE tendency should boost action tendency channel")
                .isGreaterThanOrEqualTo(baseLine.actionTendency());
        assertThat(boosted.somatic())
                .as("DOMINANCE tendency should boost somatic channel (physical assertion)")
                .isGreaterThan(baseLine.somatic());
        assertThat(boosted.attentional())
                .as("DOMINANCE should reduce attentional (narrowed by certainty)")
                .isLessThan(baseLine.attentional());
    }


    @Test
    void lowAvoidanceTendencyNoEffect() {
        var activations = List.of(
                new SituationActivation("physical_threat", 0.8));
        var baseLine = TopologyChannelWeighter.weight(activations);

        var tendencies = List.of(
                new ActionTendency(ActionReadiness.AVOIDANCE, 0.2, ""));
        var result = TopologyChannelWeighter.weight(activations, tendencies);

        assertThat(result.somatic())
                .isCloseTo(baseLine.somatic(), offset(0.001));
    }

    @Test
    void multipleTendenciesBlendSequentially() {
        var activations = List.of(
                new SituationActivation("acceptance", 0.8));

        var avoidanceOnly = TopologyChannelWeighter.weight(activations, List.of(
                new ActionTendency(ActionReadiness.AVOIDANCE, 0.75, "")));
        var attendingOnly = TopologyChannelWeighter.weight(activations, List.of(
                new ActionTendency(ActionReadiness.ATTENDING, 0.60, "")));
        var both = TopologyChannelWeighter.weight(activations, List.of(
                new ActionTendency(ActionReadiness.AVOIDANCE, 0.75, ""),
                new ActionTendency(ActionReadiness.ATTENDING, 0.60, "")));

        assertThat(both.somatic())
                .as("With both tendencies, somatic higher than ATTENDING-only (AVOIDANCE contributes)")
                .isGreaterThan(attendingOnly.somatic());
        assertThat(both.attentional())
                .as("With both tendencies, attentional higher than AVOIDANCE-only (ATTENDING contributes)")
                .isGreaterThan(avoidanceOnly.attentional());
    }


    @Test
    void lowAttendingTendencyNoEffect() {
        var activations = List.of(
                new SituationActivation("physical_threat", 0.8));
        var baseLine = TopologyChannelWeighter.weight(activations);

        var tendencies = List.of(
                new ActionTendency(ActionReadiness.ATTENDING, 0.2, ""));
        var result = TopologyChannelWeighter.weight(activations, tendencies);

        assertThat(result.attentional())
                .isCloseTo(baseLine.attentional(), offset(0.001));
    }

    @Test
    void nullActionTendenciesFallsBackToBase() {
        var activations = List.of(
                new SituationActivation("acceptance", 0.9));
        var baseLine = TopologyChannelWeighter.weight(activations);
        var result   = TopologyChannelWeighter.weight(activations, null);

        assertThat(result.attentional()).isCloseTo(baseLine.attentional(), offset(0.001));
    }


    @Test
    void highAttendingProducesAttentionalDominance() {
        var activations = List.of(
                new SituationActivation("mastery", 0.6),
                new SituationActivation("agency_granted", 0.5));
        var tendencies = List.of(
                new ActionTendency(ActionReadiness.ATTENDING, 0.85, ""));
        var result = TopologyChannelWeighter.weight(activations, tendencies);

        assertThat(result.attentional())
                .as("High ATTENDING should make attentional the dominant channel")
                .isGreaterThan(result.actionTendency());
        assertThat(result.attentional())
                .as("Attentional should be well above 0.30 for strong curiosity")
                .isGreaterThan(0.30);
        double spread = result.attentional() - Math.min(result.somatic(),
                                                        Math.min(result.temporal(), result.actionTendency()));
        assertThat(spread)
                .as("Spread should be at least 0.20 for single-drive dominance")
                .isGreaterThan(0.20);
    }


    @Test
    void highAvoidanceOnThreatProducesSharpPolarization() {
        var activations = List.of(
                new SituationActivation("physical_threat", 0.8),
                new SituationActivation("failure", 0.4),
                new SituationActivation("powerlessness", 0.3));
        var tendencies = List.of(
                new ActionTendency(ActionReadiness.AVOIDANCE, 0.85, ""));
        var result = TopologyChannelWeighter.weight(activations, tendencies);

        assertThat(result.somatic() + result.actionTendency())
                .as("Somatic + actionTendency should dominate for threat+avoidance")
                .isGreaterThan(0.70);
        assertThat(result.attentional())
                .as("Attentional should be suppressed under threat (Easterbrook)")
                .isLessThan(0.15);
        double spread = Math.max(result.somatic(), result.actionTendency())
                        - Math.min(result.attentional(), result.temporal());
        assertThat(spread)
                .as("Spread should exceed 0.20 for crisis polarization")
                .isGreaterThan(0.20);
    }


    @Test
    void avoidanceProfileDiffersFromThreatCategory() {
        var threatOnly = TopologyChannelWeighter.weight(List.of(
                new SituationActivation("physical_threat", 0.8)));
        var withAvoidance = TopologyChannelWeighter.weight(List.of(
                                                                   new SituationActivation("physical_threat", 0.8)),
                                                           List.of(new ActionTendency(ActionReadiness.AVOIDANCE, 0.7, "")));

        assertThat(withAvoidance.somatic())
                .as("AVOIDANCE should push somatic beyond threat baseline")
                .isGreaterThan(threatOnly.somatic());
        assertThat(withAvoidance.actionTendency())
                .as("AVOIDANCE should push actionTendency beyond threat baseline")
                .isGreaterThan(threatOnly.actionTendency());
    }

    @Test
    void profileWeightsSumToOne() {
        for (var category : TopologyChannelWeighter.knownCategories()) {
            var profile = TopologyChannelWeighter.profileForCategory(category);
            double sum = profile.somatic() + profile.attentional()
                       + profile.temporal() + profile.actionTendency();
            assertThat(sum).as("Category " + category + " weights sum")
                           .isCloseTo(1.0, offset(0.01));
        }
    }
}
