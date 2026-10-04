package io.casehub.neocortex.knowledge.research;

import com.zaxxer.hikari.HikariDataSource;
import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.ResearchState;
import io.casehub.neocortex.knowledge.cache.CacheDecayPolicy;
import io.casehub.neocortex.knowledge.cache.EntityMetadataStore;
import io.casehub.neocortex.knowledge.cache.InMemorySpatialCacheStore;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchOrchestratorTest {

    private InMemoryMindMapStore mindMap;
    private ResearchSessionStore sessionStore;
    private InMemorySpatialCacheStore cacheStore;
    private EntityMetadataStore metadataStore;
    private HikariDataSource pipelineDs;
    private ResearchOrchestrator orchestrator;
    private static final String TENANT = "test-tenant";

    @BeforeEach
    void setUp() {
        mindMap = new InMemoryMindMapStore();
        sessionStore = new ResearchSessionStore(":memory:");
        cacheStore = new InMemorySpatialCacheStore();
        pipelineDs = SqliteDataSourceFactory.create(":memory:", 3, 5000);
        SqliteDataSourceFactory.migrate(pipelineDs, "classpath:db/knowledge-pipeline");
        metadataStore = new EntityMetadataStore(pipelineDs);
        orchestrator = new ResearchOrchestrator(sessionStore, mindMap,
            cacheStore, metadataStore, new CacheDecayPolicy());
    }

    @AfterEach
    void tearDown() {
        sessionStore.close();
        pipelineDs.close();
    }

    @Test
    void createCreatesSubgraphAndRootNode() {
        var session = orchestrator.create("Edinburgh trip",
            "{\"maxPrice\":150}", TENANT);

        assertThat(session.name()).isEqualTo("Edinburgh trip");
        assertThat(session.state()).isEqualTo(ResearchState.ACTIVE);
        assertThat(session.tenantId()).isEqualTo(TENANT);

        var subgraph = mindMap.getSubgraph(session.mindMapSubgraphId(), TENANT);
        assertThat(subgraph).isNotNull();
        assertThat(subgraph.type()).isEqualTo(SubgraphTypes.RESEARCH_AREA);
        assertThat(subgraph.rootNodeId()).isNotNull();

        var rootNode = mindMap.getNode(subgraph.rootNodeId(), TENANT);
        assertThat(rootNode.name()).isEqualTo("Edinburgh trip");
    }

    @Test
    void pauseTransitionsToCorrectState() {
        var session = orchestrator.create("Trip", null, TENANT);
        orchestrator.pause(session.id());

        var reloaded = orchestrator.get(session.id());
        assertThat(reloaded.state()).isEqualTo(ResearchState.PAUSED);
    }

    @Test
    void resumeTransitionsToActive() {
        var session = orchestrator.create("Trip", null, TENANT);
        orchestrator.pause(session.id());
        orchestrator.resume(session.id());

        var reloaded = orchestrator.get(session.id());
        assertThat(reloaded.state()).isEqualTo(ResearchState.ACTIVE);
    }

    @Test
    void resumeExtendsTtlForSessionEntities() {
        var session = orchestrator.create("Trip", null, TENANT);
        orchestrator.pause(session.id());

        Instant shortExpiry = Instant.now().plusSeconds(60);
        var entity = new CachedEntity("e1", "Place", new Coordinates(51.5, -0.1),
                                      "restaurant", "google", "ext-1", Map.of(),
                                      Instant.now(), shortExpiry, Set.of(), false);
        cacheStore.set(entity, TENANT);
        metadataStore.addSession("e1", session.id());

        orchestrator.resume(session.id());

        CachedEntity updated = cacheStore.get("e1", TENANT);
        assertThat(updated.expiresAt()).isAfter(shortExpiry);
    }


    @Test
    void completeTransitionsToCompleted() {
        var session = orchestrator.create("Trip", null, TENANT);
        orchestrator.complete(session.id());

        var reloaded = orchestrator.get(session.id());
        assertThat(reloaded.state()).isEqualTo(ResearchState.COMPLETED);
    }

    @Test
    void listActiveReturnsOnlyActiveSessions() {
        orchestrator.create("Trip 1", null, TENANT);
        var trip2 = orchestrator.create("Trip 2", null, TENANT);
        orchestrator.pause(trip2.id());
        orchestrator.create("Trip 3", null, TENANT);

        var active = orchestrator.listActive(TENANT);
        assertThat(active).hasSize(2);
        assertThat(active).extracting("name")
            .containsExactlyInAnyOrder("Trip 1", "Trip 3");
    }

    @Test
    void listActiveIsTenantIsolated() {
        orchestrator.create("Trip A", null, "t1");
        orchestrator.create("Trip B", null, "t2");

        assertThat(orchestrator.listActive("t1")).hasSize(1);
        assertThat(orchestrator.listActive("t2")).hasSize(1);
    }

    @Test
    void getReturnsNullForUnknown() {
        assertThat(orchestrator.get("nonexistent")).isNull();
    }
}
