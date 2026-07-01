package io.casehub.neocortex.memory.jpa;

import io.casehub.neocortex.memory.*;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(value = PostgresTestResource.class, restrictToAnnotatedClass = true)
class PostgresDialectFtsTest {

    static final String TENANT = "tenant-1";
    static final MemoryDomain DOMAIN = new MemoryDomain("fts-en");

    @Inject JpaMemoryStore store;
    @Inject FixedCurrentPrincipal principal;

    @BeforeEach
    void setTenant() {
        principal.setTenancyId(TENANT);
    }

    private MemoryInput input(String text) {
        return new MemoryInput("entity-fts", DOMAIN, TENANT, null, text, Map.of());
    }

    private MemoryQuery ftsQuery(String question) {
        return MemoryQuery.forEntity("entity-fts", DOMAIN, TENANT)
            .withQuestion(question)
            .withLimit(10)
            .withOrder(MemoryOrder.RELEVANCE);
    }

    @Test @TestTransaction
    void fts_finds_match_by_stemmed_question() {
        store.store(input("The dog barked loudly at night"));
        // "barking" → Snowball English stem "bark"; "barked" → same stem. Proves the stemmer runs.
        List<Memory> results = store.query(ftsQuery("barking"));
        assertEquals(1, results.size());
        assertEquals("The dog barked loudly at night", results.get(0).text());
    }

    @Test @TestTransaction
    void fts_returns_empty_when_no_semantic_match() {
        store.store(input("The dog barked at the moon"));
        assertTrue(store.query(ftsQuery("elephant")).isEmpty());
    }

    @Test @TestTransaction
    void fts_ranks_higher_keyword_density_first() {
        store.store(input("The dog barked at the moon one evening in summer"));
        store.store(input("bark bark bark bark bark"));
        List<Memory> results = store.query(ftsQuery("bark"));
        assertEquals(2, results.size());
        assertEquals("bark bark bark bark bark", results.get(0).text());
    }

    @Test @TestTransaction
    void fts_multi_entity_returns_results_from_all_entities() {
        store.store(new MemoryInput("entity-a", DOMAIN, TENANT, null, "The cat sat on the mat", Map.of()));
        store.store(new MemoryInput("entity-b", DOMAIN, TENANT, null, "The cat chased the mouse", Map.of()));
        var q = MemoryQuery.forEntities(List.of("entity-a", "entity-b"), DOMAIN, TENANT)
            .withQuestion("cat").withOrder(MemoryOrder.RELEVANCE);
        var results = store.query(q);
        assertEquals(2, results.size());
    }
}
