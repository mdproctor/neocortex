package io.casehub.neocortex.mindmap.runtime;

import io.casehub.neocortex.mindmap.*;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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
            new SubgraphInput("Test", SubgraphType.GENERAL, null), "t1");
    }

    @Test
    void getNode_freshNode_noDecay() {
        String id = decorator.addNode(new NodeInput("Alice", subgraphId,
            ConfidenceOrigin.STATED, null, "test",
            null, null, null, null, null, null, null, null), "t1");

        MindMapNode node = decorator.getNode(id, "t1");
        assertThat(node.confidence()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void getNode_appliesDecay() {
        String id = delegate.addNode(new NodeInput("Alice", subgraphId,
            ConfidenceOrigin.STATED, 1.0, "test",
            null, null, null, null, null, null, null, null), "t1");

        MindMapNode raw = delegate.getNode(id, "t1");
        delegate.updateNode(id, new NodeUpdate(null, null, null,
            Instant.now().minus(Duration.ofDays(180)),
            null, null, null, null, null, null, null, null, null, null, null), "t1");

        MindMapNode decayed = decorator.getNode(id, "t1");
        assertThat(decayed.confidence()).isCloseTo(0.5, within(0.05));
    }

    @Test
    void search_appliesMinConfidenceAfterDecay() {
        String fresh = delegate.addNode(new NodeInput("Fresh", subgraphId,
            ConfidenceOrigin.STATED, 1.0, "test",
            null, null, null, null, null, null, null, null), "t1");

        String stale = delegate.addNode(new NodeInput("Stale", subgraphId,
            ConfidenceOrigin.STATED, 1.0, "test",
            null, null, null, null, null, null, null, null), "t1");

        delegate.updateNode(stale, new NodeUpdate(null, null, null,
            Instant.now().minus(Duration.ofDays(360)),
            null, null, null, null, null, null, null, null, null, null, null), "t1");

        var results = decorator.search(new MindMapQuery("t1", null, null,
            null, null, 0.4, null, false, 10));

        assertThat(results).anyMatch(n -> n.name().equals("Fresh"));
        assertThat(results).noneMatch(n -> n.name().equals("Stale"));
    }
}
