package io.casehub.neocortex.mindmap.intelligence;

import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.MindMapSubgraph;
import io.casehub.neocortex.mindmap.SubgraphInput;

public final class SubgraphUtils {

    private SubgraphUtils() {}

    public static String ensureSubgraph(MindMapStore store, String type, String tenantId) {
        return ensureSubgraph(store, type, type, tenantId);
    }

    public static String ensureSubgraph(MindMapStore store, String name, String type,
                                         String tenantId) {
        return store.listSubgraphs(tenantId).stream()
            .filter(s -> type.equals(s.type()))
            .map(MindMapSubgraph::id)
            .findFirst()
            .orElseGet(() -> store.createSubgraph(
                new SubgraphInput(name, type, null), tenantId));
    }
}
