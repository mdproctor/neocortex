package io.casehub.neocortex.mindmap.runtime;

import io.casehub.neocortex.mindmap.*;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;

@DefaultBean
@ApplicationScoped
public class NoOpMindMapStore implements MindMapStore {

    @Override
    public void registerVocabulary(MindMapVocabulary vocabulary) {}

    @Override
    public String addNode(NodeInput input, String tenantId) {
        return "";
    }

    @Override
    public MindMapNode getNode(String nodeId, String tenantId) {
        return null;
    }

    @Override
    public void updateNode(String nodeId, NodeUpdate update, String tenantId) {}

    @Override
    public String addEdge(EdgeInput input, String tenantId) {
        return "";
    }

    @Override
    public MindMapEdge getEdge(String edgeId, String tenantId) {
        return null;
    }

    @Override
    public void removeEdge(String edgeId, String tenantId) {}

    @Override
    public void addAlias(String nodeId, String alias, String tenantId) {}

    @Override
    public void removeAlias(String nodeId, String alias, String tenantId) {}

    @Override
    public MindMapNode resolveNode(String nameOrAlias, String subgraphId, String tenantId) {
        return null;
    }

    @Override
    public MergeResult mergeNodes(String keepNodeId, String removeNodeId, String tenantId) {
        return new MergeResult("", 0, 0, 0, Set.of(), List.of());
    }

    @Override
    public String createSubgraph(SubgraphInput input, String tenantId) {
        return "";
    }

    @Override
    public MindMapSubgraph getSubgraph(String subgraphId, String tenantId) {
        return null;
    }

    @Override
    public void updateSubgraph(String subgraphId, String rootNodeId, String tenantId) {}

    @Override
    public List<MindMapNode> nodesIn(String subgraphId, String tenantId) {
        return List.of();
    }

    @Override
    public List<MindMapEdge> bridgeEdges(String subgraphId, String tenantId) {
        return List.of();
    }

    @Override
    public List<MindMapEdge> neighbors(String nodeId, String tenantId) {
        return List.of();
    }

    @Override
    public List<MindMapEdge> neighbors(String nodeId, String edgeType, String tenantId) {
        return List.of();
    }

    @Override
    public List<MindMapNode> search(MindMapQuery query) {
        return List.of();
    }

    @Override
    public void supersede(String targetId, String supersedingId, String reason, String tenantId) {}

    @Override
    public void reinstate(String targetId, String tenantId) {}

    @Override
    public SupersessionStatus getSupersessionStatus(String targetId, String tenantId) {
        return SupersessionStatus.NOT_SUPERSEDED;
    }

    @Override
    public int eraseNode(String nodeId, String tenantId) {
        return 0;
    }

    @Override
    public int eraseSubgraph(String subgraphId, String tenantId) {
        return 0;
    }

    @Override
    public int eraseEntity(String entityName, String tenantId) {
        return 0;
    }

    @Override
    public int eraseEntityAcrossTenants(String entityName, Set<String> tenantIds) {
        return 0;
    }

    @Override
    public Set<MindMapCapability> capabilities() {
        return Set.of();
    }
}
