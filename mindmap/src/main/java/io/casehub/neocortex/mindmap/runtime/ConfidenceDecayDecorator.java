package io.casehub.neocortex.mindmap.runtime;

import io.casehub.neocortex.mindmap.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class ConfidenceDecayDecorator implements MindMapStore {

    private final MindMapStore delegate;
    private final double defaultHalfLifeDays;

    public ConfidenceDecayDecorator(MindMapStore delegate, double defaultHalfLifeDays) {
        this.delegate = delegate;
        this.defaultHalfLifeDays = defaultHalfLifeDays;
    }

    @Override
    public MindMapNode getNode(String nodeId, String tenantId) {
        MindMapNode node = delegate.getNode(nodeId, tenantId);
        if (node == null) return null;
        return withDecayedConfidence(node);
    }

    @Override
    public List<MindMapNode> nodesIn(String subgraphId, String tenantId) {
        return delegate.nodesIn(subgraphId, tenantId).stream()
            .map(this::withDecayedConfidence)
            .toList();
    }

    @Override
    public List<MindMapNode> search(MindMapQuery query) {
        List<MindMapNode> results = delegate.search(query).stream()
            .map(this::withDecayedConfidence)
            .collect(Collectors.toCollection(ArrayList::new));

        if (query.minConfidence() != null) {
            results.removeIf(n -> n.confidence() < query.minConfidence());
        }
        return results;
    }

    @Override
    public List<MindMapEdge> neighbors(String nodeId, String tenantId) {
        return delegate.neighbors(nodeId, tenantId).stream()
            .map(this::withDecayedConfidence)
            .toList();
    }

    @Override
    public List<MindMapEdge> neighbors(String nodeId, String edgeType, String tenantId) {
        return delegate.neighbors(nodeId, edgeType, tenantId).stream()
            .map(this::withDecayedConfidence)
            .toList();
    }

    @Override
    public List<MindMapEdge> bridgeEdges(String subgraphId, String tenantId) {
        return delegate.bridgeEdges(subgraphId, tenantId).stream()
            .map(this::withDecayedConfidence)
            .toList();
    }

    private MindMapNode withDecayedConfidence(MindMapNode node) {
        double decayed = applyDecay(node.confidence(), node.confirmedAt(), defaultHalfLifeDays);
        if (decayed == node.confidence()) return node;
        return new DecayedNode(node, decayed);
    }

    private MindMapEdge withDecayedConfidence(MindMapEdge edge) {
        double decayed = applyDecay(edge.confidence(), edge.updatedAt(), defaultHalfLifeDays);
        if (decayed == edge.confidence()) return edge;
        return new DecayedEdge(edge, decayed);
    }

    static double applyDecay(double confidence, Instant since, double halfLifeDays) {
        if (since == null) return confidence;
        double hoursSince = Duration.between(since, Instant.now()).toHours();
        if (hoursSince <= 0) return confidence;
        double halfLifeHours = halfLifeDays * 24.0;
        return confidence * Math.pow(2.0, -hoursSince / halfLifeHours);
    }

    // --- Delegation ---

    @Override public void registerVocabulary(MindMapVocabulary vocabulary) { delegate.registerVocabulary(vocabulary); }
    @Override public String addNode(NodeInput input, String tenantId) { return delegate.addNode(input, tenantId); }
    @Override public void updateNode(String nodeId, NodeUpdate update, String tenantId) { delegate.updateNode(nodeId, update, tenantId); }
    @Override public String addEdge(EdgeInput input, String tenantId) { return delegate.addEdge(input, tenantId); }
    @Override public MindMapEdge getEdge(String edgeId, String tenantId) { return delegate.getEdge(edgeId, tenantId); }
    @Override public void removeEdge(String edgeId, String tenantId) { delegate.removeEdge(edgeId, tenantId); }
    @Override public void addAlias(String nodeId, String alias, String tenantId) { delegate.addAlias(nodeId, alias, tenantId); }
    @Override public void removeAlias(String nodeId, String alias, String tenantId) { delegate.removeAlias(nodeId, alias, tenantId); }
    @Override public MindMapNode resolveNode(String nameOrAlias, String subgraphId, String tenantId) { return delegate.resolveNode(nameOrAlias, subgraphId, tenantId); }
    @Override public MergeResult mergeNodes(String keepNodeId, String removeNodeId, String tenantId) { return delegate.mergeNodes(keepNodeId, removeNodeId, tenantId); }
    @Override public String createSubgraph(SubgraphInput input, String tenantId) { return delegate.createSubgraph(input, tenantId); }
    @Override public MindMapSubgraph getSubgraph(String subgraphId, String tenantId) { return delegate.getSubgraph(subgraphId, tenantId); }
    @Override public void updateSubgraph(String subgraphId, String rootNodeId, String tenantId) { delegate.updateSubgraph(subgraphId, rootNodeId, tenantId); }
    @Override public void supersede(String targetId, String supersedingId, String reason, String tenantId) { delegate.supersede(targetId, supersedingId, reason, tenantId); }
    @Override public void reinstate(String targetId, String tenantId) { delegate.reinstate(targetId, tenantId); }
    @Override public SupersessionStatus getSupersessionStatus(String targetId, String tenantId) { return delegate.getSupersessionStatus(targetId, tenantId); }
    @Override public int eraseNode(String nodeId, String tenantId) { return delegate.eraseNode(nodeId, tenantId); }
    @Override public int eraseSubgraph(String subgraphId, String tenantId) { return delegate.eraseSubgraph(subgraphId, tenantId); }
    @Override public int eraseEntity(String entityName, String tenantId) { return delegate.eraseEntity(entityName, tenantId); }
    @Override public int eraseEntityAcrossTenants(String entityName, Set<String> tenantIds) { return delegate.eraseEntityAcrossTenants(entityName, tenantIds); }
    @Override public Set<MindMapCapability> capabilities() { return delegate.capabilities(); }

    // --- Wrapper types ---

    private record DecayedNode(MindMapNode delegate, double decayedConfidence) implements MindMapNode {
        @Override public String id() { return delegate.id(); }
        @Override public String name() { return delegate.name(); }
        @Override public String subgraphId() { return delegate.subgraphId(); }
        @Override public ConfidenceOrigin confidenceOrigin() { return delegate.confidenceOrigin(); }
        @Override public double confidence() { return decayedConfidence; }
        @Override public String provenance() { return delegate.provenance(); }
        @Override public Instant createdAt() { return delegate.createdAt(); }
        @Override public Instant updatedAt() { return delegate.updatedAt(); }
        @Override public Instant confirmedAt() { return delegate.confirmedAt(); }
        @Override public Instant validFrom() { return delegate.validFrom(); }
        @Override public Instant validUntil() { return delegate.validUntil(); }
        @Override public Set<String> traits() { return delegate.traits(); }
        @Override public Set<NodeRef> refs() { return delegate.refs(); }
        @Override public Double pleasure() { return delegate.pleasure(); }
        @Override public Double arousal() { return delegate.arousal(); }
        @Override public Double dominance() { return delegate.dominance(); }
        @Override public Optional<String> property(String key) { return delegate.property(key); }
        @Override public Map<String, String> properties() { return delegate.properties(); }
    }

    private record DecayedEdge(MindMapEdge delegate, double decayedConfidence) implements MindMapEdge {
        @Override public String id() { return delegate.id(); }
        @Override public String sourceNodeId() { return delegate.sourceNodeId(); }
        @Override public String targetNodeId() { return delegate.targetNodeId(); }
        @Override public String edgeType() { return delegate.edgeType(); }
        @Override public ValidationTier tier() { return delegate.tier(); }
        @Override public ConfidenceOrigin confidenceOrigin() { return delegate.confidenceOrigin(); }
        @Override public double confidence() { return decayedConfidence; }
        @Override public String provenance() { return delegate.provenance(); }
        @Override public Instant createdAt() { return delegate.createdAt(); }
        @Override public Instant updatedAt() { return delegate.updatedAt(); }
        @Override public Instant validFrom() { return delegate.validFrom(); }
        @Override public Instant validUntil() { return delegate.validUntil(); }
        @Override public Double pleasure() { return delegate.pleasure(); }
        @Override public Double arousal() { return delegate.arousal(); }
        @Override public Double dominance() { return delegate.dominance(); }
        @Override public Optional<String> property(String key) { return delegate.property(key); }
        @Override public Map<String, String> properties() { return delegate.properties(); }
    }
}
