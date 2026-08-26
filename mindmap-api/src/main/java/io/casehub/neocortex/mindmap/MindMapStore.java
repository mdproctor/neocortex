package io.casehub.neocortex.mindmap;

import java.util.List;
import java.util.Set;

public interface MindMapStore {

    void registerVocabulary(MindMapVocabulary vocabulary);

    String addNode(NodeInput input, String tenantId);

    MindMapNode getNode(String nodeId, String tenantId);

    void updateNode(String nodeId, NodeUpdate update, String tenantId);

    String addEdge(EdgeInput input, String tenantId);

    MindMapEdge getEdge(String edgeId, String tenantId);

    void removeEdge(String edgeId, String tenantId);

    void addAlias(String nodeId, String alias, String tenantId);

    void removeAlias(String nodeId, String alias, String tenantId);

    MindMapNode resolveNode(String nameOrAlias, String subgraphId, String tenantId);

    MergeResult mergeNodes(String keepNodeId, String removeNodeId, String tenantId);

    String createSubgraph(SubgraphInput input, String tenantId);

    MindMapSubgraph getSubgraph(String subgraphId, String tenantId);

    void updateSubgraph(String subgraphId, String rootNodeId, String tenantId);

    List<MindMapNode> nodesIn(String subgraphId, String tenantId);

    List<MindMapEdge> bridgeEdges(String subgraphId, String tenantId);

    List<MindMapEdge> neighbors(String nodeId, String tenantId);

    List<MindMapEdge> neighbors(String nodeId, String edgeType, String tenantId);

    List<MindMapNode> search(MindMapQuery query);

    void supersede(String targetId, String supersedingId, String reason, String tenantId);

    void reinstate(String targetId, String tenantId);

    SupersessionStatus getSupersessionStatus(String targetId, String tenantId);

    int eraseNode(String nodeId, String tenantId);

    int eraseSubgraph(String subgraphId, String tenantId);

    int eraseEntity(String entityName, String tenantId);

    int eraseEntityAcrossTenants(String entityName, Set<String> tenantIds);

    Set<MindMapCapability> capabilities();

    default void requireCapability(MindMapCapability capability) {
        if (!capabilities().contains(capability))
            throw new MindMapCapabilityException(capability);
    }
}
