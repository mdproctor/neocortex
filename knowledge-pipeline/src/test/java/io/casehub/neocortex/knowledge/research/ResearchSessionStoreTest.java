package io.casehub.neocortex.knowledge.research;

import io.casehub.neocortex.knowledge.testing.ResearchSessionStoreContractTest;

class ResearchSessionStoreTest extends ResearchSessionStoreContractTest {

    @Override
    protected ResearchSessionStore createStore() {
        return new ResearchSessionStore(":memory:");
    }

    @Override
    protected void closeStore(ResearchSessionStore store) {
        store.close();
    }
}
