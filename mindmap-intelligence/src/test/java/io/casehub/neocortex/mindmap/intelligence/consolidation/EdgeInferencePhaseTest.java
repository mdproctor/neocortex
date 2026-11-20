package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.memory.experience.ExperienceAttributeKeys;
import io.casehub.neocortex.memory.inmem.InMemoryMemoryStore;
import io.casehub.neocortex.mindmap.EdgeInput;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeInferencePhaseTest {

    private static final String TENANT = "test-tenant";

    private InMemoryMemoryStore memoryStore;
    private InMemoryMindMapStore mindMapStore;
    private EdgeInferencePhase phase;

    private final CurrentPrincipal principal = new CurrentPrincipal() {
        @Override public String actorId() { return "actor"; }
        @Override public Set<String> groups() { return Set.of(); }
        @Override public String tenancyId() { return TENANT; }
        @Override public boolean isCrossTenantAdmin() { return true; }
    };

    @BeforeEach
    void setUp() {
        memoryStore = new InMemoryMemoryStore(principal);
        mindMapStore = new InMemoryMindMapStore();
        phase = new EdgeInferencePhase(mindMapStore, memoryStore, 1, 30);
    }

    @Test
    void noOpWhenNoSessionPipeline() {
        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.PERSON, SubgraphTypes.PERSON, null), TENANT);
        mindMapStore.addNode(NodeInput.of("Alice", sgId), TENANT);
        mindMapStore.addNode(NodeInput.of("Bob", sgId), TENANT);

        phase.run(TENANT, List.of());

        var alice = mindMapStore.resolveNode("Alice", null, TENANT);
        assertThat(mindMapStore.neighbors(alice.id(), TENANT)).isEmpty();
    }
}
