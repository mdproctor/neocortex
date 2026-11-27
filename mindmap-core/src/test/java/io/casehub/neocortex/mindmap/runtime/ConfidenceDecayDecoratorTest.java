package io.casehub.neocortex.mindmap.runtime;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.mindmap.EdgeInput;
import io.casehub.neocortex.mindmap.MindMapEdge;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapVocabulary;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ConfidenceDecayDecoratorTest {

    private InMemoryMindMapStore delegate;
    private ConfidenceDecayDecorator decorator;
    private String subgraphId;

    @BeforeEach
    void setUp() {
        delegate = new InMemoryMindMapStore();
        decorator = new ConfidenceDecayDecorator(delegate, 180.0);

        subgraphId = decorator.createSubgraph(
            new SubgraphInput("Test", SubgraphTypes.GENERAL, null), "t1");
    }

    @Test
    void getNode_freshNode_noDecay() {
        String id = decorator.addNode(new NodeInput("Alice", subgraphId,
            null, "test",
            null, null, null, null, null, null, null, null), "t1");

        MindMapNode node = decorator.getNode(id, "t1");
        assertThat(node.confidence().value()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void getNode_appliesDecay() {
        String id = delegate.addNode(new NodeInput("Alice", subgraphId,
            Confidence.stated(1.0, Instant.now().minus(Duration.ofDays(180))), "test",
            null, null, null, null, null, null, null, null), "t1");

        MindMapNode decayed = decorator.getNode(id, "t1");
        assertThat(decayed.confidence().value()).isCloseTo(0.5, within(0.05));
    }

    @Test
    void search_appliesMinConfidenceAfterDecay() {
        String fresh = delegate.addNode(new NodeInput("Fresh", subgraphId,
            Confidence.stated(1.0, Instant.now()), "test",
            null, null, null, null, null, null, null, null), "t1");

        String stale = delegate.addNode(new NodeInput("Stale", subgraphId,
            Confidence.stated(1.0, Instant.now().minus(Duration.ofDays(360))), "test",
            null, null, null, null, null, null, null, null), "t1");

        var results = decorator.search(MindMapQuery.of("t1", 10).withMinConfidence(0.4));

        assertThat(results).anyMatch(n -> n.name().equals("Fresh"));
        assertThat(results).noneMatch(n -> n.name().equals("Stale"));
    }

    @Test
    void edges_usesPerTypeHalfLifeFromVocabulary() {
        decorator.registerVocabulary(MindMapVocabulary.builder()
                                                      .edgeType("trusts", 30.0, "trust")
                                                      .build());

        String a = delegate.addNode(new NodeInput("Alice", subgraphId,
                                                  null, "test", null, null, null, null, null, null, null, null), "t1");
        String b = delegate.addNode(new NodeInput("Bob", subgraphId,
                                                  null, "test", null, null, null, null, null, null, null, null), "t1");

        delegate.addEdge(EdgeInput.of(a, b, "trusts")
                                  .withConfidence(Confidence.stated(1.0, Instant.now().minus(Duration.ofDays(30)))), "t1");

        List<MindMapEdge> edges = decorator.neighbors(a, "t1");
        // 30-day half-life, 30 days elapsed → ~0.5
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).confidence().value()).isCloseTo(0.5, within(0.05));
    }

    @Test
    void edges_aliasResolvesToPerTypeHalfLife() {
        decorator.registerVocabulary(MindMapVocabulary.builder()
                                                      .edgeType("trusts", 30.0, "trust")
                                                      .build());

        String a = delegate.addNode(new NodeInput("Alice", subgraphId,
                                                  null, "test", null, null, null, null, null, null, null, null), "t1");
        String b = delegate.addNode(new NodeInput("Bob", subgraphId,
                                                  null, "test", null, null, null, null, null, null, null, null), "t1");

        // Edge uses alias "trust" instead of canonical "trusts"
        delegate.addEdge(EdgeInput.of(a, b, "trust")
                                  .withConfidence(Confidence.stated(1.0, Instant.now().minus(Duration.ofDays(30)))), "t1");

        List<MindMapEdge> edges = decorator.neighbors(a, "t1");
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).confidence().value()).isCloseTo(0.5, within(0.05));
    }

    @Test
    void edges_unregisteredTypeUsesDefaultHalfLife() {
        decorator.registerVocabulary(MindMapVocabulary.builder()
                                                      .edgeType("trusts", 30.0, "trust")
                                                      .build());

        String a = delegate.addNode(new NodeInput("Alice", subgraphId,
                                                  null, "test", null, null, null, null, null, null, null, null), "t1");
        String b = delegate.addNode(new NodeInput("Bob", subgraphId,
                                                  null, "test", null, null, null, null, null, null, null, null), "t1");

        // "knows" is not in vocabulary — should use default 180-day half-life
        delegate.addEdge(EdgeInput.of(a, b, "knows")
                                  .withConfidence(Confidence.stated(1.0, Instant.now().minus(Duration.ofDays(30)))), "t1");

        List<MindMapEdge> edges = decorator.neighbors(a, "t1");
        assertThat(edges).hasSize(1);
        // 180-day half-life, 30 days elapsed → ~0.891
        assertThat(edges.get(0).confidence().value()).isCloseTo(0.891, within(0.02));
    }

    @Test
    void edges_vocabularyWithNullHalfLifeUsesDefault() {
        decorator.registerVocabulary(MindMapVocabulary.builder()
                                                      .edgeType("knows")
                                                      .build());

        String a = delegate.addNode(new NodeInput("Alice", subgraphId,
                                                  null, "test", null, null, null, null, null, null, null, null), "t1");
        String b = delegate.addNode(new NodeInput("Bob", subgraphId,
                                                  null, "test", null, null, null, null, null, null, null, null), "t1");

        delegate.addEdge(EdgeInput.of(a, b, "knows")
                                  .withConfidence(Confidence.stated(1.0, Instant.now().minus(Duration.ofDays(30)))), "t1");

        List<MindMapEdge> edges = decorator.neighbors(a, "t1");
        assertThat(edges).hasSize(1);
        // null half-life in vocabulary → default 180 days → ~0.891
        assertThat(edges.get(0).confidence().value()).isCloseTo(0.891, within(0.02));
    }


}
