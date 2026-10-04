package io.casehub.neocortex.knowledge.research;

import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.ResearchSession;
import io.casehub.neocortex.knowledge.ResearchSessionService;
import io.casehub.neocortex.knowledge.ResearchState;
import io.casehub.neocortex.knowledge.SpatialCacheStore;
import io.casehub.neocortex.knowledge.cache.CacheDecayPolicy;
import io.casehub.neocortex.knowledge.cache.EntityMetadataStore;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class ResearchOrchestrator implements ResearchSessionService {

    private final ResearchSessionStore sessionStore;
    private final MindMapStore mindMapStore;
    private final SpatialCacheStore cacheStore;
    private final EntityMetadataStore metadataStore;
    private final CacheDecayPolicy decayPolicy;

    @Inject
    public ResearchOrchestrator(ResearchSessionStore sessionStore,
                                 MindMapStore mindMapStore,
                                 SpatialCacheStore cacheStore,
                                 EntityMetadataStore metadataStore,
                                 CacheDecayPolicy decayPolicy) {
        this.sessionStore = sessionStore;
        this.mindMapStore = mindMapStore;
        this.cacheStore = cacheStore;
        this.metadataStore = metadataStore;
        this.decayPolicy = decayPolicy;
    }

    @Override
    public ResearchSession create(String name, String criteria, String tenantId) {
        String subgraphId = mindMapStore.createSubgraph(
            new SubgraphInput(name, SubgraphTypes.RESEARCH_AREA, null), tenantId);

        String rootNodeId = mindMapStore.addNode(
            NodeInput.of(name, subgraphId).withProvenance("knowledge-pipeline"),
            tenantId);

        mindMapStore.updateSubgraph(subgraphId, rootNodeId, tenantId);

        Instant now = Instant.now();
        ResearchSession session = new ResearchSession(
            UUID.randomUUID().toString(), name, criteria, subgraphId,
            ResearchState.ACTIVE, tenantId, now, now);
        sessionStore.insert(session);
        return session;
    }

    @Override
    public void pause(String sessionId) {
        sessionStore.updateState(sessionId, ResearchState.PAUSED, Instant.now());
    }

    @Override
    public void resume(String sessionId) {
        Instant now = Instant.now();
        sessionStore.updateState(sessionId, ResearchState.ACTIVE, now);
        var session = sessionStore.get(sessionId);
        if (session.isEmpty()) {return;}
        Set<String> entityIds = metadataStore.entitiesForSession(sessionId);
        for (String entityId : entityIds) {
            CachedEntity entity = cacheStore.get(entityId, session.get().tenantId());
            if (entity != null) {
                Instant newExpiry = now.plus(decayPolicy.ttlFor(entity));
                cacheStore.expire(entityId, newExpiry, session.get().tenantId());
            }
        }
    }

    @Override
    public void complete(String sessionId) {
        sessionStore.updateState(sessionId, ResearchState.COMPLETED, Instant.now());
    }

    @Override
    public List<ResearchSession> listActive(String tenantId) {
        return sessionStore.listByState(tenantId, ResearchState.ACTIVE);
    }

    @Override
    public ResearchSession get(String sessionId) {
        return sessionStore.get(sessionId).orElse(null);
    }
}
