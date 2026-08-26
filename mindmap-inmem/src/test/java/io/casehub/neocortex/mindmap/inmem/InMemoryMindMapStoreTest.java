package io.casehub.neocortex.mindmap.inmem;

import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.testing.MindMapStoreContractTest;

class InMemoryMindMapStoreTest extends MindMapStoreContractTest {

    @Override
    protected MindMapStore createStore() {
        return new InMemoryMindMapStore();
    }
}
