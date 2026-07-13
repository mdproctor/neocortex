package io.casehub.neocortex.memory.cbr.testing;

import io.casehub.neocortex.memory.cbr.CbrRetrievalTracker;

class InMemoryCbrRetrievalTrackerTest extends CbrRetrievalTrackerContractTest {

    private final InMemoryCbrRetrievalTracker tracker = new InMemoryCbrRetrievalTracker();

    @Override
    protected CbrRetrievalTracker tracker() {
        return tracker;
    }
}
