package io.casehub.neocortex.mindmap.runtime;

import io.casehub.neocortex.mindmap.*;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VocabularyNormalizationDecoratorTest {

    private InMemoryMindMapStore delegate;
    private VocabularyNormalizationDecorator decorator;
    private String subgraphId;

    @BeforeEach
    void setUp() {
        delegate = new InMemoryMindMapStore();
        decorator = new VocabularyNormalizationDecorator(delegate);

        decorator.registerVocabulary(MindMapVocabulary.builder()
            .edgeType("works-at", "employed-by", "job-at")
            .build());

        subgraphId = decorator.createSubgraph(
            new SubgraphInput("Test", SubgraphType.GENERAL, null), "t1");
    }

    @Test
    void addEdge_normalizesAliasToCanonical() {
        String a = decorator.addNode(node("Alice"), "t1");
        String b = decorator.addNode(node("Acme"), "t1");

        String edgeId = decorator.addEdge(new EdgeInput(a, b, "employed-by",
            ConfidenceOrigin.STATED, null, "test",
            null, null, null, null, null, null), "t1");

        MindMapEdge edge = decorator.getEdge(edgeId, "t1");
        assertThat(edge.edgeType()).isEqualTo("works-at");
        assertThat(edge.tier()).isEqualTo(ValidationTier.REGISTERED);
    }

    @Test
    void addEdge_unregisteredType_passesThrough() {
        String a = decorator.addNode(node("Alice"), "t1");
        String b = decorator.addNode(node("Bob"), "t1");

        String edgeId = decorator.addEdge(new EdgeInput(a, b, "friend-of",
            ConfidenceOrigin.STATED, null, "test",
            null, null, null, null, null, null), "t1");

        MindMapEdge edge = decorator.getEdge(edgeId, "t1");
        assertThat(edge.edgeType()).isEqualTo("friend-of");
        assertThat(edge.tier()).isEqualTo(ValidationTier.UNVALIDATED);
    }

    @Test
    void addEdge_alreadyCanonical_noChange() {
        String a = decorator.addNode(node("Alice"), "t1");
        String b = decorator.addNode(node("Acme"), "t1");

        String edgeId = decorator.addEdge(new EdgeInput(a, b, "works-at",
            ConfidenceOrigin.STATED, null, "test",
            null, null, null, null, null, null), "t1");

        MindMapEdge edge = decorator.getEdge(edgeId, "t1");
        assertThat(edge.edgeType()).isEqualTo("works-at");
        assertThat(edge.tier()).isEqualTo(ValidationTier.REGISTERED);
    }

    private NodeInput node(String name) {
        return new NodeInput(name, subgraphId, ConfidenceOrigin.STATED, null,
            "test", null, null, null, null, null, null, null, null);
    }
}
