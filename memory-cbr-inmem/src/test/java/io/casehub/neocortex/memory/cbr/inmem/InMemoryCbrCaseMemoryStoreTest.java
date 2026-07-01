package io.casehub.neocortex.memory.cbr.inmem;

import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.testing.CbrCaseMemoryStoreContractTest;

class InMemoryCbrCaseMemoryStoreTest extends CbrCaseMemoryStoreContractTest {

    private final InMemoryCbrCaseMemoryStore store = new InMemoryCbrCaseMemoryStore();

    @Override
    protected CbrCaseMemoryStore store() {
        return store;
    }
}
