package io.casehub.neocortex.cognition.prompt.observation;

import io.casehub.neocortex.cognitive.index.RelationalSchema;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.OverlayRef;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CognitionObservationBuilderTargetingTest {

    private InMemoryMindMapStore store;
    private static final String TENANT = "t1";
    private static final String OBSERVER = "hooded-claw";
    private static final String TARGET = "penelope-pitstop";

    @BeforeEach
    void setUp() {
        store = new InMemoryMindMapStore();
        seedPersonOverlay(OBSERVER, TARGET, "prey", -0.3, 0.0, 0.8,
            "She inherited everything you built");
    }

    @Test
    void resolvesTargetForDriveWithMatchingSchema() {
        var builder = createBuilder(List.of(
            new DriveSnapshot("gloating", 0.7, "jaw tightens")));

        var targeted = builder.resolveRelationalTargets(
            List.of(new DriveSnapshot("gloating", 0.7, "jaw tightens")),
            Set.of(TARGET),
            Map.of(TARGET, "Penelope"));

        assertThat(targeted).hasSize(1);
        assertThat(targeted.getFirst().hasTarget()).isTrue();
        assertThat(targeted.getFirst().targetAgentId()).isEqualTo(TARGET);
        assertThat(targeted.getFirst().targetDisplayName()).isEqualTo("Penelope");
        assertThat(targeted.getFirst().role()).isEqualTo("prey");
    }

    @Test
    void leavesNonSocialDriveUntargeted() {
        var builder = createBuilder(List.of(
            new DriveSnapshot("curiosity", 0.4, "pull behind your eyes")));

        var targeted = builder.resolveRelationalTargets(
            List.of(new DriveSnapshot("curiosity", 0.4, "pull behind your eyes")),
            Set.of(TARGET),
            Map.of(TARGET, "Penelope"));

        assertThat(targeted).hasSize(1);
        assertThat(targeted.getFirst().hasTarget()).isFalse();
    }

    @Test
    void returnsUntargetedWhenNoNearbyAgents() {
        var builder = createBuilder(List.of(
            new DriveSnapshot("gloating", 0.7, "jaw tightens")));

        var targeted = builder.resolveRelationalTargets(
            List.of(new DriveSnapshot("gloating", 0.7, "jaw tightens")),
            Set.of(),
            Map.of());

        assertThat(targeted).hasSize(1);
        assertThat(targeted.getFirst().hasTarget()).isFalse();
    }

    @Test
    void selectsMostSalientTargetWhenMultipleNearby() {
        seedPersonOverlay(OBSERVER, "peter-perfect", "obstacle", 0.1, 0.0, 0.2,
            "Gets in the way");

        var builder = createBuilder(List.of(
            new DriveSnapshot("gloating", 0.7, "jaw tightens")));

        var targeted = builder.resolveRelationalTargets(
            List.of(new DriveSnapshot("gloating", 0.7, "jaw tightens")),
            Set.of(TARGET, "peter-perfect"),
            Map.of(TARGET, "Penelope", "peter-perfect", "Peter"));

        assertThat(targeted).hasSize(1);
        assertThat(targeted.getFirst().hasTarget()).isTrue();
        assertThat(targeted.getFirst().targetAgentId())
            .as("prey is a better target for gloating than obstacle")
            .isEqualTo(TARGET);
    }

    private CognitionObservationBuilder createBuilder(List<DriveSnapshot> drives) {
        return new CognitionObservationBuilder(OBSERVER, TENANT, store, null, null, drives);
    }

    private void seedPersonOverlay(String observerId, String targetId,
                                   String role, double trust, double intimacy, double utility,
                                   String description) {
        var peopleSgs = store.listSubgraphs(TENANT).stream()
                             .filter(sg -> "people".equals(sg.name()))
                             .toList();
        String peopleSgId;
        if (peopleSgs.isEmpty()) {
            peopleSgId = store.createSubgraph(
                    new SubgraphInput("people", "people", null), TENANT);
        } else {
            peopleSgId = peopleSgs.getFirst().id();
        }

        var existingTargetNode = store.nodesIn(peopleSgId, TENANT).stream()
                                      .filter(n -> !n.traits().contains("overlay"))
                                      .filter(n -> targetId.equals(n.property("agentId").orElse(null)))
                                      .findFirst();
        String targetNodeId;
        if (existingTargetNode.isPresent()) {
            targetNodeId = existingTargetNode.get().id();
        } else {
            targetNodeId = store.addNode(
                    NodeInput.of(targetId, peopleSgId)
                             .withProperties(Map.of("agentId", targetId)),
                    TENANT);
        }

        var schema       = new RelationalSchema(targetId, role, trust, intimacy, utility, description);
        var overlayProps = new HashMap<>(schema.toProperties());
        overlayProps.put(OverlayRef.AGENT_ID, observerId);
        store.addNode(
                NodeInput.of("overlay-" + observerId + "-" + targetId, peopleSgId)
                         .withTraits(Set.of("overlay"))
                         .withRefs(Set.of(OverlayRef.of(targetNodeId)))
                         .withProperties(Map.copyOf(overlayProps)),
                TENANT);
    }
}