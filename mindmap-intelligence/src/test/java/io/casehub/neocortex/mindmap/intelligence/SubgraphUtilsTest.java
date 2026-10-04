package io.casehub.neocortex.mindmap.intelligence;

import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubgraphUtilsTest {

    private MindMapStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryMindMapStore();
    }

    @Test
    void ensureSubgraphCreatesWhenAbsent() {
        String id = SubgraphUtils.ensureSubgraph(store, SubgraphTypes.PLACE, "t1");
        assertThat(id).isNotNull();
        assertThat(store.listSubgraphs("t1")).hasSize(1);
    }

    @Test
    void ensureSubgraphReusesExisting() {
        String first = SubgraphUtils.ensureSubgraph(store, SubgraphTypes.PLACE, "t1");
        String second = SubgraphUtils.ensureSubgraph(store, SubgraphTypes.PLACE, "t1");
        assertThat(second).isEqualTo(first);
        assertThat(store.listSubgraphs("t1")).hasSize(1);
    }

    @Test
    void ensureSubgraphDifferentTypesCreatesSeparate() {
        String place = SubgraphUtils.ensureSubgraph(store, SubgraphTypes.PLACE, "t1");
        String activity = SubgraphUtils.ensureSubgraph(store, SubgraphTypes.ACTIVITY, "t1");
        assertThat(place).isNotEqualTo(activity);
        assertThat(store.listSubgraphs("t1")).hasSize(2);
    }

    @Test
    void ensureSubgraphWithExplicitName() {
        String id = SubgraphUtils.ensureSubgraph(store, "Cognitive", SubgraphTypes.COGNITIVE, "t1");
        assertThat(id).isNotNull();
        var sg = store.listSubgraphs("t1").get(0);
        assertThat(sg.type()).isEqualTo(SubgraphTypes.COGNITIVE);
    }
}
