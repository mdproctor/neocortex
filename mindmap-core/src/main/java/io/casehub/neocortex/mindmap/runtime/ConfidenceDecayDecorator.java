package io.casehub.neocortex.mindmap.runtime;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.mindmap.AbstractForwardingMindMapStore;
import io.casehub.neocortex.mindmap.EdgeTypeDefinition;
import io.casehub.neocortex.mindmap.MindMapEdge;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.NodeRef;
import io.casehub.neocortex.mindmap.ValidationTier;
import io.casehub.platform.api.identity.PrincipalId;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class ConfidenceDecayDecorator extends AbstractForwardingMindMapStore {

    private final double defaultHalfLifeDays;

    public ConfidenceDecayDecorator(MindMapStore delegate, double defaultHalfLifeDays) {
        super(delegate);
        this.defaultHalfLifeDays = defaultHalfLifeDays;
    }

    @Override
    public MindMapNode getNode(String nodeId, String tenantId) {
        MindMapNode node = delegate().getNode(nodeId, tenantId);
        if (node == null) {return null;}
        return withDecayedConfidence(node);
    }

    @Override
    public List<MindMapNode> nodesIn(String subgraphId, String tenantId) {
        return delegate().nodesIn(subgraphId, tenantId).stream()
                         .map(this::withDecayedConfidence)
                         .toList();
    }

    @Override
    public List<MindMapNode> search(MindMapQuery query) {
        List<MindMapNode> results = delegate().search(query).stream()
                                              .map(this::withDecayedConfidence)
                                              .collect(Collectors.toCollection(ArrayList::new));

        if (query.minConfidence() != null) {
            results.removeIf(n -> n.confidence().value() < query.minConfidence());
        }
        return results;
    }

    @Override
    public List<MindMapEdge> neighbors(String nodeId, String tenantId) {
        return neighbors(nodeId, tenantId, (PrincipalId) null);
    }

    @Override
    public List<MindMapEdge> neighbors(String nodeId, String tenantId, PrincipalId callerPrincipal) {
        return delegate().neighbors(nodeId, tenantId, callerPrincipal).stream()
                         .map(this::withDecayedConfidence)
                         .toList();
    }

    @Override
    public List<MindMapEdge> neighbors(String nodeId, String edgeType, String tenantId) {
        return neighbors(nodeId, edgeType, tenantId, (PrincipalId) null);
    }

    @Override
    public List<MindMapEdge> neighbors(String nodeId, String edgeType, String tenantId, PrincipalId callerPrincipal) {
        return delegate().neighbors(nodeId, edgeType, tenantId, callerPrincipal).stream()
                         .map(this::withDecayedConfidence)
                         .toList();
    }

    @Override
    public List<MindMapEdge> bridgeEdges(String subgraphId, String tenantId) {
        return bridgeEdges(subgraphId, tenantId, (PrincipalId) null);
    }

    @Override
    public List<MindMapEdge> bridgeEdges(String subgraphId, String tenantId, PrincipalId callerPrincipal) {
        return delegate().bridgeEdges(subgraphId, tenantId, callerPrincipal).stream()
                         .map(this::withDecayedConfidence)
                         .toList();
    }

    private MindMapNode withDecayedConfidence(MindMapNode node) {
        Confidence c = node.confidence();
        double decayed = applyDecay(c.value(), c.decayReference(), defaultHalfLifeDays);
        if (decayed == c.value()) {return node;}
        return new DecayedNode(node, c.withValue(decayed));
    }


    private MindMapEdge withDecayedConfidence(MindMapEdge edge) {
        Confidence c        = edge.confidence();
        double     halfLife = resolveHalfLife(edge.edgeType());
        double     decayed  = applyDecay(c.value(), c.decayReference(), halfLife);
        if (decayed == c.value()) {return edge;}
        return new DecayedEdge(edge, c.withValue(decayed));
    }


    private double resolveHalfLife(String edgeType) {
        for (EdgeTypeDefinition def : delegate().registeredEdgeTypes()) {
            if (def.defaultDecayHalfLifeDays() != null
                && (def.canonical().equals(edgeType) || def.aliases().contains(edgeType))) {
                return def.defaultDecayHalfLifeDays();
            }
        }
        return defaultHalfLifeDays;
    }

    static double applyDecay(double confidence, Instant since, double halfLifeDays) {
        if (since == null) {return confidence;}
        double hoursSince = Duration.between(since, Instant.now()).toHours();
        if (hoursSince <= 0) {return confidence;}
        double halfLifeHours = halfLifeDays * 24.0;
        return confidence * Math.pow(2.0, -hoursSince / halfLifeHours);
    }

    private record DecayedNode(MindMapNode delegate, Confidence decayedConfidence) implements MindMapNode {
        @Override
        public String id()                           {return delegate.id();}

        @Override
        public String name()                         {return delegate.name();}

        @Override
        public String subgraphId()                   {return delegate.subgraphId();}

        @Override
        public String subgraphType()                 {return delegate.subgraphType();}

        @Override
        public Confidence confidence()               {return decayedConfidence;}

        @Override
        public String provenance()                   {return delegate.provenance();}

        @Override
        public Instant createdAt()                   {return delegate.createdAt();}

        @Override
        public Instant updatedAt()                   {return delegate.updatedAt();}

        @Override
        public Instant validFrom()                   {return delegate.validFrom();}

        @Override
        public Instant validUntil()                  {return delegate.validUntil();}

        @Override
        public Set<String> traits()                  {return delegate.traits();}

        @Override
        public Set<NodeRef> refs()                   {return delegate.refs();}

        @Override
        public Double pleasure()                     {return delegate.pleasure();}

        @Override
        public Double arousal()                      {return delegate.arousal();}

        @Override
        public Double dominance()                    {return delegate.dominance();}

        @Override
        public Optional<String> property(String key) {return delegate.property(key);}

        @Override
        public Map<String, String> properties()      {return delegate.properties();}

        @Override
        public PrincipalId principalId()             {return delegate.principalId();}

        @Override
        public Set<String> sharedWith()              {return delegate.sharedWith();}
    }

    private record DecayedEdge(MindMapEdge delegate, Confidence decayedConfidence) implements MindMapEdge {
        @Override
        public String id()                           {return delegate.id();}

        @Override
        public String sourceNodeId()                 {return delegate.sourceNodeId();}

        @Override
        public String targetNodeId()                 {return delegate.targetNodeId();}

        @Override
        public String edgeType()                     {return delegate.edgeType();}

        @Override
        public ValidationTier tier()                 {return delegate.tier();}

        @Override
        public Confidence confidence()               {return decayedConfidence;}

        @Override
        public String provenance()                   {return delegate.provenance();}

        @Override
        public Instant createdAt()                   {return delegate.createdAt();}

        @Override
        public Instant updatedAt()                   {return delegate.updatedAt();}

        @Override
        public Instant validFrom()                   {return delegate.validFrom();}

        @Override
        public Instant validUntil()                  {return delegate.validUntil();}

        @Override
        public Double pleasure()                     {return delegate.pleasure();}

        @Override
        public Double arousal()                      {return delegate.arousal();}

        @Override
        public Double dominance()                    {return delegate.dominance();}

        @Override
        public Optional<String> property(String key) {return delegate.property(key);}

        @Override
        public Map<String, String> properties()      {return delegate.properties();}
    }
}
