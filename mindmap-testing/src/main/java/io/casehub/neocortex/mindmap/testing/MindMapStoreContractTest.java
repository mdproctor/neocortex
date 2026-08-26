package io.casehub.neocortex.mindmap.testing;

import io.casehub.neocortex.mindmap.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class MindMapStoreContractTest {

    protected static final String TENANT = "tenant-1";
    protected static final String TENANT_2 = "tenant-2";

    protected MindMapStore store;
    private String subgraphId;

    protected abstract MindMapStore createStore();

    @BeforeEach
    void setUp() {
        store = createStore();
        store.registerVocabulary(MindMapVocabulary.builder()
            .edgeType("works-at", "employed-by", "job-at")
            .edgeType("parent-of")
            .edgeType("uses")
            .edgeType("related-to")
            .build());
        subgraphId = store.createSubgraph(
            new SubgraphInput("Test Graph", SubgraphType.GENERAL, null), TENANT);
    }

    protected String defaultSubgraphId() {
        return subgraphId;
    }

    protected NodeInput nodeInput(String name) {
        return new NodeInput(name, subgraphId, ConfidenceOrigin.STATED, null,
            "test", null, null, null, null, null, null, null, null);
    }

    protected NodeInput nodeInput(String name, String sgId) {
        return new NodeInput(name, sgId, ConfidenceOrigin.STATED, null,
            "test", null, null, null, null, null, null, null, null);
    }

    // --- Subgraph tests ---

    @Test
    void createSubgraph_returnsId() {
        String id = store.createSubgraph(
            new SubgraphInput("People", SubgraphType.PERSON, null), TENANT);
        assertThat(id).isNotBlank();
    }

    @Test
    void getSubgraph_returnsStoredSubgraph() {
        String id = store.createSubgraph(
            new SubgraphInput("Projects", SubgraphType.PROJECT, null), TENANT);
        MindMapSubgraph sg = store.getSubgraph(id, TENANT);
        assertThat(sg.name()).isEqualTo("Projects");
        assertThat(sg.type()).isEqualTo(SubgraphType.PROJECT);
        assertThat(sg.tenantId()).isEqualTo(TENANT);
        assertThat(sg.rootNodeId()).isNull();
    }

    @Test
    void updateSubgraph_setsRootNodeId() {
        String sgId = store.createSubgraph(
            new SubgraphInput("People", SubgraphType.PERSON, null), TENANT);
        String nodeId = store.addNode(nodeInput("Alice", sgId), TENANT);
        store.updateSubgraph(sgId, nodeId, TENANT);
        MindMapSubgraph sg = store.getSubgraph(sgId, TENANT);
        assertThat(sg.rootNodeId()).isEqualTo(nodeId);
    }

    // --- Node CRUD tests ---

    @Test
    void addNode_returnsNonNullId() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        assertThat(id).isNotBlank();
    }

    @Test
    void getNode_returnsStoredNode() {
        String id = store.addNode(new NodeInput("Alice", subgraphId,
            ConfidenceOrigin.STATED, null, "conversation",
            Set.of("Personable"), Set.of(new NodeRef("memory", "m-1", null)),
            null, null, 0.5, 0.2, -0.1,
            Map.of("birthday", "1990-01-15")), TENANT);

        MindMapNode node = store.getNode(id, TENANT);
        assertThat(node.name()).isEqualTo("Alice");
        assertThat(node.subgraphId()).isEqualTo(subgraphId);
        assertThat(node.confidenceOrigin()).isEqualTo(ConfidenceOrigin.STATED);
        assertThat(node.confidence()).isEqualTo(1.0);
        assertThat(node.provenance()).isEqualTo("conversation");
        assertThat(node.traits()).containsExactly("Personable");
        assertThat(node.refs()).containsExactly(new NodeRef("memory", "m-1", null));
        assertThat(node.pleasure()).isEqualTo(0.5);
        assertThat(node.arousal()).isEqualTo(0.2);
        assertThat(node.dominance()).isEqualTo(-0.1);
        assertThat(node.property("birthday")).contains("1990-01-15");
    }

    @Test
    void getNode_nonExistent_returnsNull() {
        assertThat(store.getNode("nonexistent", TENANT)).isNull();
    }

    @Test
    void addNode_defaultConfidence_fromOrigin() {
        String id = store.addNode(new NodeInput("Bob", subgraphId,
            ConfidenceOrigin.INFERRED, null, "test",
            null, null, null, null, null, null, null, null), TENANT);
        MindMapNode node = store.getNode(id, TENANT);
        assertThat(node.confidence()).isEqualTo(0.7);
    }

    @Test
    void updateNode_changesName() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        store.updateNode(id, new NodeUpdate("Alicia", null, null, null,
            null, null, null, null, null, null, null, null, null, null, null), TENANT);
        assertThat(store.getNode(id, TENANT).name()).isEqualTo("Alicia");
    }

    @Test
    void updateNode_addsTraits() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        store.updateNode(id, new NodeUpdate(null, null, null, null,
            Set.of("Personable", "TeamMember"), null, null, null,
            null, null, null, null, null, null, null), TENANT);
        assertThat(store.getNode(id, TENANT).traits())
            .containsExactlyInAnyOrder("Personable", "TeamMember");
    }

    @Test
    void updateNode_removesTraits() {
        String id = store.addNode(new NodeInput("Alice", subgraphId,
            ConfidenceOrigin.STATED, null, "test",
            Set.of("Personable", "TeamMember"), null, null, null,
            null, null, null, null), TENANT);
        store.updateNode(id, new NodeUpdate(null, null, null, null,
            null, Set.of("TeamMember"), null, null,
            null, null, null, null, null, null, null), TENANT);
        assertThat(store.getNode(id, TENANT).traits()).containsExactly("Personable");
    }

    @Test
    void updateNode_setsProperties() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        store.updateNode(id, new NodeUpdate(null, null, null, null,
            null, null, null, null, null, null, null, null, null,
            Map.of("role", "engineer", "team", "platform"), null), TENANT);
        MindMapNode node = store.getNode(id, TENANT);
        assertThat(node.property("role")).contains("engineer");
        assertThat(node.property("team")).contains("platform");
    }

    @Test
    void updateNode_removesProperties() {
        String id = store.addNode(new NodeInput("Alice", subgraphId,
            ConfidenceOrigin.STATED, null, "test", null, null,
            null, null, null, null, null,
            Map.of("role", "engineer", "team", "platform")), TENANT);
        store.updateNode(id, new NodeUpdate(null, null, null, null,
            null, null, null, null, null, null, null, null, null,
            null, Set.of("team")), TENANT);
        MindMapNode node = store.getNode(id, TENANT);
        assertThat(node.property("role")).contains("engineer");
        assertThat(node.property("team")).isEmpty();
    }

    @Test
    void updateNode_setsConfirmedAt() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        Instant confirmed = Instant.now();
        store.updateNode(id, new NodeUpdate(null, null, null, confirmed,
            null, null, null, null, null, null, null, null, null, null, null), TENANT);
        assertThat(store.getNode(id, TENANT).confirmedAt()).isEqualTo(confirmed);
    }

    @Test
    void updateNode_confirmedAtWithoutConfidence_resetsTo1() {
        String id = store.addNode(new NodeInput("Alice", subgraphId,
            ConfidenceOrigin.SPECULATED, null, "test",
            null, null, null, null, null, null, null, null), TENANT);
        assertThat(store.getNode(id, TENANT).confidence()).isEqualTo(0.3);

        store.updateNode(id, new NodeUpdate(null, null, null, Instant.now(),
            null, null, null, null, null, null, null, null, null, null, null), TENANT);
        assertThat(store.getNode(id, TENANT).confidence()).isEqualTo(1.0);
    }

    @Test
    void updateNode_confirmedAtWithConfidence_usesProvidedValue() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        store.updateNode(id, new NodeUpdate(null, null, 0.8, Instant.now(),
            null, null, null, null, null, null, null, null, null, null, null), TENANT);
        assertThat(store.getNode(id, TENANT).confidence()).isEqualTo(0.8);
    }

    @Test
    void updateNode_setsAffect() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        store.updateNode(id, new NodeUpdate(null, null, null, null,
            null, null, null, null, null, null, 0.7, -0.3, 0.5, null, null), TENANT);
        MindMapNode node = store.getNode(id, TENANT);
        assertThat(node.pleasure()).isEqualTo(0.7);
        assertThat(node.arousal()).isEqualTo(-0.3);
        assertThat(node.dominance()).isEqualTo(0.5);
    }

    // --- Subgraph membership ---

    @Test
    void nodesIn_returnsNodesInSubgraph() {
        String sg1 = store.createSubgraph(
            new SubgraphInput("People", SubgraphType.PERSON, null), TENANT);
        store.addNode(nodeInput("Alice", sg1), TENANT);
        store.addNode(nodeInput("Bob", sg1), TENANT);

        assertThat(store.nodesIn(sg1, TENANT)).hasSize(2);
    }

    @Test
    void nodesIn_excludesOtherSubgraphs() {
        String sg1 = store.createSubgraph(
            new SubgraphInput("People", SubgraphType.PERSON, null), TENANT);
        String sg2 = store.createSubgraph(
            new SubgraphInput("Projects", SubgraphType.PROJECT, null), TENANT);
        store.addNode(nodeInput("Alice", sg1), TENANT);
        store.addNode(nodeInput("Neocortex", sg2), TENANT);

        assertThat(store.nodesIn(sg1, TENANT)).hasSize(1);
        assertThat(store.nodesIn(sg1, TENANT).getFirst().name()).isEqualTo("Alice");
    }

    // --- Alias tests ---

    @Test
    void addAlias_resolvesNode() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        store.addAlias(id, "Dr. Smith", TENANT);
        MindMapNode resolved = store.resolveNode("Dr. Smith", null, TENANT);
        assertThat(resolved).isNotNull();
        assertThat(resolved.id()).isEqualTo(id);
    }

    @Test
    void removeAlias_noLongerResolves() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        store.addAlias(id, "Dr. Smith", TENANT);
        store.removeAlias(id, "Dr. Smith", TENANT);
        assertThat(store.resolveNode("Dr. Smith", null, TENANT)).isNull();
    }

    @Test
    void resolveNode_byName() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        MindMapNode resolved = store.resolveNode("Alice", null, TENANT);
        assertThat(resolved).isNotNull();
        assertThat(resolved.id()).isEqualTo(id);
    }

    @Test
    void resolveNode_byAlias() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        store.addAlias(id, "Al", TENANT);
        MindMapNode resolved = store.resolveNode("Al", null, TENANT);
        assertThat(resolved).isNotNull();
        assertThat(resolved.id()).isEqualTo(id);
    }

    @Test
    void resolveNode_nullSubgraphId_searchesAll() {
        String sg1 = store.createSubgraph(
            new SubgraphInput("People", SubgraphType.PERSON, null), TENANT);
        String id = store.addNode(nodeInput("Alice", sg1), TENANT);
        store.addAlias(id, "Dr. A", TENANT);

        MindMapNode resolved = store.resolveNode("Dr. A", null, TENANT);
        assertThat(resolved).isNotNull();
        assertThat(resolved.id()).isEqualTo(id);
    }

    @Test
    void resolveNode_withSubgraphId_scopesSearch() {
        String sg1 = store.createSubgraph(
            new SubgraphInput("People", SubgraphType.PERSON, null), TENANT);
        String sg2 = store.createSubgraph(
            new SubgraphInput("Projects", SubgraphType.PROJECT, null), TENANT);
        store.addNode(nodeInput("Alice", sg1), TENANT);
        store.addNode(nodeInput("Alice", sg2), TENANT);

        MindMapNode resolved = store.resolveNode("Alice", sg1, TENANT);
        assertThat(resolved).isNotNull();
        assertThat(resolved.subgraphId()).isEqualTo(sg1);
    }

    @Test
    void resolveNode_unknownName_returnsNull() {
        assertThat(store.resolveNode("Unknown", null, TENANT)).isNull();
    }

    // --- Tenant isolation ---

    @Test
    void tenantIsolation_nodeInvisibleAcrossTenants() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        assertThat(store.getNode(id, TENANT_2)).isNull();
    }

    @Test
    void tenantIsolation_subgraphInvisibleAcrossTenants() {
        assertThat(store.getSubgraph(subgraphId, TENANT_2)).isNull();
    }

    @Test
    void tenantIsolation_aliasInvisibleAcrossTenants() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        store.addAlias(id, "Dr. Smith", TENANT);
        assertThat(store.resolveNode("Dr. Smith", null, TENANT_2)).isNull();
    }

    // ===== Batch 3: Edge + Vocabulary + Traversal + Search =====

    protected EdgeInput edgeInput(String src, String tgt, String type) {
        return new EdgeInput(src, tgt, type, ConfidenceOrigin.STATED, null,
            "test", null, null, null, null, null, null);
    }

    @Test
    void addEdge_returnsId() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        String b = store.addNode(nodeInput("Bob"), TENANT);
        String edgeId = store.addEdge(edgeInput(a, b, "works-at"), TENANT);
        assertThat(edgeId).isNotBlank();
    }

    @Test
    void getEdge_returnsStoredEdge() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        String b = store.addNode(nodeInput("Acme"), TENANT);
        String edgeId = store.addEdge(new EdgeInput(a, b, "works-at",
            ConfidenceOrigin.STATED, null, "conversation",
            Instant.parse("2020-01-01T00:00:00Z"), null,
            -0.2, 0.5, 0.0, Map.of("role", "engineer")), TENANT);

        MindMapEdge edge = store.getEdge(edgeId, TENANT);
        assertThat(edge.sourceNodeId()).isEqualTo(a);
        assertThat(edge.targetNodeId()).isEqualTo(b);
        assertThat(edge.edgeType()).isEqualTo("works-at");
        assertThat(edge.tier()).isEqualTo(ValidationTier.REGISTERED);
        assertThat(edge.validFrom()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(edge.pleasure()).isEqualTo(-0.2);
        assertThat(edge.property("role")).contains("engineer");
    }

    @Test
    void removeEdge_deletesEdge() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        String b = store.addNode(nodeInput("Bob"), TENANT);
        String edgeId = store.addEdge(edgeInput(a, b, "works-at"), TENANT);
        store.removeEdge(edgeId, TENANT);
        assertThat(store.getEdge(edgeId, TENANT)).isNull();
    }

    @Test
    void addEdge_registeredType_setsTierRegistered() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        String b = store.addNode(nodeInput("Acme"), TENANT);
        String edgeId = store.addEdge(edgeInput(a, b, "works-at"), TENANT);
        assertThat(store.getEdge(edgeId, TENANT).tier()).isEqualTo(ValidationTier.REGISTERED);
    }

    @Test
    void addEdge_unregisteredType_setsTierUnvalidated() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        String b = store.addNode(nodeInput("Bob"), TENANT);
        String edgeId = store.addEdge(edgeInput(a, b, "friend-of"), TENANT);
        assertThat(store.getEdge(edgeId, TENANT).tier()).isEqualTo(ValidationTier.UNVALIDATED);
    }

    @Test
    void addEdge_aliasType_normalizesToCanonical() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        String b = store.addNode(nodeInput("Acme"), TENANT);
        String edgeId = store.addEdge(edgeInput(a, b, "employed-by"), TENANT);
        MindMapEdge edge = store.getEdge(edgeId, TENANT);
        assertThat(edge.edgeType()).isEqualTo("works-at");
        assertThat(edge.tier()).isEqualTo(ValidationTier.REGISTERED);
    }

    @Test
    void registerVocabulary_mergesDefinitions() {
        store.registerVocabulary(MindMapVocabulary.builder()
            .edgeType("member-of")
            .build());
        String a = store.addNode(nodeInput("Alice"), TENANT);
        String b = store.addNode(nodeInput("Team"), TENANT);
        String edgeId = store.addEdge(edgeInput(a, b, "member-of"), TENANT);
        assertThat(store.getEdge(edgeId, TENANT).tier()).isEqualTo(ValidationTier.REGISTERED);
    }

    @Test
    void registerVocabulary_conflictingAlias_throws() {
        assertThatThrownBy(() -> store.registerVocabulary(MindMapVocabulary.builder()
            .edgeType("employed-by", "works-at")
            .build()))
            .isInstanceOf(VocabularyConflictException.class);
    }

    @Test
    void neighbors_returnsAllEdges() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        String b = store.addNode(nodeInput("Acme"), TENANT);
        String c = store.addNode(nodeInput("Bob"), TENANT);
        store.addEdge(edgeInput(a, b, "works-at"), TENANT);
        store.addEdge(edgeInput(a, c, "parent-of"), TENANT);
        assertThat(store.neighbors(a, TENANT)).hasSize(2);
    }

    @Test
    void neighbors_filteredByType() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        String b = store.addNode(nodeInput("Acme"), TENANT);
        String c = store.addNode(nodeInput("Bob"), TENANT);
        store.addEdge(edgeInput(a, b, "works-at"), TENANT);
        store.addEdge(edgeInput(a, c, "parent-of"), TENANT);
        assertThat(store.neighbors(a, "works-at", TENANT)).hasSize(1);
    }

    @Test
    void neighbors_emptyForIsolatedNode() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        assertThat(store.neighbors(a, TENANT)).isEmpty();
    }

    @Test
    void bridgeEdges_returnsCrossSubgraphEdges() {
        String sg1 = store.createSubgraph(
            new SubgraphInput("People", SubgraphType.PERSON, null), TENANT);
        String sg2 = store.createSubgraph(
            new SubgraphInput("Orgs", SubgraphType.ORGANISATION, null), TENANT);
        String alice = store.addNode(nodeInput("Alice", sg1), TENANT);
        String acme = store.addNode(nodeInput("Acme", sg2), TENANT);
        store.addEdge(edgeInput(alice, acme, "works-at"), TENANT);

        assertThat(store.bridgeEdges(sg1, TENANT)).hasSize(1);
    }

    @Test
    void bridgeEdges_excludesInternalEdges() {
        String sg1 = store.createSubgraph(
            new SubgraphInput("People", SubgraphType.PERSON, null), TENANT);
        String alice = store.addNode(nodeInput("Alice", sg1), TENANT);
        String bob = store.addNode(nodeInput("Bob", sg1), TENANT);
        store.addEdge(edgeInput(alice, bob, "related-to"), TENANT);

        assertThat(store.bridgeEdges(sg1, TENANT)).isEmpty();
    }

    @Test
    void search_byText_matchesNodeName() {
        store.addNode(nodeInput("Alice"), TENANT);
        store.addNode(nodeInput("Bob"), TENANT);
        var results = store.search(new MindMapQuery(TENANT, null, "Ali",
            null, null, null, null, false, 10));
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().name()).isEqualTo("Alice");
    }

    @Test
    void search_bySubgraph() {
        String sg1 = store.createSubgraph(
            new SubgraphInput("People", SubgraphType.PERSON, null), TENANT);
        store.addNode(nodeInput("Alice", sg1), TENANT);
        store.addNode(nodeInput("Bob"), TENANT);

        var results = store.search(new MindMapQuery(TENANT, sg1, null,
            null, null, null, null, false, 10));
        assertThat(results).hasSize(1);
    }

    @Test
    void search_byTraits() {
        String id = store.addNode(nodeInput("Alice"), TENANT);
        store.updateNode(id, new NodeUpdate(null, null, null, null,
            Set.of("Personable"), null, null, null, null, null,
            null, null, null, null, null), TENANT);
        store.addNode(nodeInput("Acme"), TENANT);

        var results = store.search(new MindMapQuery(TENANT, null, null,
            null, Set.of("Personable"), null, null, false, 10));
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().name()).isEqualTo("Alice");
    }

    @Test
    void search_byEdgeType_returnsConnectedNodes() {
        String alice = store.addNode(nodeInput("Alice"), TENANT);
        String acme = store.addNode(nodeInput("Acme"), TENANT);
        store.addNode(nodeInput("Bob"), TENANT);
        store.addEdge(edgeInput(alice, acme, "works-at"), TENANT);

        var results = store.search(new MindMapQuery(TENANT, null, null,
            "works-at", null, null, null, false, 10));
        assertThat(results).hasSize(2);
    }

    @Test
    void search_byMinConfidence() {
        store.addNode(new NodeInput("Alice", subgraphId, ConfidenceOrigin.STATED,
            0.9, "test", null, null, null, null, null, null, null, null), TENANT);
        store.addNode(new NodeInput("Bob", subgraphId, ConfidenceOrigin.SPECULATED,
            null, "test", null, null, null, null, null, null, null, null), TENANT);

        var results = store.search(new MindMapQuery(TENANT, null, null,
            null, null, 0.5, null, false, 10));
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().name()).isEqualTo("Alice");
    }

    @Test
    void search_byConfidenceOrigin() {
        store.addNode(new NodeInput("Alice", subgraphId, ConfidenceOrigin.STATED,
            null, "test", null, null, null, null, null, null, null, null), TENANT);
        store.addNode(new NodeInput("Bob", subgraphId, ConfidenceOrigin.INFERRED,
            null, "test", null, null, null, null, null, null, null, null), TENANT);

        var results = store.search(new MindMapQuery(TENANT, null, null,
            null, null, null, ConfidenceOrigin.INFERRED, false, 10));
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().name()).isEqualTo("Bob");
    }

    @Test
    void search_excludesSupersededByDefault() {
        String alice = store.addNode(nodeInput("Alice"), TENANT);
        String bob = store.addNode(nodeInput("Bob"), TENANT);
        store.supersede(alice, bob, "merged", TENANT);

        var results = store.search(new MindMapQuery(TENANT, null, null,
            null, null, null, null, false, 10));
        assertThat(results).noneMatch(n -> n.id().equals(alice));
    }

    @Test
    void search_includeSuperseded_returnsAll() {
        String alice = store.addNode(nodeInput("Alice"), TENANT);
        String bob = store.addNode(nodeInput("Bob"), TENANT);
        store.supersede(alice, bob, "merged", TENANT);

        var results = store.search(new MindMapQuery(TENANT, null, null,
            null, null, null, null, true, 10));
        assertThat(results).anyMatch(n -> n.id().equals(alice));
    }

    @Test
    void search_respectsLimit() {
        for (int i = 0; i < 5; i++) store.addNode(nodeInput("Node" + i), TENANT);
        var results = store.search(new MindMapQuery(TENANT, null, null,
            null, null, null, null, false, 3));
        assertThat(results).hasSize(3);
    }

    @Test
    void tenantIsolation_edgeInvisibleAcrossTenants() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        String b = store.addNode(nodeInput("Bob"), TENANT);
        String edgeId = store.addEdge(edgeInput(a, b, "works-at"), TENANT);
        assertThat(store.getEdge(edgeId, TENANT_2)).isNull();
    }

    // ===== Batch 4: Merge + Supersession + Erasure =====

    @Test
    void mergeNodes_keepsTargetNode() {
        String keep = store.addNode(nodeInput("Alice"), TENANT);
        String remove = store.addNode(nodeInput("Al"), TENANT);
        MergeResult result = store.mergeNodes(keep, remove, TENANT);
        assertThat(result.survivingNodeId()).isEqualTo(keep);
        assertThat(store.getNode(keep, TENANT)).isNotNull();
        assertThat(store.getNode(remove, TENANT)).isNull();
    }

    @Test
    void mergeNodes_removesSourceNode() {
        String keep = store.addNode(nodeInput("Alice"), TENANT);
        String remove = store.addNode(nodeInput("Al"), TENANT);
        store.mergeNodes(keep, remove, TENANT);
        assertThat(store.getNode(remove, TENANT)).isNull();
    }

    @Test
    void mergeNodes_unionsAliases() {
        String keep = store.addNode(nodeInput("Alice"), TENANT);
        String remove = store.addNode(nodeInput("Al"), TENANT);
        store.addAlias(remove, "A.Smith", TENANT);
        store.mergeNodes(keep, remove, TENANT);
        assertThat(store.resolveNode("A.Smith", null, TENANT).id()).isEqualTo(keep);
    }

    @Test
    void mergeNodes_repointsEdges() {
        String keep = store.addNode(nodeInput("Alice"), TENANT);
        String remove = store.addNode(nodeInput("Al"), TENANT);
        String acme = store.addNode(nodeInput("Acme"), TENANT);
        store.addEdge(edgeInput(remove, acme, "works-at"), TENANT);
        store.mergeNodes(keep, remove, TENANT);

        assertThat(store.neighbors(keep, TENANT)).hasSize(1);
        assertThat(store.neighbors(keep, TENANT).getFirst().sourceNodeId()).isEqualTo(keep);
    }

    @Test
    void mergeNodes_deduplicatesEdges_newerUpdatedAtWins() {
        String keep = store.addNode(nodeInput("Alice"), TENANT);
        String remove = store.addNode(nodeInput("Al"), TENANT);
        String acme = store.addNode(nodeInput("Acme"), TENANT);
        store.addEdge(edgeInput(keep, acme, "works-at"), TENANT);
        store.addEdge(edgeInput(remove, acme, "works-at"), TENANT);

        MergeResult result = store.mergeNodes(keep, remove, TENANT);
        assertThat(result.duplicateEdgesRemoved()).isEqualTo(1);
        assertThat(store.neighbors(keep, "works-at", TENANT)).hasSize(1);
    }

    @Test
    void mergeNodes_unionsTraits() {
        String keep = store.addNode(new NodeInput("Alice", subgraphId,
            ConfidenceOrigin.STATED, null, "test",
            Set.of("Personable"), null, null, null, null, null, null, null), TENANT);
        String remove = store.addNode(new NodeInput("Al", subgraphId,
            ConfidenceOrigin.STATED, null, "test",
            Set.of("TeamMember"), null, null, null, null, null, null, null), TENANT);

        MergeResult result = store.mergeNodes(keep, remove, TENANT);
        assertThat(result.traitsMerged()).contains("TeamMember");
        assertThat(store.getNode(keep, TENANT).traits())
            .containsExactlyInAnyOrder("Personable", "TeamMember");
    }

    @Test
    void mergeNodes_unionsNodeRefs() {
        String keep = store.addNode(new NodeInput("Alice", subgraphId,
            ConfidenceOrigin.STATED, null, "test", null,
            Set.of(new NodeRef("memory", "m-1", null)),
            null, null, null, null, null, null), TENANT);
        String remove = store.addNode(new NodeInput("Al", subgraphId,
            ConfidenceOrigin.STATED, null, "test", null,
            Set.of(new NodeRef("cbr", "c-1", "clinical")),
            null, null, null, null, null, null), TENANT);

        store.mergeNodes(keep, remove, TENANT);
        Set<NodeRef> refs = store.getNode(keep, TENANT).refs();
        assertThat(refs).hasSize(2);
        assertThat(refs).contains(new NodeRef("memory", "m-1", null));
        assertThat(refs).contains(new NodeRef("cbr", "c-1", "clinical"));
    }

    @Test
    void mergeNodes_propertyConflict_newerWins() {
        String keep = store.addNode(new NodeInput("Alice", subgraphId,
            ConfidenceOrigin.STATED, null, "test", null, null,
            null, null, null, null, null,
            Map.of("role", "junior")), TENANT);
        String remove = store.addNode(new NodeInput("Al", subgraphId,
            ConfidenceOrigin.STATED, null, "test", null, null,
            null, null, null, null, null,
            Map.of("role", "senior")), TENANT);

        MergeResult result = store.mergeNodes(keep, remove, TENANT);
        assertThat(result.propertyConflicts()).hasSize(1);
    }

    @Test
    void supersede_marksTargetSuperseded() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        String b = store.addNode(nodeInput("Bob"), TENANT);
        store.supersede(a, b, "replaced", TENANT);

        SupersessionStatus status = store.getSupersessionStatus(a, TENANT);
        assertThat(status.superseded()).isTrue();
        assertThat(status.supersedingId()).isEqualTo(b);
        assertThat(status.reason()).isEqualTo("replaced");
    }

    @Test
    void reinstate_clearsSupersession() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        String b = store.addNode(nodeInput("Bob"), TENANT);
        store.supersede(a, b, "replaced", TENANT);
        store.reinstate(a, TENANT);

        SupersessionStatus status = store.getSupersessionStatus(a, TENANT);
        assertThat(status.superseded()).isFalse();
        assertThat(status.wasReinstated()).isTrue();
    }

    @Test
    void supersessionStatus_notSuperseded() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        SupersessionStatus status = store.getSupersessionStatus(a, TENANT);
        assertThat(status.superseded()).isFalse();
    }

    @Test
    void supersede_notFoundThrows() {
        assertThatThrownBy(() -> store.supersede("nonexistent", "x", "reason", TENANT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eraseNode_removesNodeAndEdgesAndAliases() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        String b = store.addNode(nodeInput("Bob"), TENANT);
        store.addEdge(edgeInput(a, b, "works-at"), TENANT);
        store.addAlias(a, "Dr. Smith", TENANT);

        int count = store.eraseNode(a, TENANT);
        assertThat(count).isGreaterThan(1);
        assertThat(store.getNode(a, TENANT)).isNull();
        assertThat(store.neighbors(b, TENANT)).isEmpty();
        assertThat(store.resolveNode("Dr. Smith", null, TENANT)).isNull();
    }

    @Test
    void eraseNode_removesNodeRefs() {
        String a = store.addNode(new NodeInput("Alice", subgraphId,
            ConfidenceOrigin.STATED, null, "test", null,
            Set.of(new NodeRef("memory", "m-1", null)),
            null, null, null, null, null, null), TENANT);
        store.eraseNode(a, TENANT);
        assertThat(store.getNode(a, TENANT)).isNull();
    }

    @Test
    void eraseNode_returnsDeletedCount() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        int count = store.eraseNode(a, TENANT);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void eraseSubgraph_removesAllNodesAndEdges() {
        String sg = store.createSubgraph(
            new SubgraphInput("People", SubgraphType.PERSON, null), TENANT);
        store.addNode(nodeInput("Alice", sg), TENANT);
        store.addNode(nodeInput("Bob", sg), TENANT);

        int count = store.eraseSubgraph(sg, TENANT);
        assertThat(count).isGreaterThanOrEqualTo(3);
        assertThat(store.getSubgraph(sg, TENANT)).isNull();
        assertThat(store.nodesIn(sg, TENANT)).isEmpty();
    }

    @Test
    void eraseEntity_findsByNameOrAlias() {
        String a = store.addNode(nodeInput("Alice"), TENANT);
        store.addAlias(a, "Dr. Smith", TENANT);

        int count = store.eraseEntity("Dr. Smith", TENANT);
        assertThat(count).isGreaterThan(0);
        assertThat(store.getNode(a, TENANT)).isNull();
    }

    @Test
    void eraseEntityAcrossTenants_erasesInAllTenants() {
        String sg2 = store.createSubgraph(
            new SubgraphInput("Test", SubgraphType.GENERAL, null), TENANT_2);
        store.addNode(nodeInput("Alice"), TENANT);
        store.addNode(nodeInput("Alice", sg2), TENANT_2);

        int count = store.eraseEntityAcrossTenants("Alice", Set.of(TENANT, TENANT_2));
        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    @Test
    void eraseNode_nonExistent_returnsZero() {
        assertThat(store.eraseNode("nonexistent", TENANT)).isEqualTo(0);
    }

    @Test
    void capabilities_returnsNonEmpty() {
        assertThat(store.capabilities()).isNotEmpty();
    }

    @Test
    void requireCapability_supportedDoesNotThrow() {
        store.requireCapability(MindMapCapability.TRAVERSAL);
    }
}
