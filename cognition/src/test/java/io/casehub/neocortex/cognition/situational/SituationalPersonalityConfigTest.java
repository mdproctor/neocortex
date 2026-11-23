package io.casehub.neocortex.cognition.situational;

import io.casehub.neocortex.caps.CapsNode;
import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.NodeRange;
import io.casehub.neocortex.caps.NodeType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.assertj.core.data.Offset;

class SituationalPersonalityConfigTest {

    @Test
    void loadDefaultConfigFromClasspath() {
        var loader = new SituationalPersonalityConfigLoader();
        var config = loader.loadFromClasspath("situational-personality.yaml");

        assertThat(config.modes()).hasSize(5);
        assertThat(config.modes()).containsKey(SituationalMode.STRESS);
        assertThat(config.modes()).containsKey(SituationalMode.FLOW);
        assertThat(config.modes()).containsKey(SituationalMode.SOCIAL_COLLABORATIVE);
        assertThat(config.modes()).containsKey(SituationalMode.SOCIAL_COMPETITIVE);
        assertThat(config.modes()).containsKey(SituationalMode.SOCIAL_HIERARCHICAL);
        assertThat(config.gains().betaN()).isEqualTo(0.5);
        assertThat(config.gains().betaR()).isEqualTo(0.25);
        assertThat(config.gains().mFloor()).isEqualTo(0.3);
        assertThat(config.gains().switchMargin()).isEqualTo(0.10);
    }

    @Test
    void stressModeHasCorrectSignalWeights() {
        var loader = new SituationalPersonalityConfigLoader();
        var config = loader.loadFromClasspath("situational-personality.yaml");
        var stress = config.modes().get(SituationalMode.STRESS);

        assertThat(stress.group()).isEqualTo(SituationalMode.Group.AFFECTIVE);
        assertThat(stress.thresholdOn()).isEqualTo(0.50);
        assertThat(stress.thresholdOff()).isEqualTo(0.42);
        assertThat(stress.fadeTau()).isEqualTo(Duration.ofHours(2));

        double exoSum = stress.exogenous().stream().mapToDouble(SignalConfig::weight).sum();
        assertThat(exoSum).isCloseTo(1.0, Offset.offset(0.001));

        double endoSum = stress.endogenous().stream().mapToDouble(SignalConfig::weight).sum();
        assertThat(endoSum).isCloseTo(1.0, Offset.offset(0.001));
    }

    @Test
    void rejectsNegativeBetaN() {
        assertThatThrownBy(() -> new SituationalPersonalityConfig.GainsConfig(-0.1, 0.25, 0.3, 0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("beta_N");
    }

    @Test
    void rejectsNegativeBetaR() {
        assertThatThrownBy(() -> new SituationalPersonalityConfig.GainsConfig(0.5, -0.1, 0.3, 0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("beta_R");
    }

    @Test
    void rejectsThresholdOffGreaterThanOn() {
        assertThatThrownBy(() -> new ModeDetectionConfig(
                SituationalMode.Group.AFFECTIVE,
                List.of(), List.of(), null,
                0.40, 0.50, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold_off");
    }

    @Test
    void rejectsXFloorOutOfRange() {
        assertThatThrownBy(() ->
                new SituationalPersonalityConfig.ClassifierCalibration(0.2, "test", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x_floor");

        assertThatThrownBy(() ->
                new SituationalPersonalityConfig.ClassifierCalibration(0.95, "test", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x_floor");
    }

    @Test
    void validationRejectsExogenousWeightsNotSummingToOne() {
        var badMode = new ModeDetectionConfig(
                SituationalMode.Group.AFFECTIVE,
                List.of(
                        new SignalConfig.SituationInputSignal(List.of("physical_threat"), SignalConfig.Aggregation.MAX, 0.3),
                        new SignalConfig.SituationInputSignal(List.of("social_threat"), SignalConfig.Aggregation.MAX, 0.3)
                ),
                List.of(), null,
                0.50, 0.42, Duration.ofHours(1)
        );
        var config = SituationalPersonalityConfig.withDefaults(Map.of(SituationalMode.STRESS, badMode));

        assertThatThrownBy(() -> config.validate(minimalTopology()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exogenous weights");
    }

    @Test
    void validationRejectsSituationNodeNotInTopology() {
        var badMode = new ModeDetectionConfig(
                SituationalMode.Group.AFFECTIVE,
                List.of(new SignalConfig.SituationInputSignal(List.of("nonexistent_node"), SignalConfig.Aggregation.MAX, 1.0)),
                List.of(), null,
                0.50, 0.42, Duration.ofHours(1)
        );
        var config = SituationalPersonalityConfig.withDefaults(Map.of(SituationalMode.STRESS, badMode));

        assertThatThrownBy(() -> config.validate(minimalTopology()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in topology input set");
    }

    @Test
    void validationRejectsMediatingNodeNotInTopology() {
        var badMode = new ModeDetectionConfig(
                SituationalMode.Group.AFFECTIVE,
                List.of(new SignalConfig.CapsMediatingSignal(List.of("fake_mediating"), SignalConfig.Aggregation.MAX, SignalTransform.RISE_FROM_REST, 1.0)),
                List.of(), null,
                0.50, 0.42, Duration.ofHours(1)
        );
        var config = SituationalPersonalityConfig.withDefaults(Map.of(SituationalMode.STRESS, badMode));

        assertThatThrownBy(() -> config.validate(minimalTopology()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in topology mediating set");
    }

    @Test
    void validationRejectsWrongMediatingTransform() {
        var badMode = new ModeDetectionConfig(
                SituationalMode.Group.AFFECTIVE,
                List.of(new SignalConfig.CapsMediatingSignal(
                        List.of("threat_sensitivity"), SignalConfig.Aggregation.MAX,
                        SignalTransform.POSITIVE_PART, 1.0)),
                List.of(), null,
                0.50, 0.42, Duration.ofHours(1)
        );
        var config = SituationalPersonalityConfig.withDefaults(Map.of(SituationalMode.STRESS, badMode));

        assertThatThrownBy(() -> config.validate(minimalTopology()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RISE_FROM_REST or DROP_FROM_REST");
    }

    @Test
    void validationRejectsSubFloorHoldMarginViolation() {
        var badMode = new ModeDetectionConfig(
                SituationalMode.Group.AFFECTIVE,
                List.of(new SignalConfig.CapsMediatingSignal(
                        List.of("threat_sensitivity"), SignalConfig.Aggregation.MAX,
                        SignalTransform.RISE_FROM_REST, 1.0)),
                List.of(), null,
                0.50, 0.10, Duration.ofHours(1)
        );
        var config = new SituationalPersonalityConfig(
                new EnumMap<>(Map.of(SituationalMode.STRESS, badMode)),
                new SituationalPersonalityConfig.GainsConfig(0.5, 0.25, 0.5, 0.1),
                DynamicsConfig.defaults(), Map.of(),
                new SituationalPersonalityConfig.ClassifierCalibration(0.6, "", "", "")
        );

        assertThatThrownBy(() -> config.validate(minimalTopology()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sub-floor hold margin violated");
    }

    @Test
    void validConfigPassesValidation() {
        var loader = new SituationalPersonalityConfigLoader();
        var config = loader.loadFromClasspath("situational-personality.yaml");
        config.validate(testTopology());
    }

    @Test
    void signalTransformPositivePart() {
        assertThat(SignalTransform.POSITIVE_PART.apply(0.5)).isEqualTo(0.5);
        assertThat(SignalTransform.POSITIVE_PART.apply(-0.5)).isEqualTo(0.0);
    }

    @Test
    void signalTransformNegativePart() {
        assertThat(SignalTransform.NEGATIVE_PART.apply(-0.5)).isEqualTo(0.5);
        assertThat(SignalTransform.NEGATIVE_PART.apply(0.5)).isEqualTo(0.0);
    }

    @Test
    void signalTransformComplement() {
        assertThat(SignalTransform.COMPLEMENT.apply(0.3)).isCloseTo(0.7, Offset.offset(1e-9));
    }

    @Test
    void signalTransformAboveFloor() {
        assertThat(SignalTransform.ABOVE_FLOOR.applyWithFloor(0.5, 0.6)).isEqualTo(0.0);
        assertThat(SignalTransform.ABOVE_FLOOR.applyWithFloor(0.8, 0.6)).isCloseTo(0.5, Offset.offset(1e-9));
        assertThat(SignalTransform.ABOVE_FLOOR.applyWithFloor(1.0, 0.6)).isCloseTo(1.0, Offset.offset(1e-9));
    }

    @Test
    void signalTransformRiseFromRest() {
        assertThat(SignalTransform.RISE_FROM_REST.applyFromRest(0.5, 0.3, 1.0, 0.0)).isCloseTo(0.2857, Offset.offset(0.001));
        assertThat(SignalTransform.RISE_FROM_REST.applyFromRest(0.2, 0.3, 1.0, 0.0)).isEqualTo(0.0);
    }

    @Test
    void signalTransformDropFromRest() {
        assertThat(SignalTransform.DROP_FROM_REST.applyFromRest(0.1, 0.3, 1.0, 0.0)).isCloseTo(0.6667, Offset.offset(0.001));
        assertThat(SignalTransform.DROP_FROM_REST.applyFromRest(0.5, 0.3, 1.0, 0.0)).isEqualTo(0.0);
    }

    @Test
    void parameterisedBandTransform() {
        var band = new ParameterisedTransform.Band(0.25, 0.6);
        assertThat(band.apply(0.25)).isCloseTo(1.0, Offset.offset(1e-9));
        assertThat(band.apply(-0.35)).isCloseTo(0.0, Offset.offset(1e-9));
        assertThat(band.apply(0.85)).isCloseTo(0.0, Offset.offset(1e-9));
        assertThat(band.apply(0.55)).isCloseTo(0.5, Offset.offset(1e-9));
    }

    private static CapsTopology minimalTopology() {
        var nodes = new LinkedHashMap<String, CapsNode>();
        nodes.put("physical_threat", new CapsNode("physical_threat", NodeType.INPUT, NodeRange.UNIPOLAR, "threat", List.of(), Map.of()));
        nodes.put("social_threat", new CapsNode("social_threat", NodeType.INPUT, NodeRange.UNIPOLAR, "threat", List.of(), Map.of()));
        nodes.put("threat_sensitivity", new CapsNode("threat_sensitivity", NodeType.MEDIATING, NodeRange.UNIPOLAR, "mediating", List.of(), Map.of()));
        return new CapsTopology(1, nodes, List.of(), Map.of(), List.of(), null);
    }

    private static CapsTopology testTopology() {
        var nodes = new LinkedHashMap<String, CapsNode>();
        for (String id : List.of("physical_threat", "social_threat", "psychological_threat", "unpredictable_danger",
                "failure", "scarcity", "powerlessness", "rejection", "betrayal", "abandonment",
                "mastery", "competence_recognition", "choice_available", "agency_granted",
                "acceptance", "sharing", "perceived_responsiveness",
                "competition", "exclusion", "dominance",
                "power_asymmetry", "controlled", "submission")) {
            nodes.put(id, new CapsNode(id, NodeType.INPUT, NodeRange.UNIPOLAR, "input", List.of(), Map.of()));
        }
        for (String id : List.of("FFFS_activation", "BIS_activation", "threat_sensitivity",
                "BAS_activation", "self_efficacy",
                "other_safety", "other_reliability")) {
            nodes.put(id, new CapsNode(id, NodeType.MEDIATING, NodeRange.UNIPOLAR, "mediating", List.of(), Map.of()));
        }
        return new CapsTopology(1, nodes, List.of(), Map.of(), List.of(), null);
    }
}
