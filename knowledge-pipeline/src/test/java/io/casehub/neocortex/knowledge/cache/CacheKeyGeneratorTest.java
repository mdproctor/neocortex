package io.casehub.neocortex.knowledge.cache;

import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.CacheFilter;
import io.casehub.neocortex.knowledge.ExpandedTerm;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.TermNormalizer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeyGeneratorTest {

    @Test
    void textSearchNormalizesCase() {
        var q = new KnowledgeQuery.TextSearch("Find Italian  Restaurants", null);
        var nq = CacheKeyGenerator.generate(q, 6);
        assertThat(nq.cacheKey()).isEqualTo("TEXT:find italian restaurants");
    }

    @Test
    void textSearchTrimsWhitespace() {
        var q = new KnowledgeQuery.TextSearch("  pizza  near me  ", null);
        var nq = CacheKeyGenerator.generate(q, 6);
        assertThat(nq.cacheKey()).isEqualTo("TEXT:pizza near me");
    }

    @Test
    void nearbySearchRoundsToGeohash() {
        var q1 = new KnowledgeQuery.NearbySearch(
            new Coordinates(51.5317, -0.1240), 1000, CacheFilter.none());
        var q2 = new KnowledgeQuery.NearbySearch(
            new Coordinates(51.5320, -0.1235), 1000, CacheFilter.none());
        assertThat(CacheKeyGenerator.generate(q1, 6).cacheKey())
            .isEqualTo(CacheKeyGenerator.generate(q2, 6).cacheKey());
    }

    @Test
    void nearbySearchIncludesRadius() {
        var q = new KnowledgeQuery.NearbySearch(
            new Coordinates(51.5, -0.1), 1000, CacheFilter.none());
        assertThat(CacheKeyGenerator.generate(q, 6).cacheKey())
            .endsWith(":1000");
    }

    @Test
    void categorySearchIncludesCategoryInKey() {
        var q = new KnowledgeQuery.CategorySearch(
            "italian", new Coordinates(51.5, -0.1), 1000);
        var nq = CacheKeyGenerator.generate(q, 6);
        assertThat(nq.cacheKey()).startsWith("CATEGORY:italian:");
    }

    @Test
    void categorySearchNormalizesCategory() {
        var q = new KnowledgeQuery.CategorySearch(
            " ITALIAN ", new Coordinates(51.5, -0.1), 1000);
        var nq = CacheKeyGenerator.generate(q, 6);
        assertThat(nq.cacheKey()).startsWith("CATEGORY:italian:");
    }

    @Test
    void differentRadiiProduceDifferentKeys() {
        var q1 = new KnowledgeQuery.NearbySearch(
            new Coordinates(51.5, -0.1), 1000, CacheFilter.none());
        var q2 = new KnowledgeQuery.NearbySearch(
            new Coordinates(51.5, -0.1), 5000, CacheFilter.none());
        assertThat(CacheKeyGenerator.generate(q1, 6).cacheKey())
            .isNotEqualTo(CacheKeyGenerator.generate(q2, 6).cacheKey());
    }

    @Test
    void normalizedQueryPreservesOriginalQuery() {
        var q = new KnowledgeQuery.TextSearch("pizza", null);
        var nq = CacheKeyGenerator.generate(q, 6);
        assertThat(nq.query()).isEqualTo(q);
    }

    @Test
    void synonymsProduceSameCacheKeyWhenNormalized() {
        TermNormalizer normalizer = (term, domain) -> {
            if ("eatery".equals(term) || "restaurant".equals(term)) {
                return new ExpandedTerm("restaurant", Set.of("restaurant", "eatery", "eating house"));
            }
            return ExpandedTerm.passthrough(term);
        };

        var q1 = new KnowledgeQuery.TextSearch("italian eatery", "location");
        var q2 = new KnowledgeQuery.TextSearch("italian restaurant", "location");

        var r1 = CacheKeyGenerator.generate(q1, 6, normalizer);
        var r2 = CacheKeyGenerator.generate(q2, 6, normalizer);

        assertThat(r1.normalizedQuery().cacheKey()).isEqualTo(r2.normalizedQuery().cacheKey());
    }

    @Test
    void normalizationResultCarriesExpansions() {
        TermNormalizer normalizer = (term, domain) ->
                                            new ExpandedTerm("doll", Set.of("doll", "dolly"));

        var q      = new KnowledgeQuery.TextSearch("dolly", "commerce");
        var result = CacheKeyGenerator.generate(q, 6, normalizer);

        assertThat(result.expansions()).containsKey("dolly");
        assertThat(result.expansions().get("dolly").canonical()).isEqualTo("doll");
    }

    @Test
    void backwardCompatibleGenerateStillWorks() {
        var q  = new KnowledgeQuery.TextSearch("pizza", null);
        var nq = CacheKeyGenerator.generate(q, 6);
        assertThat(nq.cacheKey()).isEqualTo("TEXT:pizza");
    }

    @Test
    void categorySearchNormalizesWithPlaceDomain() {
        TermNormalizer normalizer = (term, domain) -> {
            assertThat(domain).isEqualTo("location");
            return new ExpandedTerm("cafe", Set.of("cafe", "café", "coffee shop"));
        };

        var q      = new KnowledgeQuery.CategorySearch("café", new Coordinates(51.5, -0.1), 1000);
        var result = CacheKeyGenerator.generate(q, 6, normalizer);

        assertThat(result.normalizedQuery().cacheKey()).startsWith("CATEGORY:cafe:");
    }

    @Test
    void nearbySearchUnaffectedByNormalizer() {
        TermNormalizer normalizer = (term, domain) -> {
            throw new AssertionError("Should not be called for NearbySearch");
        };

        var q      = new KnowledgeQuery.NearbySearch(new Coordinates(51.5, -0.1), 1000, CacheFilter.none());
        var result = CacheKeyGenerator.generate(q, 6, normalizer);

        assertThat(result.normalizedQuery().cacheKey()).startsWith("NEARBY:");
    }

    @Test
    void passthroughNormalizerProducesNoExpansions() {
        TermNormalizer normalizer = (term, domain) -> ExpandedTerm.passthrough(term);

        var q      = new KnowledgeQuery.TextSearch("starbucks", "location");
        var result = CacheKeyGenerator.generate(q, 6, normalizer);

        assertThat(result.normalizedQuery().cacheKey()).isEqualTo("TEXT:starbucks");
        assertThat(result.expansions()).isEmpty();
    }

}
