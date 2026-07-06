package io.casehub.neocortex.rag.testing;

import io.casehub.neocortex.rag.RetrievalTracker;
import org.junit.jupiter.api.BeforeEach;

class InMemoryRetrievalTrackerTest extends RetrievalTrackerContractTest {

    private final InMemoryRetrievalTracker store = new InMemoryRetrievalTracker();

    @Override
    protected RetrievalTracker tracker() {
        return store;
    }

    @BeforeEach
    void reset() {
        store.clear();
    }
}
