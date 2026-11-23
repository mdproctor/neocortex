package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.cognitive.ConfidenceOrigin;
import io.casehub.neocortex.inference.DenseEmbedder;
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
import java.util.Map;
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
    void parseRelationships_standardJsonOrder() {
        String json = """
            {"relationships": [{"source": "Alice", "target": "Bob", "type": "collaborated-with", "confidence": "INFERRED"}]}""";

        List<ParsedRelationship> result = EdgeInferencePhase.parseRelationships(json);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().source()).isEqualTo("Alice");
        assertThat(result.getFirst().target()).isEqualTo("Bob");
        assertThat(result.getFirst().type()).isEqualTo("collaborated-with");
        assertThat(result.getFirst().origin()).isEqualTo(ConfidenceOrigin.INFERRED);
    }

    @Test
    void parseRelationships_differentKeyOrder_noMatch() {
        String json = """
            {"relationships": [{"type": "knows", "source": "Alice", "target": "Bob"}]}""";

        List<ParsedRelationship> result = EdgeInferencePhase.parseRelationships(json);

        assertThat(result).isEmpty();
    }

    @Test
    void parseRelationships_escapedQuotesInEntityName_noMatch() {
        String json = """
            {"relationships": [{"source": "O\\"Brien", "target": "Bob", "type": "knows"}]}""";

        List<ParsedRelationship> result = EdgeInferencePhase.parseRelationships(json);

        assertThat(result).isEmpty();
    }

    @Test
    void parseRelationships_emptyRelationshipsArray() {
        String json = """
            {"relationships": []}""";

        List<ParsedRelationship> result = EdgeInferencePhase.parseRelationships(json);

        assertThat(result).isEmpty();
    }

    @Test
    void parseRelationships_truncatedJson() {
        String json = """
            {"relationships": [{"source": "Alice", "targ""";

        List<ParsedRelationship> result = EdgeInferencePhase.parseRelationships(json);

        assertThat(result).isEmpty();
    }

    @Test
    void parseRelationships_multipleRelationships() {
        String json = """
            {"relationships": [
                {"source": "Alice", "target": "Bob", "type": "collaborated-with", "confidence": "INFERRED"},
                {"source": "Carol", "target": "Dave", "type": "taught-by", "confidence": "INFERRED"}
            ]}""";

        List<ParsedRelationship> result = EdgeInferencePhase.parseRelationships(json);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).source()).isEqualTo("Alice");
        assertThat(result.get(0).type()).isEqualTo("collaborated-with");
        assertThat(result.get(1).source()).isEqualTo("Carol");
        assertThat(result.get(1).type()).isEqualTo("taught-by");
    }

    @Test
    void parseRelationships_mixedValidAndReordered_extractsOnlyValid() {
        String json = """
            {"relationships": [
                {"source": "Alice", "target": "Bob", "type": "knows"},
                {"type": "taught-by", "source": "Carol", "target": "Dave"}
            ]}""";

        List<ParsedRelationship> result = EdgeInferencePhase.parseRelationships(json);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().source()).isEqualTo("Alice");
    }

    @Test
    void runMergesCoOccurrenceAndEmbeddingCandidates() {
        var embedder = new StubDenseEmbedder(Map.of(
            "Alice", new float[]{1.0f, 0.0f, 0.0f, 0.0f},
            "Bob", new float[]{0.5f, 0.5f, 0.0f, 0.0f},
            "Cubism", new float[]{0.0f, 0.0f, 1.0f, 0.0f},
            "Surrealism", new float[]{0.0f, 0.0f, 0.95f, 0.1f}));

        var phase = new TestableEdgeInferencePhase(mindMapStore, memoryStore,
            1, 30, embedder, 0.7,
            List.of(
                new ParsedRelationship("Alice", "Bob", "collaborated-with", ConfidenceOrigin.INFERRED),
                new ParsedRelationship("Cubism", "Surrealism", "influenced-by", ConfidenceOrigin.INFERRED)));

        String personSg = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.PERSON, SubgraphTypes.PERSON, null), TENANT);
        mindMapStore.addNode(NodeInput.of("Alice", personSg), TENANT);
        mindMapStore.addNode(NodeInput.of("Bob", personSg), TENANT);

        String conceptSg = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.CONCEPT, SubgraphTypes.CONCEPT, null), TENANT);
        mindMapStore.addNode(NodeInput.of("Cubism", conceptSg), TENANT);
        mindMapStore.addNode(NodeInput.of("Surrealism", conceptSg), TENANT);

        storeExperienceMemory("Alice", "Bob", "Alice and Bob collaborated on research");

        phase.run(TENANT, List.of());

        var alice = mindMapStore.resolveNode("Alice", null, TENANT);
        var aliceEdges = mindMapStore.neighbors(alice.id(), TENANT);
        assertThat(aliceEdges).hasSize(1);
        assertThat(aliceEdges.getFirst().edgeType()).isEqualTo("collaborated-with");

        var cubism = mindMapStore.resolveNode("Cubism", null, TENANT);
        var cubismEdges = mindMapStore.neighbors(cubism.id(), TENANT);
        assertThat(cubismEdges).hasSize(1);
        assertThat(cubismEdges.getFirst().edgeType()).isEqualTo("influenced-by");
    }

    @Test
    void runRespectsMaxCandidatesCapOnMergedList() {
        var embedder = new StubDenseEmbedder(Map.of(
            "Alice", new float[]{1.0f, 0.0f, 0.0f, 0.0f},
            "Bob", new float[]{0.5f, 0.5f, 0.0f, 0.0f},
            "Carol", new float[]{0.0f, 0.0f, 1.0f, 0.0f},
            "Dave", new float[]{0.0f, 0.0f, 0.95f, 0.1f}));

        var phase = new TestableEdgeInferencePhase(mindMapStore, memoryStore,
            1, 2, embedder, 0.7,
            List.of(
                new ParsedRelationship("Alice", "Bob", "collaborated-with", ConfidenceOrigin.INFERRED)));

        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.PERSON, SubgraphTypes.PERSON, null), TENANT);
        mindMapStore.addNode(NodeInput.of("Alice", sgId), TENANT);
        mindMapStore.addNode(NodeInput.of("Bob", sgId), TENANT);
        mindMapStore.addNode(NodeInput.of("Carol", sgId), TENANT);
        mindMapStore.addNode(NodeInput.of("Dave", sgId), TENANT);

        storeExperienceMemory("Alice", "Bob", "Alice and Bob collaborated on research");
        storeExperienceMemory("Alice", "Carol", "Alice and Carol shared notes");

        phase.run(TENANT, List.of());

        var alice = mindMapStore.resolveNode("Alice", null, TENANT);
        var aliceEdges = mindMapStore.neighbors(alice.id(), TENANT);
        assertThat(aliceEdges).hasSize(1);
        assertThat(aliceEdges.getFirst().edgeType()).isEqualTo("collaborated-with");

        var carol = mindMapStore.resolveNode("Carol", null, TENANT);
        var carolEdges = mindMapStore.neighbors(carol.id(), TENANT);
        assertThat(carolEdges)
            .as("embedding candidates should be excluded when co-occurrence fills maxCandidates")
            .hasSizeLessThanOrEqualTo(1);
    }

    private EdgeInferencePhase embeddingPhase(int degreeThreshold, int maxCandidates,
                                               DenseEmbedder embedder, double threshold) {
        return new EdgeInferencePhase(mindMapStore, memoryStore,
            degreeThreshold, maxCandidates, embedder, true, threshold);
    }

    @Test
    void buildEmbeddingCandidates_findsSimilarNodes() {
        var embedder = new StubDenseEmbedder(Map.of(
            "Cubism", new float[]{1.0f, 0.0f, 0.0f, 0.0f},
            "Surrealism", new float[]{0.95f, 0.1f, 0.0f, 0.0f}));
        var phase = embeddingPhase(1, 30, embedder, 0.7);

        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.CONCEPT, SubgraphTypes.CONCEPT, null), TENANT);
        mindMapStore.addNode(NodeInput.of("Cubism", sgId), TENANT);
        mindMapStore.addNode(NodeInput.of("Surrealism", sgId), TENANT);

        List<MindMapNode> underConnected = phase.findUnderConnectedNodes(TENANT);
        List<EdgeInferencePhase.CandidatePair> candidates =
            phase.buildEmbeddingCandidates(underConnected, Set.of(), TENANT);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().coOccurrenceCount()).isZero();
        assertThat(candidates.getFirst().sharedContexts().getFirst()).startsWith("Semantic similarity:");
    }

    @Test
    void buildEmbeddingCandidates_excludesExistingEdges() {
        var embedder = new StubDenseEmbedder(Map.of(
            "Cubism", new float[]{1.0f, 0.0f, 0.0f, 0.0f},
            "Surrealism", new float[]{0.95f, 0.1f, 0.0f, 0.0f}));
        var phase = embeddingPhase(1, 30, embedder, 0.7);

        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.CONCEPT, SubgraphTypes.CONCEPT, null), TENANT);
        String cubismId = mindMapStore.addNode(NodeInput.of("Cubism", sgId), TENANT);
        String surrealismId = mindMapStore.addNode(NodeInput.of("Surrealism", sgId), TENANT);
        mindMapStore.addEdge(EdgeInput.of(cubismId, surrealismId, "influenced-by"), TENANT);

        List<MindMapNode> underConnected = phase.findUnderConnectedNodes(TENANT);
        List<EdgeInferencePhase.CandidatePair> candidates =
            phase.buildEmbeddingCandidates(underConnected, Set.of(), TENANT);

        assertThat(candidates).isEmpty();
    }

    @Test
    void buildEmbeddingCandidates_excludesCoOccurrencePairs() {
        var embedder = new StubDenseEmbedder(Map.of(
            "Cubism", new float[]{1.0f, 0.0f, 0.0f, 0.0f},
            "Surrealism", new float[]{0.95f, 0.1f, 0.0f, 0.0f}));
        var phase = embeddingPhase(1, 30, embedder, 0.7);

        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.CONCEPT, SubgraphTypes.CONCEPT, null), TENANT);
        String cubismId = mindMapStore.addNode(NodeInput.of("Cubism", sgId), TENANT);
        String surrealismId = mindMapStore.addNode(NodeInput.of("Surrealism", sgId), TENANT);

        Set<String> existingPairs = Set.of(EdgeInferencePhase.pairKey(cubismId, surrealismId));
        List<MindMapNode> underConnected = phase.findUnderConnectedNodes(TENANT);
        List<EdgeInferencePhase.CandidatePair> candidates =
            phase.buildEmbeddingCandidates(underConnected, existingPairs, TENANT);

        assertThat(candidates).isEmpty();
    }

    @Test
    void buildEmbeddingCandidates_belowThreshold_excluded() {
        var embedder = new StubDenseEmbedder(Map.of(
            "Cubism", new float[]{1.0f, 0.0f, 0.0f, 0.0f},
            "Astronomy", new float[]{0.0f, 1.0f, 0.0f, 0.0f}));
        var phase = embeddingPhase(1, 30, embedder, 0.7);

        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.CONCEPT, SubgraphTypes.CONCEPT, null), TENANT);
        mindMapStore.addNode(NodeInput.of("Cubism", sgId), TENANT);
        mindMapStore.addNode(NodeInput.of("Astronomy", sgId), TENANT);

        List<MindMapNode> underConnected = phase.findUnderConnectedNodes(TENANT);
        List<EdgeInferencePhase.CandidatePair> candidates =
            phase.buildEmbeddingCandidates(underConnected, Set.of(), TENANT);

        assertThat(candidates).isEmpty();
    }

    @Test
    void buildEmbeddingCandidates_noEmbedder_returnsEmpty() {
        var phase = embeddingPhase(1, 30, null, 0.7);

        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.CONCEPT, SubgraphTypes.CONCEPT, null), TENANT);
        mindMapStore.addNode(NodeInput.of("Cubism", sgId), TENANT);
        mindMapStore.addNode(NodeInput.of("Surrealism", sgId), TENANT);

        List<MindMapNode> underConnected = phase.findUnderConnectedNodes(TENANT);
        List<EdgeInferencePhase.CandidatePair> candidates =
            phase.buildEmbeddingCandidates(underConnected, Set.of(), TENANT);

        assertThat(candidates).isEmpty();
    }

    @Test
    void buildEmbeddingCandidates_disabled_returnsEmpty() {
        var embedder = new StubDenseEmbedder(Map.of(
            "Cubism", new float[]{1.0f, 0.0f, 0.0f, 0.0f},
            "Surrealism", new float[]{0.95f, 0.1f, 0.0f, 0.0f}));
        var phase = new EdgeInferencePhase(mindMapStore, memoryStore,
            1, 30, embedder, false, 0.7);

        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.CONCEPT, SubgraphTypes.CONCEPT, null), TENANT);
        mindMapStore.addNode(NodeInput.of("Cubism", sgId), TENANT);
        mindMapStore.addNode(NodeInput.of("Surrealism", sgId), TENANT);

        List<MindMapNode> underConnected = phase.findUnderConnectedNodes(TENANT);
        List<EdgeInferencePhase.CandidatePair> candidates =
            phase.buildEmbeddingCandidates(underConnected, Set.of(), TENANT);

        assertThat(candidates).isEmpty();
    }

    @Test
    void buildNodeText_includesNameTraitsAndProperties() {
        String sgId = mindMapStore.createSubgraph(
            new SubgraphInput(SubgraphTypes.CONCEPT, SubgraphTypes.CONCEPT, null), TENANT);
        mindMapStore.addNode(
            new NodeInput("Cubism", sgId, null, null,
                Set.of("ArtMovement"), Set.of(), null, null, null, null, null,
                Map.of("era", "20th century")), TENANT);

        MindMapNode node = mindMapStore.resolveNode("Cubism", null, TENANT);
        String text = EdgeInferencePhase.buildNodeText(node);

        assertThat(text).contains("Cubism");
        assertThat(text).contains("ArtMovement");
        assertThat(text).contains("20th century");
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

        TestableEdgeInferencePhase(MindMapStore mindMapStore, CaseMemoryStore memoryStore,
                                   int degreeThreshold, int maxCandidates,
                                   DenseEmbedder denseEmbedder, double embeddingSimilarityThreshold,
                                   List<ParsedRelationship> fixedResponse) {
            super(mindMapStore, memoryStore, degreeThreshold, maxCandidates,
                  denseEmbedder, true, embeddingSimilarityThreshold);
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

            List<CandidatePair> coOccurrenceCandidates = buildCandidates(underConnected, tenantId);

            java.util.Set<String> existingPairKeys = new java.util.HashSet<>();
            for (CandidatePair cp : coOccurrenceCandidates) {
                existingPairKeys.add(pairKey(cp.nodeIdA(), cp.nodeIdB()));
            }

            int remaining = maxCandidates - coOccurrenceCandidates.size();
            List<CandidatePair> embeddingCandidates = remaining > 0
                ? buildEmbeddingCandidates(underConnected, existingPairKeys, tenantId)
                : List.of();

            var candidates = new java.util.ArrayList<>(coOccurrenceCandidates);
            if (!embeddingCandidates.isEmpty()) {
                candidates.addAll(embeddingCandidates.subList(0, Math.min(remaining, embeddingCandidates.size())));
            }

            if (candidates.isEmpty()) return;

            List<ParsedRelationship> inferred = invokeEdgeInference(candidates);
            if (!inferred.isEmpty()) {
                persistEdges(inferred, tenantId);
            }
        }
    }

    static class StubDenseEmbedder implements DenseEmbedder {
        private final Map<String, float[]> vectors;

        StubDenseEmbedder(Map<String, float[]> vectors) {
            this.vectors = new HashMap<>(vectors);
        }

        @Override
        public float[] embed(String text) {
            for (var entry : vectors.entrySet()) {
                if (text.startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return new float[]{0, 0, 0, 0};
        }

        @Override
        public int dimension() { return 4; }
    }
}
