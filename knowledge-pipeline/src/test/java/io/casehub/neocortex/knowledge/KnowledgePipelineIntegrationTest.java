package io.casehub.neocortex.knowledge;

import io.casehub.connectors.Page;
import io.casehub.connectors.PageRequest;
import io.casehub.connectors.location.model.Coordinates;
import io.casehub.connectors.location.model.Place;
import io.casehub.connectors.location.model.PriceLevel;
import io.casehub.connectors.location.spi.LocationPlatform;
import io.casehub.neocortex.knowledge.cache.CacheDecayPolicy;
import io.casehub.neocortex.knowledge.cache.CacheEvictionScheduler;
import io.casehub.neocortex.knowledge.cache.EntityMetadataStore;
import io.casehub.neocortex.knowledge.cache.InMemorySpatialCacheStore;
import io.casehub.neocortex.knowledge.cache.QueryCacheStore;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.knowledge.promotion.EntityPromoter;
import io.casehub.neocortex.knowledge.research.ResearchOrchestrator;
import io.casehub.neocortex.knowledge.research.ResearchSessionStore;
import io.casehub.neocortex.knowledge.resolution.EntityResolutionEngine;
import io.casehub.neocortex.knowledge.resolution.PlaceMatcher;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.NodeRef;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePipelineIntegrationTest {

    private HikariDataSource pipelineDs;
    private HikariDataSource researchDs;
    private KnowledgePipelineOrchestrator orchestrator;
    private InMemoryMindMapStore mindMap;
    private DedupIndexStore dedupStore;
    private ResearchOrchestrator researchOrchestrator;
    private InMemorySpatialCacheStore cacheStore;
    private EntityMetadataStore metadataStore;

    @BeforeEach
    void setUp() {
        pipelineDs = SqliteDataSourceFactory.create(":memory:", 3, 5000);
        SqliteDataSourceFactory.migrate(pipelineDs, "classpath:db/knowledge-pipeline");
        researchDs = SqliteDataSourceFactory.create(":memory:", 3, 5000);
        SqliteDataSourceFactory.migrate(researchDs, "classpath:db/knowledge-research");

        cacheStore = new InMemorySpatialCacheStore();
        var queryCache = new QueryCacheStore(pipelineDs);
        dedupStore = new DedupIndexStore(pipelineDs);
        metadataStore = new EntityMetadataStore(pipelineDs);
        var sessionStore = new ResearchSessionStore(researchDs);
        mindMap = new InMemoryMindMapStore();
        researchOrchestrator = new ResearchOrchestrator(sessionStore, mindMap);
        var promoter = new EntityPromoter(mindMap, cacheStore, dedupStore, sessionStore);
        var resolutionEngine = new EntityResolutionEngine(new PlaceMatcher());

        var provider = new StubProvider(List.of(
            new Place("ChIJ_ondine", "Ondine", "George IV Bridge, Edinburgh",
                new Coordinates(55.9489, -3.1908),
                List.of("restaurant", "seafood"), 4.6, 1200,
                "+44 131 226 1888", "https://ondinerestaurant.co.uk",
                PriceLevel.EXPENSIVE),
            new Place("ChIJ_balmoral", "The Balmoral", "1 Princes St, Edinburgh",
                new Coordinates(55.9524, -3.1885),
                List.of("hotel", "lodging"), 4.5, 3400,
                "+44 131 556 2414", "https://www.roccofortehotels.com/hotels-and-resorts/the-balmoral-hotel/",
                PriceLevel.VERY_EXPENSIVE)
        ));

        orchestrator = new KnowledgePipelineOrchestrator(
            List.of(provider), cacheStore, queryCache, dedupStore,
            metadataStore, resolutionEngine, promoter,
            new CacheDecayPolicy(), 6);
    }

    @AfterEach
    void tearDown() {
        pipelineDs.close();
        researchDs.close();
    }

    @Test
    void endToEndSearchCacheAndPromote() {
        var results = orchestrator.search(
            new KnowledgeQuery.NearbySearch(
                new Coordinates(55.9500, -3.1900), 1000, CacheFilter.none()),
            "tenant-1");

        assertThat(results).hasSize(2);
        assertThat(results).extracting("name")
            .containsExactlyInAnyOrder("Ondine", "The Balmoral");

        var ondine = results.stream()
            .filter(e -> e.name().equals("Ondine")).findFirst().get();
        assertThat(ondine.source()).isEqualTo("stub-provider");
        assertThat(ondine.externalId()).isEqualTo("ChIJ_ondine");
        assertThat(ondine.properties()).containsEntry("phone", "+44 131 226 1888");

        var dedupEntry = dedupStore.lookup("stub-provider", "ChIJ_ondine");
        assertThat(dedupEntry).isPresent();
        assertThat(dedupEntry.get().mindMapNodeId()).isNull();

        var promotionResult = orchestrator.promote(
            new PromotionRequest(ondine.id(), "tenant-1", null));
        assertThat(promotionResult.created()).isTrue();
        assertThat(promotionResult.mindMapNodeId()).isNotNull();

        var updatedDedup = dedupStore.lookup("stub-provider", "ChIJ_ondine");
        assertThat(updatedDedup.get().mindMapNodeId())
            .isEqualTo(promotionResult.mindMapNodeId());

        var placeSubgraphs = mindMap.listSubgraphs("tenant-1").stream()
            .filter(s -> SubgraphTypes.PLACE.equals(s.type()))
            .toList();
        assertThat(placeSubgraphs).hasSize(1);

        MindMapNode node = mindMap.getNode(promotionResult.mindMapNodeId(), "tenant-1");
        assertThat(node.name()).isEqualTo("Ondine");
        assertThat(node.refs()).anySatisfy(ref -> {
            assertThat(ref.scheme()).isEqualTo("stub-provider");
            assertThat(ref.id()).isEqualTo("ChIJ_ondine");
        });
    }

    @Test
    void researchSessionSearchAndPromote() {
        var session = researchOrchestrator.create(
            "Edinburgh Trip", null, "tenant-1");

        var results = orchestrator.search(
            new KnowledgeQuery.NearbySearch(
                new Coordinates(55.9500, -3.1900), 1000, CacheFilter.none()),
            "tenant-1", session.id());

        assertThat(results).hasSize(2);

        var balmoral = results.stream()
            .filter(e -> e.name().equals("The Balmoral")).findFirst().get();

        assertThat(metadataStore.sessionsFor(balmoral.id()))
            .contains(session.id());

        var promotion = orchestrator.promote(
            new PromotionRequest(balmoral.id(), "tenant-1", session.id()));

        assertThat(promotion.created()).isTrue();

        var node = mindMap.getNode(promotion.mindMapNodeId(), "tenant-1");
        assertThat(node).isNotNull();

        var neighbors = mindMap.neighbors(promotion.mindMapNodeId(), "tenant-1");
        assertThat(neighbors).isNotEmpty();
    }

    @Test
    void evictionPreservesSessionProtectedEntities() {
        var session = researchOrchestrator.create(
            "Edinburgh Trip", null, "tenant-1");

        orchestrator.search(
            new KnowledgeQuery.NearbySearch(
                new Coordinates(55.9500, -3.1900), 1000, CacheFilter.none()),
            "tenant-1", session.id());

        var entities = cacheStore.discoverTenants();
        assertThat(entities).contains("tenant-1");

        var sessionStore = new ResearchSessionStore(researchDs);
        var scheduler = new CacheEvictionScheduler(
            cacheStore, metadataStore, sessionStore, dedupStore,
            Duration.ofDays(90), Duration.ofDays(180));

        scheduler.runEviction();

        assertThat(cacheStore.discoverTenants()).contains("tenant-1");
    }

    @Test
    void duplicateSearchReturnsCachedResults() {
        var query = new KnowledgeQuery.NearbySearch(
            new Coordinates(55.9500, -3.1900), 1000, CacheFilter.none());

        var first = orchestrator.search(query, "tenant-1");
        var second = orchestrator.search(query, "tenant-1");

        assertThat(first).hasSameSizeAs(second);
        assertThat(first).extracting("id")
            .containsExactlyInAnyOrderElementsOf(
                second.stream().map(CachedEntity::id).toList());
    }

    static class StubProvider implements LocationPlatform {
        private final List<Place> places;

        StubProvider(List<Place> places) { this.places = places; }

        @Override public String id() { return "stub-provider"; }
        @Override public boolean supports(Class<?> cap) {
            return PlaceSearch.class.isAssignableFrom(cap);
        }
        @Override public PlaceSearch placeSearch(String userId) {
            return new PlaceSearch() {
                @Override public Page<Place> searchByText(String q, PageRequest p) {
                    return new Page<>(places, null, false);
                }
                @Override public Page<Place> searchNearby(Coordinates loc, int r, PageRequest p) {
                    return new Page<>(places, null, false);
                }
                @Override public Page<Place> searchByCategory(String cat, Coordinates loc, int r, PageRequest p) {
                    return new Page<>(places, null, false);
                }
            };
        }
        @Override public PlaceDetails placeDetails(String userId) { return null; }
        @Override public Geocoding geocoding(String userId) { return null; }
        @Override public Directions directions(String userId) { return null; }
    }
}
