package io.casehub.neocortex.knowledge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NormalizerCompositionTest {

    @Test
    void chainPipelinesOutputThroughAllNormalizers() {
        TermNormalizer stemmer = (term, domain) -> {
            if ("running".equals(term)) {
                return new ExpandedTerm("run", Set.of("run", "running"));
            }
            return ExpandedTerm.passthrough(term);
        };
        TermNormalizer synonymExpander = (term, domain) -> {
            if ("run".equals(term)) {
                return new ExpandedTerm("run", Set.of("run", "jog", "sprint"));
            }
            return ExpandedTerm.passthrough(term);
        };

        Map<String, ExpandedTerm> result = composeNormalize(
            new KnowledgeQuery.TextSearch("running fast", "documents"),
            List.of(stemmer, synonymExpander));

        assertNotNull(result.get("running"));
        assertEquals("run", result.get("running").canonical());
        assertTrue(result.get("running").variants().containsAll(
            Set.of("run", "running", "jog", "sprint")),
            "Expected all variants from both normalizers, got: " + result.get("running").variants());
    }

    @Test
    void firstMatchWinsIsReplaced() {
        TermNormalizer stemmer = (term, domain) -> {
            if ("running".equals(term)) {
                return new ExpandedTerm("run", Set.of("run", "running"));
            }
            return ExpandedTerm.passthrough(term);
        };
        TermNormalizer synonymExpander = (term, domain) -> {
            if ("run".equals(term)) {
                return new ExpandedTerm("run", Set.of("run", "jog"));
            }
            return ExpandedTerm.passthrough(term);
        };

        Map<String, ExpandedTerm> result = composeNormalize(
            new KnowledgeQuery.TextSearch("running", "documents"),
            List.of(stemmer, synonymExpander));

        assertTrue(result.get("running").variants().contains("jog"),
            "Second normalizer should have run on stemmed output");
    }

    @Test
    void singleNormalizerStillWorks() {
        TermNormalizer synonymExpander = (term, domain) -> {
            if ("headphones".equals(term)) {
                return new ExpandedTerm("earphone", Set.of("earphone", "headphone"));
            }
            return ExpandedTerm.passthrough(term);
        };

        Map<String, ExpandedTerm> result = composeNormalize(
            new KnowledgeQuery.TextSearch("buy headphones", "commerce"),
            List.of(synonymExpander));

        assertEquals("earphone", result.get("headphones").canonical());
    }

    @Test
    void emptyChainProducesNoExpansions() {
        Map<String, ExpandedTerm> result = composeNormalize(
            new KnowledgeQuery.TextSearch("test query", "general"),
            List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void passthroughNormalizersAreSkipped() {
        TermNormalizer noop = (term, domain) -> ExpandedTerm.passthrough(term);
        TermNormalizer real = (term, domain) -> {
            if ("car".equals(term)) {
                return new ExpandedTerm("automobile", Set.of("automobile", "car", "vehicle"));
            }
            return ExpandedTerm.passthrough(term);
        };

        Map<String, ExpandedTerm> result = composeNormalize(
            new KnowledgeQuery.TextSearch("buy car", "commerce"),
            List.of(noop, real));

        assertEquals("automobile", result.get("car").canonical());
    }

    static Map<String, ExpandedTerm> composeNormalize(
            KnowledgeQuery query, List<TermNormalizer> chain) {
        if (!(query instanceof KnowledgeQuery.TextSearch textSearch)) {
            return Map.of();
        }
        Map<String, ExpandedTerm> expansions = new LinkedHashMap<>();
        String text = textSearch.query().toLowerCase().strip();
        String[] tokens = text.split("\\s+");
        for (String token : tokens) {
            String current = token;
            Set<String> allVariants = new LinkedHashSet<>();
            allVariants.add(token);
            for (TermNormalizer normalizer : chain) {
                ExpandedTerm expanded = normalizer.normalize(current, query.domain());
                if (!current.equals(expanded.canonical()) || expanded.variants().size() > 1) {
                    current = expanded.canonical();
                    allVariants.addAll(expanded.variants());
                }
            }
            if (!token.equals(current) || allVariants.size() > 1) {
                expansions.put(token, new ExpandedTerm(current, allVariants));
            }
        }
        return expansions;
    }
}
