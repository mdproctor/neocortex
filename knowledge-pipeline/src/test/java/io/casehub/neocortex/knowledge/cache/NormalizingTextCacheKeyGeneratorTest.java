package io.casehub.neocortex.knowledge.cache;

import io.casehub.neocortex.knowledge.CacheKeyGenerator;
import io.casehub.neocortex.knowledge.ExpandedTerm;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.NormalizedQuery;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NormalizingTextCacheKeyGeneratorTest {

    private final CacheKeyGenerator generator = new NormalizingTextCacheKeyGenerator();

    @Test
    void textSearchWithNoExpansions() {
        var query = new KnowledgeQuery.TextSearch("headphones", "commerce");
        NormalizedQuery result = generator.generate(query, Map.of());
        assertEquals("TEXT:headphones", result.cacheKey());
    }

    @Test
    void textSearchSubstitutesCanonicalForm() {
        var query = new KnowledgeQuery.TextSearch("wireless headphones", "commerce");
        var expansions = Map.of(
            "headphones", new ExpandedTerm("earphone", Set.of("earphone", "headphone"))
        );
        NormalizedQuery result = generator.generate(query, expansions);
        assertEquals("TEXT:wireless earphone", result.cacheKey());
    }

    @Test
    void synonymQueriesProduceSameCacheKey() {
        var q1 = new KnowledgeQuery.TextSearch("headphones", "commerce");
        var q2 = new KnowledgeQuery.TextSearch("earphones", "commerce");
        var exp1 = Map.of("headphones", new ExpandedTerm("earphone", Set.of("earphone", "headphone")));
        var exp2 = Map.of("earphones", new ExpandedTerm("earphone", Set.of("earphone", "earphones")));
        assertEquals(
            generator.generate(q1, exp1).cacheKey(),
            generator.generate(q2, exp2).cacheKey()
        );
    }

    @Test
    void nullExpansionsProducesPlainKey() {
        var query = new KnowledgeQuery.TextSearch("test query", "documents");
        NormalizedQuery result = generator.generate(query, null);
        assertEquals("TEXT:test query", result.cacheKey());
    }

    @Test
    void normalizesUnicodeAndWhitespace() {
        var query = new KnowledgeQuery.TextSearch("  Café   Latte  ", "commerce");
        NormalizedQuery result = generator.generate(query, Map.of());
        assertTrue(result.cacheKey().startsWith("TEXT:"));
        assertFalse(result.cacheKey().contains("  "));
    }
}
