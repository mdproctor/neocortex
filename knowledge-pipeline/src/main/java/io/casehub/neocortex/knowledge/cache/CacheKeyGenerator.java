package io.casehub.neocortex.knowledge.cache;

import io.casehub.neocortex.knowledge.ExpandedTerm;
import io.casehub.neocortex.knowledge.KnowledgeDomain;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.NormalizedQuery;
import io.casehub.neocortex.knowledge.SpatialBucket;
import io.casehub.neocortex.knowledge.TermNormalizer;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CacheKeyGenerator {

    private CacheKeyGenerator() {}

    public static NormalizedQuery generate(KnowledgeQuery query, int geohashPrecision) {
        String key = switch (query) {
            case KnowledgeQuery.TextSearch t ->
                "TEXT:" + normalizeText(t.query());
            case KnowledgeQuery.NearbySearch n ->
                "NEARBY:" + SpatialBucket.encode(
                    n.center().lat(), n.center().lng(), geohashPrecision)
                    + ":" + n.radiusMeters();
            case KnowledgeQuery.CategorySearch c ->
                "CATEGORY:" + c.category().toLowerCase().strip()
                    + ":" + SpatialBucket.encode(
                        c.center().lat(), c.center().lng(), geohashPrecision)
                    + ":" + c.radiusMeters();
            default -> throw new UnsupportedOperationException("Unsupported query type: " + query.getClass().getName());
        };
        return new NormalizedQuery(query, key);
    }

    public static NormalizationResult generate(KnowledgeQuery query, int geohashPrecision,
                                               TermNormalizer normalizer) {
        Map<String, ExpandedTerm> expansions = new LinkedHashMap<>();

        String key = switch (query) {
            case KnowledgeQuery.TextSearch t -> {
                String domain     = t.domain();
                String normalized = normalizeAndExpand(t.query(), domain, normalizer, expansions);
                yield "TEXT:" + normalized;
            }
            case KnowledgeQuery.NearbySearch n -> "NEARBY:" + SpatialBucket.encode(
                    n.center().lat(), n.center().lng(), geohashPrecision)
                                                  + ":" + n.radiusMeters();
            case KnowledgeQuery.CategorySearch c -> {
                ExpandedTerm expanded = normalizer.normalize(
                        c.category().toLowerCase().strip(), KnowledgeDomain.PLACE);
                if (!c.category().toLowerCase().strip().equals(expanded.canonical())
                    || expanded.variants().size() > 1) {
                    expansions.put(c.category(), expanded);
                }
                yield "CATEGORY:" + expanded.canonical()
                      + ":" + SpatialBucket.encode(
                        c.center().lat(), c.center().lng(), geohashPrecision)
                      + ":" + c.radiusMeters();
            }
            default -> throw new UnsupportedOperationException("Unsupported query type: " + query.getClass().getName());
        };

        return new NormalizationResult(
                new NormalizedQuery(query, key),
                expansions);
    }

    private static String normalizeAndExpand(String text, String domain,
                                             TermNormalizer normalizer,
                                             Map<String, ExpandedTerm> expansions) {
        String normalized = normalizeText(text);
        if (domain == null) {
            return normalized;
        }
        String[]      tokens    = normalized.split(" ");
        StringBuilder canonical = new StringBuilder();

        int i = 0;
        while (i < tokens.length) {
            if (i > 0) {canonical.append(' ');}

            if (i + 1 < tokens.length) {
                String       bigram       = tokens[i] + " " + tokens[i + 1];
                ExpandedTerm bigramResult = normalizer.normalize(bigram, domain);
                if (!bigram.equals(bigramResult.canonical()) || bigramResult.variants().size() > 1) {
                    expansions.put(bigram, bigramResult);
                    canonical.append(bigramResult.canonical());
                    i += 2;
                    continue;
                }
            }

            ExpandedTerm result = normalizer.normalize(tokens[i], domain);
            if (!tokens[i].equals(result.canonical()) || result.variants().size() > 1) {
                expansions.put(tokens[i], result);
            }
            canonical.append(result.canonical());
            i++;
        }

        return canonical.toString();
    }


    public static String normalizeText(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .toLowerCase().strip().replaceAll("\\s+", " ");
    }
}
