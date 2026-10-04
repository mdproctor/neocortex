package io.casehub.neocortex.knowledge.promotion;

import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.PromotionRequest;
import io.casehub.neocortex.knowledge.ResearchSession;
import io.casehub.neocortex.knowledge.ResearchState;
import io.casehub.neocortex.knowledge.cache.InMemorySpatialCacheStore;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.knowledge.research.ResearchSessionStore;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EntityPromoterTest {

    private InMemoryMindMapStore mindMap;
    private InMemorySpatialCacheStore cache;
    private DedupIndexStore dedup;
    private ResearchSessionStore sessions;
    private EntityPromoter promoter;
    private static final String TENANT = "test-tenant";

    @BeforeEach
    void setUp() {
        mindMap = new InMemoryMindMapStore();
        cache = new InMemorySpatialCacheStore();
        dedup = new DedupIndexStore(":memory:");
        sessions = new ResearchSessionStore(":memory:");
        promoter = new EntityPromoter(mindMap, cache, dedup, sessions);
    }

    @AfterEach
    void tearDown() {
        dedup.close();
        sessions.close();
    }

    private CachedEntity cachedEntity(String id) {
        return new CachedEntity(id, "Ondine", new Coordinates(55.9505, -3.1915),
            "restaurant", "google", "ChIJ-ondine",
            Map.of("phone", "+44 131 226 1888", "rating", "4.5"),
            Instant.now(), null, Instant.now().plusSeconds(86400), Set.of(), false);
    }

    @Test
    void createNewPlaceNodeWhenNoMatch() {
        var entity = cachedEntity("e1");
        cache.set(entity, TENANT);
        dedup.upsert("google", "ChIJ-ondine", "e1");

        var result = promoter.promote(new PromotionRequest("e1", TENANT, null));
        assertThat(result.created()).isTrue();
        assertThat(result.mindMapNodeId()).isNotNull();

        MindMapNode node = mindMap.getNode(result.mindMapNodeId(), TENANT);
        assertThat(node.name()).isEqualTo("Ondine");
        assertThat(node.property("lat")).isPresent();
        assertThat(node.refs()).anyMatch(r ->
            "google".equals(r.scheme()) && "ChIJ-ondine".equals(r.id()));

        var dedupEntry = dedup.lookup("google", "ChIJ-ondine");
        assertThat(dedupEntry.get().mindMapNodeId()).isEqualTo(result.mindMapNodeId());
    }

    @Test
    void enrichExistingNodeWhenDedupHasMindMapId() {
        var entity = cachedEntity("e1");
        cache.set(entity, TENANT);

        String sgId = mindMap.createSubgraph(
            new SubgraphInput(SubgraphTypes.PLACE, SubgraphTypes.PLACE, null), TENANT);
        String existingNodeId = mindMap.addNode(
            NodeInput.of("Ondine", sgId), TENANT);

        dedup.upsert("google", "ChIJ-ondine", "e1");
        dedup.setMindMapNodeId("google", "ChIJ-ondine", existingNodeId);

        var result = promoter.promote(new PromotionRequest("e1", TENANT, null));
        assertThat(result.created()).isFalse();
        assertThat(result.mindMapNodeId()).isEqualTo(existingNodeId);

        MindMapNode node = mindMap.getNode(existingNodeId, TENANT);
        assertThat(node.property("lat")).isPresent();
        assertThat(node.refs()).anyMatch(r -> "google".equals(r.scheme()));
    }

    @Test
    void enrichExistingNodeFoundByTypedResolve() {
        var entity = cachedEntity("e1");
        cache.set(entity, TENANT);
        dedup.upsert("google", "ChIJ-ondine", "e1");

        String sgId = mindMap.createSubgraph(
            new SubgraphInput(SubgraphTypes.PLACE, SubgraphTypes.PLACE, null), TENANT);
        mindMap.addNode(NodeInput.of("Ondine", sgId), TENANT);

        var result = promoter.promote(new PromotionRequest("e1", TENANT, null));
        assertThat(result.created()).isFalse();

        var dedupEntry = dedup.lookup("google", "ChIJ-ondine");
        assertThat(dedupEntry.get().mindMapNodeId()).isNotNull();
    }

    @Test
    void linksToResearchSessionWhenProvided() {
        var entity = cachedEntity("e1");
        cache.set(entity, TENANT);
        dedup.upsert("google", "ChIJ-ondine", "e1");

        String sgId = mindMap.createSubgraph(
            new SubgraphInput("Edinburgh trip", SubgraphTypes.RESEARCH_AREA, null),
            TENANT);
        String rootId = mindMap.addNode(
            NodeInput.of("Edinburgh trip", sgId), TENANT);
        mindMap.updateSubgraph(sgId, rootId, TENANT);

        sessions.insert(new ResearchSession("session-1", "Edinburgh trip",
            null, sgId, ResearchState.ACTIVE, TENANT,
            Instant.now(), Instant.now()));

        var result = promoter.promote(
            new PromotionRequest("e1", TENANT, "session-1"));
        assertThat(result.created()).isTrue();

        var edges = mindMap.neighbors(result.mindMapNodeId(), TENANT);
        assertThat(edges).anyMatch(e ->
            "discovered-in".equals(e.edgeType())
            && rootId.equals(e.targetNodeId()));
    }
}
