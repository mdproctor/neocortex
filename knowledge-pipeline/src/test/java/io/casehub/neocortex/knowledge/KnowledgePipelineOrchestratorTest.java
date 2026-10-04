package io.casehub.neocortex.knowledge;

import io.casehub.connectors.Page;
import io.casehub.connectors.PageRequest;
import io.casehub.connectors.location.model.Coordinates;
import io.casehub.connectors.location.model.Place;
import io.casehub.connectors.location.model.PriceLevel;
import io.casehub.connectors.location.spi.LocationPlatform;
import io.casehub.neocortex.knowledge.cache.CacheDecayPolicy;
import io.casehub.neocortex.knowledge.cache.CacheKeyGenerator;
import io.casehub.neocortex.knowledge.cache.EntityMetadataStore;
import io.casehub.neocortex.knowledge.cache.InMemorySpatialCacheStore;
import io.casehub.neocortex.knowledge.cache.QueryCacheStore;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.knowledge.promotion.EntityPromoter;
import io.casehub.neocortex.knowledge.research.ResearchOrchestrator;
import io.casehub.neocortex.knowledge.research.ResearchSessionStore;
import io.casehub.neocortex.knowledge.resolution.EntityResolutionEngine;
import io.casehub.neocortex.knowledge.resolution.PlaceMatcher;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePipelineOrchestratorTest {

    private HikariDataSource pipelineDs;
    private HikariDataSource researchDs;
    private KnowledgePipelineOrchestrator orchestrator;
    private InMemorySpatialCacheStore cacheStore;
    private QueryCacheStore queryCache;
    private StubLocationPlatform locationPlatform;

    @BeforeEach
    void setUp() {
        pipelineDs = SqliteDataSourceFactory.create(":memory:", 3, 5000);
        SqliteDataSourceFactory.migrate(pipelineDs, "classpath:db/knowledge-pipeline");
        researchDs = SqliteDataSourceFactory.create(":memory:", 3, 5000);
        SqliteDataSourceFactory.migrate(researchDs, "classpath:db/knowledge-research");

        cacheStore = new InMemorySpatialCacheStore();
        queryCache = new QueryCacheStore(pipelineDs);
        var dedupStore = new DedupIndexStore(pipelineDs);
        var metadataStore = new EntityMetadataStore(pipelineDs);
        var sessionStore = new ResearchSessionStore(researchDs);
        var mindMap = new InMemoryMindMapStore();
        var researchOrchestrator = new ResearchOrchestrator(sessionStore, mindMap,
            cacheStore, metadataStore, new CacheDecayPolicy());
        var promoter = new EntityPromoter(mindMap, cacheStore, dedupStore, sessionStore);
        var resolutionEngine = new EntityResolutionEngine(new PlaceMatcher());

        locationPlatform = new StubLocationPlatform();

        orchestrator = new KnowledgePipelineOrchestrator(
            List.of(locationPlatform),
            cacheStore,
            queryCache,
            dedupStore,
            metadataStore,
            resolutionEngine,
            promoter,
            new CacheDecayPolicy(),
            6
        );
    }

    @AfterEach
    void tearDown() {
        pipelineDs.close();
        researchDs.close();
    }

    @Test
    void searchCacheMissFetchesFromProvider() {
        locationPlatform.places = List.of(
            new Place("g1", "Ondine", "Edinburgh", new Coordinates(55.9533, -3.1883),
                List.of("restaurant"), 4.5, 200, "+44 131 226 1888", "https://ondine.co.uk",
                PriceLevel.MODERATE)
        );

        var results = orchestrator.search(
            new KnowledgeQuery.CategorySearch("restaurant",
                new Coordinates(55.9533, -3.1883), 1000),
            "tenant-1");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Ondine");
        assertThat(results.get(0).source()).isEqualTo("stub");
    }

    @Test
    void searchCacheHitReturnsWithoutFetch() {
        locationPlatform.places = List.of(
            new Place("g1", "Ondine", "Edinburgh", new Coordinates(55.9533, -3.1883),
                List.of("restaurant"), 4.5, 200, null, null, null)
        );

        var query = new KnowledgeQuery.CategorySearch("restaurant",
            new Coordinates(55.9533, -3.1883), 1000);
        orchestrator.search(query, "tenant-1");
        int firstFetchCount = locationPlatform.fetchCount;

        var results = orchestrator.search(query, "tenant-1");

        assertThat(results).hasSize(1);
        assertThat(locationPlatform.fetchCount).isEqualTo(firstFetchCount);
    }

    @Test
    void searchTextQueryWorks() {
        locationPlatform.places = List.of(
            new Place("g1", "Pizza Express", "London", new Coordinates(51.5, -0.1),
                List.of("restaurant"), 3.8, 500, null, null, null)
        );

        var results = orchestrator.search(
            new KnowledgeQuery.TextSearch("pizza"),
            "tenant-1");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Pizza Express");
    }

    @Test
    void promoteFlowDelegatesToPromoter() {
        locationPlatform.places = List.of(
            new Place("g1", "Ondine", "Edinburgh", new Coordinates(55.9533, -3.1883),
                List.of("restaurant"), 4.5, 200, null, null, null)
        );

        var results = orchestrator.search(
            new KnowledgeQuery.CategorySearch("restaurant",
                new Coordinates(55.9533, -3.1883), 1000),
            "tenant-1");

        var result = orchestrator.promote(
            new PromotionRequest(results.get(0).id(), "tenant-1", null));

        assertThat(result.mindMapNodeId()).isNotNull();
        assertThat(result.created()).isTrue();
    }

    static class StubLocationPlatform implements LocationPlatform {
        List<Place> places = List.of();
        int fetchCount = 0;

        @Override public String id() { return "stub"; }
        @Override public boolean supports(Class<?> capability) {
            return PlaceSearch.class.isAssignableFrom(capability);
        }

        @Override
        public PlaceSearch placeSearch(String userId) {
            return new PlaceSearch() {
                @Override
                public Page<Place> searchByText(String query, PageRequest pagination) {
                    fetchCount++;
                    return new Page<>(places, null, false);
                }
                @Override
                public Page<Place> searchNearby(Coordinates location, int radiusMeters,
                                                 PageRequest pagination) {
                    fetchCount++;
                    return new Page<>(places, null, false);
                }
                @Override
                public Page<Place> searchByCategory(String category, Coordinates location,
                                                     int radiusMeters, PageRequest pagination) {
                    fetchCount++;
                    return new Page<>(places, null, false);
                }
            };
        }

        @Override public PlaceDetails placeDetails(String userId) { return null; }
        @Override public Geocoding geocoding(String userId) { return null; }
        @Override public Directions directions(String userId) { return null; }
    }
}
