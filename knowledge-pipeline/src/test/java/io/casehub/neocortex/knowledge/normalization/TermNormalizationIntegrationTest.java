package io.casehub.neocortex.knowledge.normalization;

import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.cache.CacheKeyGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TermNormalizationIntegrationTest {

    private static WordNetTermNormalizer normalizer;

    @BeforeAll
    static void init() throws Exception {
        normalizer = new WordNetTermNormalizer();
        normalizer.init();
    }

    @Test
    void synonymQueriesProduceSameCacheKey() {
        var q1 = new KnowledgeQuery.TextSearch("eatery", "location");
        var q2 = new KnowledgeQuery.TextSearch("restaurant", "location");

        var r1 = CacheKeyGenerator.generate(q1, 6, normalizer);
        var r2 = CacheKeyGenerator.generate(q2, 6, normalizer);

        assertThat(r1.normalizedQuery().cacheKey())
            .isEqualTo(r2.normalizedQuery().cacheKey());
    }

    @Test
    void expansionDataAvailableForVariantDispatch() {
        var q = new KnowledgeQuery.TextSearch("eatery", "location");
        var result = CacheKeyGenerator.generate(q, 6, normalizer);

        assertThat(result.expansions()).isNotEmpty();
        var expansion = result.expansions().values().iterator().next();
        assertThat(expansion.variants().size()).isGreaterThan(1);
    }

    @Test
    void nullDomainPassesThroughWithoutExpansion() {
        var q = new KnowledgeQuery.TextSearch("starbucks", null);
        var result = CacheKeyGenerator.generate(q, 6, normalizer);

        assertThat(result.normalizedQuery().cacheKey()).isEqualTo("TEXT:starbucks");
        assertThat(result.expansions()).isEmpty();
    }

    @Test
    void compoundTermProducesCanonicalCacheKey() {
        var q1 = new KnowledgeQuery.TextSearch("coffee shop", "location");
        var result = CacheKeyGenerator.generate(q1, 6, normalizer);

        assertThat(result.normalizedQuery().cacheKey()).startsWith("TEXT:");
        assertThat(result.expansions()).isNotEmpty();
    }

    @Test
    void noOpNormalizerProducesSameKeysAsLegacy() {
        var noOp = new NoOpTermNormalizer();
        var q = new KnowledgeQuery.TextSearch("pizza near me", null);

        var legacy = CacheKeyGenerator.generate(q, 6);
        var withNoOp = CacheKeyGenerator.generate(q, 6, noOp);

        assertThat(withNoOp.normalizedQuery().cacheKey())
            .isEqualTo(legacy.cacheKey());
    }
}
