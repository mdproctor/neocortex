package io.casehub.neocortex.memory.jpa;

import io.casehub.neocortex.memory.*;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(PostgresDialectFtsFrenchTest.FrenchFtsProfile.class)
@QuarkusTestResource(value = PostgresTestResource.class, restrictToAnnotatedClass = true)
class PostgresDialectFtsFrenchTest {

    public static class FrenchFtsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("casehub.memory.jpa.fts.language", "french");
        }
    }

    static final String TENANT = "tenant-1";
    static final MemoryDomain DOMAIN = new MemoryDomain("fts-fr");

    @Inject JpaMemoryStore store;
    @Inject FixedCurrentPrincipal principal;

    @BeforeEach
    void setTenant() {
        principal.setTenancyId(TENANT);
    }

    private MemoryInput input(String text) {
        return new MemoryInput("entity-fr", DOMAIN, TENANT, null, text, Map.of());
    }

    private MemoryQuery ftsQuery(String question) {
        return MemoryQuery.forEntity("entity-fr", DOMAIN, TENANT)
            .withQuestion(question)
            .withLimit(10)
            .withOrder(MemoryOrder.RELEVANCE);
    }

    @Test @TestTransaction
    void fts_french_regconfig_stems_plural_to_singular() {
        store.store(input("les chiens aboient dans le jardin la nuit"));
        List<Memory> results = store.query(ftsQuery("chien"));
        assertEquals(1, results.size());
    }
}
