package io.casehub.neocortex.knowledge.cache;

import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.CacheFilter;
import io.casehub.neocortex.knowledge.ExpandedTerm;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.NormalizedQuery;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SpatialCacheKeyGeneratorTest {

    private static final Coordinates LONDON = new Coordinates(51.5074, -0.1278);
    private final SpatialCacheKeyGenerator generator = new SpatialCacheKeyGenerator(6);

    @Test
    void textSearchProducesTextPrefixedKey() {
        NormalizedQuery result = generator.generate(
            new KnowledgeQuery.TextSearch("Coffee Shop", "location"), Map.of());

        assertTrue(result.cacheKey().startsWith("TEXT:"));
        assertTrue(result.cacheKey().contains("coffee shop"));
    }

    @Test
    void textSearchAppliesExpansions() {
        var expansions = Map.of("coffee",
            new ExpandedTerm("café", Set.of("café", "coffee")));

        NormalizedQuery result = generator.generate(
            new KnowledgeQuery.TextSearch("coffee shop", "location"), expansions);

        assertTrue(result.cacheKey().contains("café"));
        assertFalse(result.cacheKey().contains("coffee"));
    }

    @Test
    void nearbySearchIncludesGeohashAndRadius() {
        NormalizedQuery result = generator.generate(
            new KnowledgeQuery.NearbySearch(LONDON, 500, CacheFilter.none()), Map.of());

        assertTrue(result.cacheKey().startsWith("NEARBY:"));
        assertTrue(result.cacheKey().contains(":500"));
    }

    @Test
    void categorySearchIncludesCategoryAndGeohash() {
        NormalizedQuery result = generator.generate(
            new KnowledgeQuery.CategorySearch("restaurant", LONDON, 1000), Map.of());

        assertTrue(result.cacheKey().startsWith("CATEGORY:restaurant:"));
        assertTrue(result.cacheKey().contains(":1000"));
    }

    @Test
    void categorySearchAppliesExpansions() {
        var expansions = Map.of("restaurant",
            new ExpandedTerm("eatery", Set.of("eatery", "restaurant")));

        NormalizedQuery result = generator.generate(
            new KnowledgeQuery.CategorySearch("restaurant", LONDON, 1000), expansions);

        assertTrue(result.cacheKey().contains("eatery"));
    }

    @Test
    void textSearchNormalizesUnicode() {
        NormalizedQuery result = generator.generate(
            new KnowledgeQuery.TextSearch("  Café  SHOP  ", "location"), Map.of());

        assertEquals("TEXT:café shop", result.cacheKey());
    }
}
