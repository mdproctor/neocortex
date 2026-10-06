package io.casehub.neocortex.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DomainRegistryTest {

    @Test
    void lookupReturnsDomainSupport() {
        var support = DomainSupport.minimal("test", stubProvider("test"));
        var registry = new DomainRegistry(List.of(support));
        assertEquals(Optional.of(support), registry.lookup("test"));
    }

    @Test
    void lookupReturnsEmptyForUnknownDomain() {
        var registry = new DomainRegistry(List.of());
        assertEquals(Optional.empty(), registry.lookup("unknown"));
    }

    @Test
    void allDomains() {
        var s1 = DomainSupport.minimal("a", stubProvider("a"));
        var s2 = DomainSupport.minimal("b", stubProvider("b"));
        var registry = new DomainRegistry(List.of(s1, s2));
        assertEquals(2, registry.allDomains().size());
        assertTrue(registry.allDomains().containsKey("a"));
        assertTrue(registry.allDomains().containsKey("b"));
    }

    @Test
    void duplicateDomainThrows() {
        var s1 = DomainSupport.minimal("a", stubProvider("a"));
        var s2 = DomainSupport.minimal("a", stubProvider("a2"));
        assertThrows(IllegalStateException.class, () -> new DomainRegistry(List.of(s1, s2)));
    }

    private SearchableProvider stubProvider(String domain) {
        return new SearchableProvider() {
            public String domain() { return domain; }
            public String id() { return "stub"; }
            public PipelinePage<CachedEntity> search(KnowledgeQuery q, PipelinePageRequest p) {
                return new PipelinePage<>(List.of(), null, false);
            }
            public boolean supports(KnowledgeQuery q) { return true; }
        };
    }
}
