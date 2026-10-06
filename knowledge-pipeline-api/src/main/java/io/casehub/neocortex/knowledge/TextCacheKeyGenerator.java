package io.casehub.neocortex.knowledge;

import java.util.Map;

public enum TextCacheKeyGenerator implements CacheKeyGenerator {
    INSTANCE;

    @Override
    public NormalizedQuery generate(KnowledgeQuery query, Map<String, ExpandedTerm> expansions) {
        String key = query.toString().toLowerCase().strip();
        return new NormalizedQuery(query, key);
    }
}
