package io.casehub.neocortex.rag.tracking;

import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.neocortex.rag.testing.RetrievalTrackerContractTest;
import org.junit.jupiter.api.BeforeEach;

class SqliteRetrievalTrackerTest extends RetrievalTrackerContractTest {

    private final SqliteRetrievalTracker store;

    SqliteRetrievalTrackerTest() {
        store = new SqliteRetrievalTracker();
        store.path = ":memory:";
        store.maxPoolSize = 1;
        store.busyTimeoutMs = 5000;
        store.init();
    }

    @Override
    protected RetrievalTracker tracker() {
        return store;
    }

    @BeforeEach
    void reset() {
        store.clearAll();
    }
}
