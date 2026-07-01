package io.casehub.neocortex.memory.sqlite;

import io.casehub.neocortex.memory.*;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@ActivateRequestContext
@TestProfile(SqliteMemoryStoreTest.FtsDisabledProfile.class)
class SqliteMemoryStoreFtsDisabledTest {

    static final String TENANT = "tenant-1";
    static final MemoryDomain DOMAIN = new MemoryDomain("d");

    @Inject SqliteMemoryStore store;
    @Inject FixedCurrentPrincipal principal;

    @BeforeEach
    void setup() {
        principal.setTenancyId(TENANT);
    }

    @AfterEach
    void cleanUp() {
        store.eraseEntity("entity-1", TENANT);
    }

    @Test
    void relevanceOrderWithQuestionFallsBackToChronologicalWhenFtsDisabled() {
        store.store(new MemoryInput("entity-1", DOMAIN, TENANT, null,
            "first stored — contains target word ibuprofen", Map.of()));
        store.store(new MemoryInput("entity-1", DOMAIN, TENANT, null,
            "second stored — also mentions ibuprofen", Map.of()));

        // With fts.enabled=false, RELEVANCE+question → chronological fallback (most-recent first)
        var results = store.query(
            MemoryQuery.forEntity("entity-1", DOMAIN, TENANT)
                .withOrder(MemoryOrder.RELEVANCE)
                .withQuestion("ibuprofen"));

        assertEquals(2, results.size());
        assertEquals("second stored — also mentions ibuprofen", results.get(0).text(),
            "Expected chronological DESC when FTS disabled; got: " + results.get(0).text());
    }
}
