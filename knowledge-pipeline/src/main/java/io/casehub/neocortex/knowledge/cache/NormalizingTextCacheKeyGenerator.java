package io.casehub.neocortex.knowledge.cache;

import io.casehub.neocortex.knowledge.CacheKeyGenerator;
import io.casehub.neocortex.knowledge.ExpandedTerm;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.NormalizedQuery;

import java.text.Normalizer;
import java.util.Map;

public class NormalizingTextCacheKeyGenerator implements CacheKeyGenerator {

    public static final NormalizingTextCacheKeyGenerator INSTANCE =
        new NormalizingTextCacheKeyGenerator();

    @Override
    public NormalizedQuery generate(KnowledgeQuery query, Map<String, ExpandedTerm> expansions) {
        if (!(query instanceof KnowledgeQuery.TextSearch t)) {
            String key = query.toString().toLowerCase().strip();
            return new NormalizedQuery(query, key);
        }
        String normalized = Normalizer.normalize(t.query(), Normalizer.Form.NFKC)
            .toLowerCase().strip().replaceAll("\\s+", " ");
        if (expansions != null && !expansions.isEmpty()) {
            for (var entry : expansions.entrySet()) {
                String term = entry.getKey().toLowerCase().strip();
                normalized = normalized.replace(term, entry.getValue().canonical());
            }
        }
        return new NormalizedQuery(query, "TEXT:" + normalized);
    }
}
