package io.casehub.neocortex.memory.cbr.tracking;

import io.casehub.neocortex.memory.cbr.CbrRetrievalTracker;
import io.casehub.neocortex.memory.cbr.testing.CbrRetrievalTrackerContractTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

class SqliteCbrRetrievalTrackerTest extends CbrRetrievalTrackerContractTest {

    private SqliteCbrRetrievalTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new SqliteCbrRetrievalTracker(":memory:", 1, 5000);
        tracker.init();
    }

    @AfterEach
    void tearDown() {
        if (tracker != null) tracker.shutdown();
    }

    @Override
    protected CbrRetrievalTracker tracker() {
        return tracker;
    }
}
