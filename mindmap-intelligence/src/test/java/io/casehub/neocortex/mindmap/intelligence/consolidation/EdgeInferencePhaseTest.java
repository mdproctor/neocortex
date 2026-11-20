package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.cognitive.ConfidenceOrigin;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.memory.experience.ExperienceAttributeKeys;
import io.casehub.neocortex.memory.inmem.InMemoryMemoryStore;
import io.casehub.neocortex.mindmap.EdgeInput;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.mindmap.intelligence.ParsedRelationship;
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
    }

    private EdgeInferencePhase phase(int degreeThreshold, int maxCandidates) {
        return new EdgeInferencePhase(mindMapStore, memoryStore, degreeThreshold, maxCandidates);
    }

    private TestableEdgeInferencePhase testablePhase(int degreeThreshold, int maxCandidates,
                                                      List<ParsedRelationship> llmResponse) {
        return new TestableEdgeInferencePhase(mindMapStore, memoryStore,
            degreeThreshold, maxCandidates, llmResponse);
    }

    private void storeExperienceMemory(String subject, String relatedEntities, String text) {
        var attrs = new HashMap<String, String>();
        attrs.put("subject", subject);
        if (relatedEntities != null) {
            attrs.put("related_entities", relatedEntities);
        }
        attrs.put(ExperienceAttributeKeys.EVENT_TYPE, "observation");
        memoryStore.store(new MemoryInput(
            Subject.of("agent", "test-agent"), new MemoryDomain("experience"),
            TENANT, null, text, attrs, null, null, null, null, null, null));
    }

    @Test
    void noOpWhenNoSessionPipeline() {
        var phase = phase(1, 30);
        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.PERSON, SubgraphTypes.PERSON, null), TENANT);
        mindMapStore.addNode(NodeInput.of("Alice", sgId), TENANT);
        mindMapStore.addNode(NodeInput.of("Bob", sgId), TENANT);

        phase.run(TENANT, List.of());

        var alice = mindMapStore.resolveNode("Alice", null, TENANT);
        assertThat(mindMapStore.neighbors(alice.id(), TENANT)).isEmpty();
    }

    @Test
    void findsUnderConnectedNodes() {
        var phase = phase(1, 30);
        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.PERSON, SubgraphTypes.PERSON, null), TENANT);
        String aliceId = mindMapStore.addNode(NodeInput.of("Alice", sgId), TENANT);
        String bobId = mindMapStore.addNode(NodeInput.of("Bob", sgId), TENANT);
        String carolId = mindMapStore.addNode(NodeInput.of("Carol", sgId), TENANT);
        mindMapStore.addEdge(EdgeInput.of(aliceId, bobId, "knows"), TENANT);
        mindMapStore.addEdge(EdgeInput.of(aliceId, carolId, "knows"), TENANT);

        List<MindMapNode> result = phase.findUnderConnectedNodes(TENANT);

        assertThat(result).extracting(MindMapNode::name)
            .contains("Bob", "Carol")
            .doesNotContain("Alice");
    }

    @Test
    void buildsCandidatesFromSharedMemories() {
        var phase = phase(1, 30);
        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.PERSON, SubgraphTypes.PERSON, null), TENANT);
        mindMapStore.addNode(NodeInput.of("Alice", sgId), TENANT);
        mindMapStore.addNode(NodeInput.of("Bob", sgId), TENANT);

        storeExperienceMemory("Alice", "Bob", "Alice and Bob collaborated on research");

        List<MindMapNode> underConnected = phase.findUnderConnectedNodes(TENANT);
        List<EdgeInferencePhase.CandidatePair> candidates =
            phase.buildCandidates(underConnected, TENANT);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().coOccurrenceCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void excludesPairsWithExistingEdge() {
        var phase = phase(0, 30);
        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.PERSON, SubgraphTypes.PERSON, null), TENANT);
        String aliceId = mindMapStore.addNode(NodeInput.of("Alice", sgId), TENANT);
        String bobId = mindMapStore.addNode(NodeInput.of("Bob", sgId), TENANT);
        mindMapStore.addEdge(EdgeInput.of(aliceId, bobId, "knows"), TENANT);

        storeExperienceMemory("Alice", "Bob", "Alice and Bob met at a conference");

        List<MindMapNode> underConnected = phase.findUnderConnectedNodes(TENANT);
        List<EdgeInferencePhase.CandidatePair> candidates =
            phase.buildCandidates(underConnected, TENANT);

        assertThat(candidates).isEmpty();
    }

    @Test
    void noCandidatesWhenNoSharedMemories() {
        var phase = phase(1, 30);
        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.PERSON, SubgraphTypes.PERSON, null), TENANT);
        mindMapStore.addNode(NodeInput.of("Alice", sgId), TENANT);
        mindMapStore.addNode(NodeInput.of("Bob", sgId), TENANT);

        storeExperienceMemory("Alice", null, "Alice went to school");
        storeExperienceMemory("Bob", null, "Bob went to work");

        List<MindMapNode> underConnected = phase.findUnderConnectedNodes(TENANT);
        List<EdgeInferencePhase.CandidatePair> candidates =
            phase.buildCandidates(underConnected, TENANT);

        assertThat(candidates).isEmpty();
    }

    @Test
    void persistsInferredEdges() {
        var phase = testablePhase(1, 30, List.of(
            new ParsedRelationship("Alice", "Bob", "collaborated-with", ConfidenceOrigin.INFERRED)));

        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.PERSON, SubgraphTypes.PERSON, null), TENANT);
        mindMapStore.addNode(NodeInput.of("Alice", sgId), TENANT);
        mindMapStore.addNode(NodeInput.of("Bob", sgId), TENANT);

        storeExperienceMemory("Alice", "Bob", "Alice and Bob collaborated on research");

        phase.run(TENANT, List.of());

        var alice = mindMapStore.resolveNode("Alice", null, TENANT);
        var edges = mindMapStore.neighbors(alice.id(), TENANT);
        assertThat(edges).hasSize(1);
        assertThat(edges.getFirst().edgeType()).isEqualTo("collaborated-with");
    }

    @Test
    void skipsDuplicateEdgeOnPersist() {
        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.PERSON, SubgraphTypes.PERSON, null), TENANT);
        String aliceId = mindMapStore.addNode(NodeInput.of("Alice", sgId), TENANT);
        String bobId = mindMapStore.addNode(NodeInput.of("Bob", sgId), TENANT);
        mindMapStore.addEdge(EdgeInput.of(aliceId, bobId, "collaborated-with"), TENANT);

        var phase = phase(1, 30);
        phase.persistEdges(List.of(
            new ParsedRelationship("Alice", "Bob", "collaborated-with", ConfidenceOrigin.INFERRED)),
            TENANT);

        assertThat(mindMapStore.neighbors(aliceId, TENANT)).hasSize(1);
    }

    static class TestableEdgeInferencePhase extends EdgeInferencePhase {
        private final List<ParsedRelationship> fixedResponse;

        TestableEdgeInferencePhase(MindMapStore mindMapStore, CaseMemoryStore memoryStore,
                                   int degreeThreshold, int maxCandidates,
                                   List<ParsedRelationship> fixedResponse) {
            super(mindMapStore, memoryStore, degreeThreshold, maxCandidates);
            this.fixedResponse = fixedResponse;
        }

        @Override
        List<ParsedRelationship> invokeEdgeInference(List<CandidatePair> candidates) {
            return fixedResponse;
        }

        @Override
        public void run(String tenantId, List<String> subgraphPriority) {
            if (memoryStore == null) return;

            List<MindMapNode> underConnected = findUnderConnectedNodes(tenantId);
            if (underConnected.isEmpty()) return;

            List<CandidatePair> candidates = buildCandidates(underConnected, tenantId);
            if (candidates.isEmpty()) return;

            List<ParsedRelationship> inferred = invokeEdgeInference(candidates);
            if (!inferred.isEmpty()) {
                persistEdges(inferred, tenantId);
            }
        }
    }
}
