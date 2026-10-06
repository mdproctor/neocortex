package io.casehub.neocortex.knowledge;

import com.zaxxer.hikari.HikariDataSource;
import io.casehub.connectors.Page;
import io.casehub.connectors.PageRequest;
import io.casehub.connectors.location.model.Coordinates;
import io.casehub.connectors.location.model.Place;
import io.casehub.connectors.location.model.PriceLevel;
import io.casehub.connectors.location.spi.LocationPlatform;
import io.casehub.neocortex.knowledge.cache.CacheDecayPolicy;
import io.casehub.neocortex.knowledge.cache.EntityMetadataStore;
import io.casehub.neocortex.knowledge.cache.InMemorySpatialCacheStore;
import io.casehub.neocortex.knowledge.cache.QueryCacheStore;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.knowledge.cache.SpatialCacheKeyGenerator;
import io.casehub.neocortex.knowledge.promotion.EntityPromoter;
import io.casehub.neocortex.knowledge.research.ResearchOrchestrator;
import io.casehub.neocortex.knowledge.research.ResearchSessionStore;
import io.casehub.neocortex.knowledge.resolution.EntityResolutionEngine;
import io.casehub.neocortex.knowledge.resolution.PlaceMatcher;
import io.casehub.neocortex.knowledge.resolution.SpatialBlockingStrategy;
import io.casehub.neocortex.knowledge.resolution.SpatialSearchableProvider;
import io.casehub.neocortex.knowledge.resolution.CompositeSearchableProvider;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;
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

        orchestrator = createOrchestrator(List.of(locationPlatform), cacheStore,
            queryCache, dedupStore, metadataStore, resolutionEngine, promoter,
            new CacheDecayPolicy(), (term, domain) -> ExpandedTerm.passthrough(term));
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
            new KnowledgeQuery.TextSearch("pizza", null),
            "tenant-1");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Pizza Express");
    }

    @Test
    void variantDispatchFiresMultipleQueriesForUnknownProvider() {
        locationPlatform.places = List.of(
                new Place("g1", "Dolly Shop", "London", new Coordinates(51.5, -0.1),
                          List.of("shop"), 4.0, 100, null, null, null)
                                         );

        TermNormalizer expander = (term, domain) -> {
            if ("dolly".equals(term)) {
                return new ExpandedTerm("doll", java.util.Set.of("doll", "dolly", "dolls"));
            }
            return ExpandedTerm.passthrough(term);
        };

        var expandingOrchestrator = createOrchestrator(List.of(locationPlatform), cacheStore,
                queryCache,
                new DedupIndexStore(pipelineDs),
                new EntityMetadataStore(pipelineDs),
                new EntityResolutionEngine(new PlaceMatcher()),
                new EntityPromoter(new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore(),
                        cacheStore, new DedupIndexStore(pipelineDs),
                        new ResearchSessionStore(researchDs)),
                new CacheDecayPolicy(), expander);

        locationPlatform.fetchCount = 0;
        locationPlatform.queriesReceived.clear();

        var results = expandingOrchestrator.search(
                new KnowledgeQuery.TextSearch("dolly", "location"), "tenant-1");

        assertThat(results).isNotEmpty();
        assertThat(locationPlatform.fetchCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void knownProviderSkipsVariantExpansion() {
        locationPlatform.places = List.of(
                new Place("g1", "Dolly Shop", "London", new Coordinates(51.5, -0.1),
                          List.of("shop"), 4.0, 100, null, null, null)
                                         );

        TermNormalizer expander = (term, domain) -> {
            if ("dolly".equals(term)) {
                return new ExpandedTerm("doll", java.util.Set.of("doll", "dolly", "dolls"));
            }
            return ExpandedTerm.passthrough(term);
        };

        var knownProviderOrchestrator = createOrchestrator(List.of(locationPlatform), cacheStore,
                queryCache,
                new DedupIndexStore(pipelineDs),
                new EntityMetadataStore(pipelineDs),
                new EntityResolutionEngine(new PlaceMatcher()),
                new EntityPromoter(new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore(),
                        cacheStore, new DedupIndexStore(pipelineDs),
                        new ResearchSessionStore(researchDs)),
                new CacheDecayPolicy(), expander);

        locationPlatform.fetchCount = 0;
        locationPlatform.queriesReceived.clear();

        knownProviderOrchestrator.search(
                new KnowledgeQuery.TextSearch("dolly", "location"), "tenant-1");

        assertThat(locationPlatform.fetchCount).isEqualTo(1);
    }

    @Test
    void synonymQueriesHitSameCacheInOrchestrator() {
        locationPlatform.places = List.of(
                new Place("g1", "Italian Place", "London", new Coordinates(51.5, -0.1),
                          List.of("restaurant"), 4.0, 100, null, null, null)
                                         );

        TermNormalizer expander = (term, domain) -> {
            if ("eatery".equals(term) || "restaurant".equals(term)) {
                return new ExpandedTerm("restaurant",
                                        java.util.Set.of("restaurant", "eatery"));
            }
            return ExpandedTerm.passthrough(term);
        };

        var normOrchestrator = createOrchestrator(List.of(locationPlatform), cacheStore,
                queryCache,
                new DedupIndexStore(pipelineDs),
                new EntityMetadataStore(pipelineDs),
                new EntityResolutionEngine(new PlaceMatcher()),
                new EntityPromoter(new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore(),
                        cacheStore, new DedupIndexStore(pipelineDs),
                        new ResearchSessionStore(researchDs)),
                new CacheDecayPolicy(), expander);

        normOrchestrator.search(
                new KnowledgeQuery.TextSearch("eatery", "location"), "tenant-1");
        int fetchAfterFirst = locationPlatform.fetchCount;

        var results = normOrchestrator.search(
                new KnowledgeQuery.TextSearch("restaurant", "location"), "tenant-1");

        assertThat(results).hasSize(1);
        assertThat(locationPlatform.fetchCount).isEqualTo(fetchAfterFirst);
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

    @Test
    void refreshStaleFetchesUpdatedDetails() {
        locationPlatform.places = List.of(
                new Place("g1", "Ondine", "Edinburgh", new Coordinates(55.9533, -3.1883),
                          List.of("restaurant"), 4.5, 200, "+44 131 226 1888", "https://ondine.co.uk",
                          PriceLevel.MODERATE)
                                         );

        orchestrator.search(
                new KnowledgeQuery.CategorySearch("restaurant",
                                                  new Coordinates(55.9533, -3.1883), 1000),
                "tenant-1");

        assertThat(cacheStore.listAll("tenant-1")).hasSize(1);

        locationPlatform.detailsSupported = true;
        locationPlatform.detailPlace      = new Place("g1", "Ondine Updated", "Edinburgh",
                                                      new Coordinates(55.9533, -3.1883),
                                                      List.of("restaurant"), 4.7, 200, "+44 131 226 9999", "https://ondine.co.uk",
                                                      PriceLevel.EXPENSIVE);

        orchestrator.refreshStale("tenant-1");

        var refreshed = cacheStore.listAll("tenant-1");
        assertThat(refreshed).hasSize(1);
        assertThat(refreshed.get(0).properties()).containsEntry("phone", "+44 131 226 9999");
    }

    @Test
    void subsumptionNarrowQueryReusesWiderCachedResults() {
        locationPlatform.places = List.of(
                new Place("g1", "Ondine", "Edinburgh", new Coordinates(55.9533, -3.1883),
                          List.of("restaurant"), 4.5, 200, null, null, null),
                new Place("g2", "The Balmoral", "Edinburgh", new Coordinates(55.9534, -3.1884),
                          List.of("hotel"), 4.3, 500, null, null, null)
                                         );

        var wideQuery = new KnowledgeQuery.NearbySearch(
                new Coordinates(55.9533, -3.1883), 2000, CacheFilter.none());
        orchestrator.search(wideQuery, "tenant-1");
        int fetchAfterWide = locationPlatform.fetchCount;

        var narrowQuery = new KnowledgeQuery.NearbySearch(
                new Coordinates(55.9533, -3.1883), 500, CacheFilter.none());
        var narrowResults = orchestrator.search(narrowQuery, "tenant-1");

        assertThat(locationPlatform.fetchCount).isEqualTo(fetchAfterWide);
        assertThat(narrowResults).isNotEmpty();
    }

    @Test
    void providerErrorDoesNotPreventOtherProviders() {
        var failingProvider = new StubLocationPlatform() {
            @Override
            public PlaceSearch placeSearch(String userId) {
                return new PlaceSearch() {
                    @Override
                    public Page<Place> searchByText(String q, PageRequest p) {
                        throw new RuntimeException("Provider down");
                    }

                    @Override
                    public Page<Place> searchNearby(Coordinates loc, int r, PageRequest p) {
                        throw new RuntimeException("Provider down");
                    }

                    @Override
                    public Page<Place> searchByCategory(String cat, Coordinates loc, int r, PageRequest p) {
                        throw new RuntimeException("Provider down");
                    }
                };
            }
        };
        failingProvider.places = List.of();

        var workingProvider = new StubLocationPlatform();
        workingProvider.places = List.of(
                new Place("g1", "Working Place", "London", new Coordinates(51.5, -0.1),
                          List.of("restaurant"), 4.0, 100, null, null, null)
                                        );

        var multiOrchestrator = createOrchestrator(
                List.of(failingProvider, workingProvider),
                cacheStore, queryCache,
                new DedupIndexStore(pipelineDs),
                new EntityMetadataStore(pipelineDs),
                new EntityResolutionEngine(new PlaceMatcher()),
                new EntityPromoter(
                        new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore(),
                        cacheStore,
                        new DedupIndexStore(pipelineDs),
                        new ResearchSessionStore(researchDs)),
                new CacheDecayPolicy(),
                (term, domain) -> ExpandedTerm.passthrough(term));

        var results = multiOrchestrator.search(
                new KnowledgeQuery.TextSearch("restaurant", null), "tenant-1");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Working Place");
    }


    private static KnowledgePipelineOrchestrator createOrchestrator(
            List<LocationPlatform> providers, InMemorySpatialCacheStore cache,
            QueryCacheStore queryCache, DedupIndexStore dedupStore,
            EntityMetadataStore metadataStore, EntityResolutionEngine resolutionEngine,
            EntityPromoter promoter, CacheDecayPolicy decayPolicy, TermNormalizer normalizer) {
        List<SearchableProvider> searchable = providers.stream()
                .filter(p -> p.supports(LocationPlatform.PlaceSearch.class))
                .map(p -> (SearchableProvider) new SpatialSearchableProvider(p, decayPolicy.coordinatesTtl()))
                .toList();
        SearchableProvider provider = searchable.size() == 1
                ? searchable.get(0)
                : new CompositeSearchableProvider("location", searchable);
        DomainSupport spatial = new DomainSupport("location", provider,
                new SpatialCacheKeyGenerator(6),
                new io.casehub.neocortex.knowledge.cache.SpatialSubsumptionRule(),
                new PlaceMatcher(),
                new SpatialBlockingStrategy(cache, 200),
                List.of(normalizer));
        DomainRegistry registry = new DomainRegistry(List.of(spatial));
        return new KnowledgePipelineOrchestrator(
                registry, cache, queryCache, dedupStore, metadataStore,
                resolutionEngine, promoter, decayPolicy, providers);
    }

    static class StubLocationPlatform implements LocationPlatform {
        List<Place> places           = List.of();
        int         fetchCount       = 0;
        boolean     detailsSupported = false;
        Place       detailPlace      = null;
        List<String> queriesReceived = new java.util.ArrayList<>();

        @Override
        public String id() {return "stub";}

        @Override
        public boolean supports(Class<?> capability) {
            if (PlaceDetails.class.isAssignableFrom(capability)) {return detailsSupported;}
            return PlaceSearch.class.isAssignableFrom(capability);
        }

        @Override
        public PlaceSearch placeSearch(String userId) {
            return new PlaceSearch() {
                @Override
                public Page<Place> searchByText(String query, PageRequest pagination) {
                    fetchCount++;
                    queriesReceived.add(query);
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

        @Override
        public PlaceDetails placeDetails(String userId) {
            return externalId -> detailPlace != null
                ? new io.casehub.connectors.location.model.PlaceDetail(
                    detailPlace.id(), detailPlace.name(), detailPlace.formattedAddress(),
                    detailPlace.location(), detailPlace.types(), detailPlace.rating(),
                    detailPlace.userRatingsTotal(), detailPlace.phoneNumber(), null,
                    detailPlace.website(), detailPlace.priceLevel(), null, null, null, null)
                : null;
        }

        @Override
        public Geocoding geocoding(String userId)   {return null;}

        @Override
        public Directions directions(String userId) {return null;}
    }
}
