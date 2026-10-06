package io.casehub.neocortex.knowledge;

import java.util.Map;

@FunctionalInterface
public interface CacheKeyGenerator {
    NormalizedQuery generate(KnowledgeQuery query, Map<String, ExpandedTerm> expansions);
}
