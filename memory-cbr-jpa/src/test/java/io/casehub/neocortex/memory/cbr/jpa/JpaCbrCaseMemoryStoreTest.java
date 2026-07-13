package io.casehub.neocortex.memory.cbr.jpa;

import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.testing.CbrCaseMemoryStoreContractTest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;

@QuarkusTest
class JpaCbrCaseMemoryStoreTest extends CbrCaseMemoryStoreContractTest {

    @Inject
    CbrCaseMemoryStore store;

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void clearDatabase() {
        em.createQuery("DELETE FROM CbrCaseEntity").executeUpdate();
    }

    @Override
    protected CbrCaseMemoryStore store() {
        return store;
    }
}
