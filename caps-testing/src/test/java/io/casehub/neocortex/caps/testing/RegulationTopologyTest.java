package io.casehub.neocortex.caps.testing;

import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.NodeRange;
import io.casehub.neocortex.caps.NodeType;
import io.casehub.neocortex.caps.engine.CapsTopologyLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegulationTopologyTest {

    private static CapsTopology topology;

    @BeforeAll
    static void loadTopology() {
        topology = new CapsTopologyLoader().loadFromClasspath("caps-topology.yaml");
    }

    @Test
    void regulationOutputNodesExist() {
        var expected = List.of(
            "situation_selection", "situation_modification",
            "attentional_deployment", "cognitive_change", "response_modulation",
            "social_support_seeking", "social_sharing", "social_withdrawal",
            "co_regulation", "deliberate_provocation");
        for (var id : expected) {
            var node = topology.nodes().get(id);
            assertThat(node).as("Missing regulation output node: %s", id).isNotNull();
            assertThat(node.type()).isEqualTo(NodeType.OUTPUT);
            assertThat(node.range()).isEqualTo(NodeRange.UNIPOLAR);
            assertThat(node.category()).isEqualTo("regulation");
        }
    }

    @Test
    void socialContextInputNodesExist() {
        var expected = List.of(
            "perceived_responsiveness", "target_distress",
            "social_isolation", "power_asymmetry");
        for (var id : expected) {
            var node = topology.nodes().get(id);
            assertThat(node).as("Missing social-context input node: %s", id).isNotNull();
            assertThat(node.type()).isEqualTo(NodeType.INPUT);
            assertThat(node.category()).isEqualTo("social_context");
        }
    }

    @Test
    void intrapersonalRegulationConnectionsExist() {
        var connections = topology.connections();
        assertThat(connections).anyMatch(
            c -> c.from().equals("arousal_level") && c.to().equals("attentional_deployment"));
        assertThat(connections).anyMatch(
            c -> c.from().equals("self_efficacy") && c.to().equals("cognitive_change"));
        assertThat(connections).anyMatch(
            c -> c.from().equals("BIS_activation") && c.to().equals("response_modulation"));
        assertThat(connections).anyMatch(
            c -> c.from().equals("threat_sensitivity") && c.to().equals("situation_selection"));
        assertThat(connections).anyMatch(
            c -> c.from().equals("self_efficacy") && c.to().equals("situation_modification"));
    }

    @Test
    void socialRegulationConnectionsExist() {
        var connections = topology.connections();
        assertThat(connections).anyMatch(
            c -> c.from().equals("other_safety") && c.to().equals("social_support_seeking"));
        assertThat(connections).anyMatch(
            c -> c.from().equals("BIS_activation") && c.to().equals("social_withdrawal"));
        assertThat(connections).anyMatch(
            c -> c.from().equals("target_distress") && c.to().equals("co_regulation"));
        assertThat(connections).anyMatch(
            c -> c.from().equals("power_asymmetry") && c.to().equals("deliberate_provocation"));
    }

    @Test
    void copingProblemFocusedConnectionsExist() {
        var connections = topology.connections();
        assertThat(connections).anyMatch(
                c -> c.from().equals("self_efficacy") && c.to().equals("planning"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("BAS_activation") && c.to().equals("planning"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("self_efficacy") && c.to().equals("active_confrontation"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("BAS_activation") && c.to().equals("active_confrontation"));
    }

    @Test
    void copingAvoidantConnectionsExist() {
        var connections = topology.connections();
        assertThat(connections).anyMatch(
                c -> c.from().equals("BIS_activation") && c.to().equals("avoidance"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("threat_sensitivity") && c.to().equals("avoidance"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("BIS_activation") && c.to().equals("mental_disengagement"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("threat_sensitivity") && c.to().equals("mental_disengagement"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("BIS_activation") && c.to().equals("suppression"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("self_efficacy") && c.to().equals("avoidance")
                     && c.defaultWeight() < 0);
    }

    @Test
    void copingSupportSeekingConnectionsExist() {
        var connections = topology.connections();
        assertThat(connections).anyMatch(
                c -> c.from().equals("other_safety") && c.to().equals("seek_emotional_support"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("other_reliability") && c.to().equals("seek_instrumental_support"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("BAS_activation") && c.to().equals("seek_instrumental_support"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("other_safety") && c.to().equals("seek_instrumental_support"));
    }

    @Test
    void copingCognitiveConnectionsExist() {
        var connections = topology.connections();
        assertThat(connections).anyMatch(
                c -> c.from().equals("self_efficacy") && c.to().equals("positive_reinterpretation"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("self_worth") && c.to().equals("positive_reinterpretation"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("self_worth") && c.to().equals("coping_acceptance"));
        assertThat(connections).anyMatch(
                c -> c.from().equals("BIS_activation") && c.to().equals("coping_acceptance")
                     && c.defaultWeight() < 0);
    }

    @Test
    void socialContextToMediatingConnectionsExist() {
        var connections = topology.connections();
        assertThat(connections).anyMatch(
            c -> c.from().equals("perceived_responsiveness") && c.to().equals("other_safety"));
        assertThat(connections).anyMatch(
            c -> c.from().equals("social_isolation") && c.to().equals("BIS_activation"));
        assertThat(connections).anyMatch(
            c -> c.from().equals("power_asymmetry") && c.to().equals("social_comparison"));
    }


    @Test
    void copingToRegulationBridgeConnectionsExist() {
        var bridges = java.util.Map.of(
                "positive_reinterpretation", "cognitive_change",
                "avoidance", "situation_selection",
                "seek_instrumental_support", "social_support_seeking",
                "seek_emotional_support", "social_sharing",
                "mental_disengagement", "attentional_deployment",
                "active_confrontation", "situation_modification",
                "suppression", "response_modulation"
                                      );
        for (var entry : bridges.entrySet()) {
            boolean found = topology.connections().stream()
                                    .anyMatch(c -> entry.getKey().equals(c.from())
                                                   && entry.getValue().equals(c.to()));
            assertThat(found)
                    .as("Missing coping→regulation bridge: %s → %s",
                        entry.getKey(), entry.getValue())
                    .isTrue();
        }
    }

    @Test
    void topologyNodeCountIsCorrect() {
        assertThat(topology.nodes()).hasSize(107);
        assertThat(topology.nodes().values())
                .filteredOn(node -> "coping".equals(node.category()))
                .hasSize(16);
    }

    @Test
    void topologyConnectionCountIsCorrect() {assertThat(topology.connections()).hasSize(169);}
}
