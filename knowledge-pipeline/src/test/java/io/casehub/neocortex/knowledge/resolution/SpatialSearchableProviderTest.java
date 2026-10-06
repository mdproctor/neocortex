package io.casehub.neocortex.knowledge.resolution;

import io.casehub.connectors.Page;
import io.casehub.connectors.PageRequest;
import io.casehub.connectors.location.model.Coordinates;
import io.casehub.connectors.location.model.Place;
import io.casehub.connectors.location.model.PriceLevel;
import io.casehub.connectors.location.spi.LocationPlatform;
import io.casehub.connectors.location.spi.NoOpLocationPlatform;
import io.casehub.neocortex.knowledge.CacheFilter;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.PipelinePage;
import io.casehub.neocortex.knowledge.PipelinePageRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpatialSearchableProviderTest {

    private static final Duration TTL = Duration.ofHours(1);
    private static final Coordinates LONDON = new Coordinates(51.5074, -0.1278);

    @Test
    void domainIsLocation() {
        var provider = new SpatialSearchableProvider(new NoOpLocationPlatform(), TTL);
        assertEquals("location", provider.domain());
    }

    @Test
    void idDelegatesToPlatform() {
        var platform = stubPlatform("google-maps", List.of());
        var provider = new SpatialSearchableProvider(platform, TTL);
        assertEquals("google-maps", provider.id());
    }

    @Test
    void supportsTextSearch() {
        var provider = new SpatialSearchableProvider(new NoOpLocationPlatform(), TTL);
        assertTrue(provider.supports(new KnowledgeQuery.TextSearch("coffee", "location")));
    }

    @Test
    void supportsNearbySearch() {
        var provider = new SpatialSearchableProvider(new NoOpLocationPlatform(), TTL);
        assertTrue(provider.supports(new KnowledgeQuery.NearbySearch(LONDON, 500, CacheFilter.none())));
    }

    @Test
    void supportsCategorySearch() {
        var provider = new SpatialSearchableProvider(new NoOpLocationPlatform(), TTL);
        assertTrue(provider.supports(new KnowledgeQuery.CategorySearch("restaurant", LONDON, 1000)));
    }

    @Test
    void textSearchConvertesPlacesToEntities() {
        var place = testPlace("p1", "Good Coffee", LONDON, "cafe",
            "+44123", "http://good.coffee", "10 High St", PriceLevel.MODERATE, 4.5);
        var platform = stubPlatform("test-provider", List.of(place));
        var provider = new SpatialSearchableProvider(platform, TTL);

        PipelinePage<CachedEntity> result = provider.search(
            new KnowledgeQuery.TextSearch("coffee", "location"),
            PipelinePageRequest.first(20));

        assertEquals(1, result.items().size());
        CachedEntity entity = result.items().get(0);
        assertEquals("Good Coffee", entity.name());
        assertEquals(LONDON, entity.coordinates());
        assertEquals("cafe", entity.category());
        assertEquals("test-provider", entity.source());
        assertEquals("p1", entity.externalId());
        assertEquals("location", entity.domain());
        assertFalse(entity.hasDetail());
        assertNotNull(entity.fetchedAt());
        assertNotNull(entity.expiresAt());

        assertEquals("+44123", entity.properties().get("phone"));
        assertEquals("http://good.coffee", entity.properties().get("website"));
        assertEquals("10 High St", entity.properties().get("address"));
        assertEquals("MODERATE", entity.properties().get("priceLevel"));
        assertEquals("4.5", entity.properties().get("rating"));
    }

    @Test
    void nearbySearchDelegatesToPlatform() {
        var place = testPlace("p2", "Nearby Cafe", LONDON, "cafe",
            null, null, null, null, null);
        var platform = stubPlatform("test", List.of(place));
        var provider = new SpatialSearchableProvider(platform, TTL);

        PipelinePage<CachedEntity> result = provider.search(
            new KnowledgeQuery.NearbySearch(LONDON, 500, CacheFilter.none()),
            PipelinePageRequest.first(20));

        assertEquals(1, result.items().size());
        assertEquals("Nearby Cafe", result.items().get(0).name());
    }

    @Test
    void categorySearchDelegatesToPlatform() {
        var place = testPlace("p3", "Restaurant A", LONDON, "restaurant",
            null, null, null, null, null);
        var platform = stubPlatform("test", List.of(place));
        var provider = new SpatialSearchableProvider(platform, TTL);

        PipelinePage<CachedEntity> result = provider.search(
            new KnowledgeQuery.CategorySearch("restaurant", LONDON, 1000),
            PipelinePageRequest.first(20));

        assertEquals(1, result.items().size());
        assertEquals("Restaurant A", result.items().get(0).name());
    }

    @Test
    void paginationBridgesCorrectly() {
        var places = List.of(
            testPlace("p1", "Place 1", LONDON, null, null, null, null, null, null),
            testPlace("p2", "Place 2", LONDON, null, null, null, null, null, null));
        var platform = stubPlatformWithPagination("test", places, "next-cursor-123", true);
        var provider = new SpatialSearchableProvider(platform, TTL);

        PipelinePage<CachedEntity> result = provider.search(
            new KnowledgeQuery.TextSearch("test", "location"),
            PipelinePageRequest.first(2));

        assertEquals(2, result.items().size());
        assertEquals("next-cursor-123", result.nextCursor());
        assertTrue(result.hasMore());
    }

    @Test
    void nullOptionalFieldsOmittedFromProperties() {
        var place = testPlace("p1", "Minimal", LONDON, null,
            null, null, null, null, null);
        var platform = stubPlatform("test", List.of(place));
        var provider = new SpatialSearchableProvider(platform, TTL);

        PipelinePage<CachedEntity> result = provider.search(
            new KnowledgeQuery.TextSearch("test", "location"),
            PipelinePageRequest.first(20));

        CachedEntity entity = result.items().get(0);
        assertFalse(entity.properties().containsKey("phone"));
        assertFalse(entity.properties().containsKey("website"));
        assertFalse(entity.properties().containsKey("address"));
        assertFalse(entity.properties().containsKey("priceLevel"));
        assertFalse(entity.properties().containsKey("rating"));
    }

    @Test
    void entityIdIsDeterministic() {
        var place = testPlace("ext-123", "Test", LONDON, null,
            null, null, null, null, null);
        var platform = stubPlatform("provider-a", List.of(place));
        var provider = new SpatialSearchableProvider(platform, TTL);

        var r1 = provider.search(new KnowledgeQuery.TextSearch("test", "location"),
            PipelinePageRequest.first(20));
        var r2 = provider.search(new KnowledgeQuery.TextSearch("test", "location"),
            PipelinePageRequest.first(20));

        assertEquals(r1.items().get(0).id(), r2.items().get(0).id());
    }

    private static Place testPlace(String id, String name, Coordinates location,
                                    String type, String phone, String website,
                                    String address, PriceLevel priceLevel, Double rating) {
        return new Place(id, name, address, location,
            type != null ? List.of(type) : List.of(),
            rating, null, phone, website, priceLevel);
    }

    private static LocationPlatform stubPlatform(String id, List<Place> results) {
        return stubPlatformWithPagination(id, results, null, false);
    }

    private static LocationPlatform stubPlatformWithPagination(
            String id, List<Place> results, String nextCursor, boolean hasMore) {
        return new NoOpLocationPlatform() {
            @Override
            public String id() { return id; }

            @Override
            public boolean supports(Class<?> capability) {
                return capability == PlaceSearch.class;
            }

            @Override
            public PlaceSearch placeSearch(String tenantId) {
                return new PlaceSearch() {
                    @Override
                    public Page<Place> searchByText(String query, PageRequest page) {
                        return new Page<>(results, nextCursor, hasMore);
                    }

                    @Override
                    public Page<Place> searchNearby(Coordinates center, int radiusMeters,
                                                     PageRequest page) {
                        return new Page<>(results, nextCursor, hasMore);
                    }

                    @Override
                    public Page<Place> searchByCategory(String category, Coordinates center,
                                                         int radiusMeters, PageRequest page) {
                        return new Page<>(results, nextCursor, hasMore);
                    }
                };
            }
        };
    }
}
