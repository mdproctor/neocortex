package io.casehub.neocortex.knowledge.promotion;

import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.PromotionRequest;
import io.casehub.neocortex.knowledge.PromotionResult;
import io.casehub.neocortex.knowledge.SpatialCacheStore;
import io.casehub.neocortex.knowledge.KnowledgePipelineMetrics;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.knowledge.research.ResearchSessionStore;
import io.casehub.neocortex.mindmap.EdgeInput;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.NodeRef;
import io.casehub.neocortex.mindmap.NodeUpdate;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.intelligence.SubgraphUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class EntityPromoter {

    private final MindMapStore mindMapStore;
    private final SpatialCacheStore cacheStore;
    private final DedupIndexStore dedupStore;
    private final ResearchSessionStore sessionStore;

    @Inject
    public EntityPromoter(MindMapStore mindMapStore,
                          SpatialCacheStore cacheStore,
                          DedupIndexStore dedupStore,
                          ResearchSessionStore sessionStore) {
        this.mindMapStore = mindMapStore;
        this.cacheStore = cacheStore;
        this.dedupStore = dedupStore;
        this.sessionStore = sessionStore;
    }

    private KnowledgePipelineMetrics metrics;

    @Inject
    void setMetrics(KnowledgePipelineMetrics metrics) {
        this.metrics = metrics;
    }


    public PromotionResult promote(PromotionRequest request) {
        CachedEntity entity = cacheStore.get(
            request.cacheEntityId(), request.tenantId());
        if (entity == null) {
            throw new IllegalArgumentException(
                "Cache entity not found: " + request.cacheEntityId());
        }

        var dedupEntry = dedupStore.lookup(entity.source(), entity.externalId());
        if (dedupEntry.isPresent() && dedupEntry.get().mindMapNodeId() != null) {
            enrichExistingNode(dedupEntry.get().mindMapNodeId(), entity,
                request.tenantId());
            linkToResearchSession(dedupEntry.get().mindMapNodeId(), request);
            if (metrics != null) metrics.recordPromotion(false, request.tenantId());
            return new PromotionResult(dedupEntry.get().mindMapNodeId(), false);
        }

        MindMapNode existing = resolveInPlaceSubgraphs(
            entity.name(), request.tenantId());
        if (existing != null) {
            enrichExistingNode(existing.id(), entity, request.tenantId());
            dedupStore.setMindMapNodeId(
                entity.source(), entity.externalId(), existing.id());
            linkToResearchSession(existing.id(), request);
            if (metrics != null) metrics.recordPromotion(false, request.tenantId());
            return new PromotionResult(existing.id(), false);
        }

        String nodeId = createNewPlaceNode(entity, request.tenantId());
        dedupStore.setMindMapNodeId(
            entity.source(), entity.externalId(), nodeId);
        linkToResearchSession(nodeId, request);
        if (metrics != null) metrics.recordPromotion(true, request.tenantId());
        return new PromotionResult(nodeId, true);
    }

    private MindMapNode resolveInPlaceSubgraphs(String name, String tenantId) {
        return mindMapStore.listSubgraphs(tenantId).stream()
            .filter(s -> SubgraphTypes.PLACE.equals(s.type()))
            .map(s -> mindMapStore.resolveNode(name, s.id(), tenantId))
            .filter(n -> n != null)
            .findFirst()
            .orElse(null);
    }

    private void enrichExistingNode(String nodeId, CachedEntity entity,
                                     String tenantId) {
        Map<String, String> props = new HashMap<>();
        if (entity.coordinates() != null) {
            props.put("lat", String.valueOf(entity.coordinates().lat()));
            props.put("lng", String.valueOf(entity.coordinates().lng()));
        }
        if (entity.category() != null) props.put("category", entity.category());
        entity.properties().forEach(props::put);

        NodeUpdate update = NodeUpdate.empty()
            .withPropertiesToSet(props)
            .withRefsToAdd(Set.of(
                new NodeRef(entity.source(), entity.externalId(), null)));
        mindMapStore.updateNode(nodeId, update, tenantId);
    }

    private String createNewPlaceNode(CachedEntity entity, String tenantId) {
        String subgraphId = SubgraphUtils.ensureSubgraph(mindMapStore, SubgraphTypes.PLACE, tenantId);

        Map<String, String> props = new HashMap<>();
        if (entity.coordinates() != null) {
            props.put("lat", String.valueOf(entity.coordinates().lat()));
            props.put("lng", String.valueOf(entity.coordinates().lng()));
        }
        if (entity.category() != null) props.put("category", entity.category());
        entity.properties().forEach(props::put);

        return mindMapStore.addNode(
            NodeInput.of(entity.name(), subgraphId)
                .withProperties(props)
                .withRefs(Set.of(
                    new NodeRef(entity.source(), entity.externalId(), null)))
                .withProvenance("knowledge-pipeline"),
            tenantId);
    }

    private void linkToResearchSession(String nodeId, PromotionRequest request) {
        if (request.researchSessionId() == null) return;
        var session = sessionStore.get(request.researchSessionId());
        if (session.isEmpty()) return;
        var subgraph = mindMapStore.getSubgraph(
            session.get().mindMapSubgraphId(), request.tenantId());
        if (subgraph == null || subgraph.rootNodeId() == null) return;
        mindMapStore.addEdge(
            EdgeInput.of(nodeId, subgraph.rootNodeId(), "discovered-in")
                .withProvenance("knowledge-pipeline"),
            request.tenantId());
    }


}
