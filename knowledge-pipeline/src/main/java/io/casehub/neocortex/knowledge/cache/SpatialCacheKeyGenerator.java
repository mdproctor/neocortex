package io.casehub.neocortex.knowledge.cache;

import io.casehub.neocortex.knowledge.CacheKeyGenerator;
import io.casehub.neocortex.knowledge.ExpandedTerm;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.NormalizedQuery;
import io.casehub.neocortex.knowledge.SpatialBucket;

import java.text.Normalizer;
import java.util.Map;

public final class SpatialCacheKeyGenerator implements CacheKeyGenerator {

    private final int geohashPrecision;

    public SpatialCacheKeyGenerator(int geohashPrecision) {
        this.geohashPrecision = geohashPrecision;
    }

    @Override
    public NormalizedQuery generate(KnowledgeQuery query, Map<String, ExpandedTerm> expansions) {
        String key = switch (query) {
            case KnowledgeQuery.TextSearch t -> {
                String normalized = normalizeText(t.query());
                if (expansions != null && !expansions.isEmpty()) {
                    for (var entry : expansions.entrySet()) {
                        String term = entry.getKey().toLowerCase().strip();
                        normalized = normalized.replace(term, entry.getValue().canonical());
                    }
                }
                yield "TEXT:" + normalized;
            }
            case KnowledgeQuery.NearbySearch n ->
                "NEARBY:" + SpatialBucket.encode(
                    n.center().lat(), n.center().lng(), geohashPrecision)
                    + ":" + n.radiusMeters();
            case KnowledgeQuery.CategorySearch c -> {
                String cat = c.category().toLowerCase().strip();
                if (expansions != null && expansions.containsKey(c.category())) {
                    cat = expansions.get(c.category()).canonical();
                }
                yield "CATEGORY:" + cat
                    + ":" + SpatialBucket.encode(
                        c.center().lat(), c.center().lng(), geohashPrecision)
                    + ":" + c.radiusMeters();
            }
            default -> throw new UnsupportedOperationException(
                "Unsupported query type: " + query.getClass().getName());
        };
        return new NormalizedQuery(query, key);
    }

    private static String normalizeText(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .toLowerCase().strip().replaceAll("\\s+", " ");
    }
}
