package io.casehub.neocortex.knowledge.resolution;

import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.PipelinePage;
import io.casehub.neocortex.knowledge.PipelinePageRequest;
import io.casehub.neocortex.knowledge.SearchableProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CompositeSearchableProviderTest {

    @Test
    void mergesResultsFromMultipleProviders() {
        var provider1 = stubProvider("p1", List.of(entity("e1", "Place 1", "p1")));
        var provider2 = stubProvider("p2", List.of(entity("e2", "Place 2", "p2")));
        var composite = new CompositeSearchableProvider("location",
            List.of(provider1, provider2));

        var result = composite.search(
            new KnowledgeQuery.TextSearch("test", "location"),
            PipelinePageRequest.first(20));

        assertEquals(2, result.items().size());
    }

    @Test
    void deduplicatesBySourceAndExternalId() {
        var shared = entity("e1", "Same Place", "p1");
        var provider1 = stubProvider("p1", List.of(shared));
        var provider2 = stubProvider("p2", List.of(shared));
        var composite = new CompositeSearchableProvider("location",
            List.of(provider1, provider2));

        var result = composite.search(
            new KnowledgeQuery.TextSearch("test", "location"),
            PipelinePageRequest.first(20));

        assertEquals(1, result.items().size());
    }

    @Test
    void skipsProvidersThatDontSupportQuery() {
        var supporting = stubProvider("p1", List.of(entity("e1", "Place", "p1")));
        var notSupporting = new SearchableProvider() {
            @Override public String domain() { return "location"; }
            @Override public String id() { return "p2"; }
            @Override public PipelinePage<CachedEntity> search(KnowledgeQuery q, PipelinePageRequest p) {
                throw new AssertionError("Should not be called");
            }
            @Override public boolean supports(KnowledgeQuery q) { return false; }
        };
        var composite = new CompositeSearchableProvider("location",
            List.of(supporting, notSupporting));

        var result = composite.search(
            new KnowledgeQuery.TextSearch("test", "location"),
            PipelinePageRequest.first(20));

        assertEquals(1, result.items().size());
    }

    @Test
    void isolatesProviderFailures() {
        var good = stubProvider("p1", List.of(entity("e1", "Place", "p1")));
        var bad = new SearchableProvider() {
            @Override public String domain() { return "location"; }
            @Override public String id() { return "p2"; }
            @Override public PipelinePage<CachedEntity> search(KnowledgeQuery q, PipelinePageRequest p) {
                throw new RuntimeException("Provider failure");
            }
            @Override public boolean supports(KnowledgeQuery q) { return true; }
        };
        var composite = new CompositeSearchableProvider("location",
            List.of(good, bad));

        var result = composite.search(
            new KnowledgeQuery.TextSearch("test", "location"),
            PipelinePageRequest.first(20));

        assertEquals(1, result.items().size());
    }

    @Test
    void domainAndIdReflectComposite() {
        var composite = new CompositeSearchableProvider("location",
            List.of(stubProvider("p1", List.of())));

        assertEquals("location", composite.domain());
        assertEquals("location", composite.id());
    }

    @Test
    void supportsIfAnyDelegateSupports() {
        var provider = stubProvider("p1", List.of());
        var composite = new CompositeSearchableProvider("location", List.of(provider));

        assertTrue(composite.supports(new KnowledgeQuery.TextSearch("test", "location")));
    }

    @Test
    void rejectsEmptyDelegateList() {
        assertThrows(IllegalArgumentException.class,
            () -> new CompositeSearchableProvider("location", List.of()));
    }

    private static SearchableProvider stubProvider(String id, List<CachedEntity> results) {
        return new SearchableProvider() {
            @Override public String domain() { return "location"; }
            @Override public String id() { return id; }
            @Override public PipelinePage<CachedEntity> search(KnowledgeQuery q, PipelinePageRequest p) {
                return new PipelinePage<>(results, null, false);
            }
            @Override public boolean supports(KnowledgeQuery q) { return true; }
        };
    }

    private static CachedEntity entity(String id, String name, String source) {
        return new CachedEntity(id, name, new Coordinates(51.5, -0.1), null,
            source, id, Map.of(), Instant.now(), null,
            Instant.now().plusSeconds(3600), Set.of(), false, "location");
    }
}
