package io.casehub.neocortex.knowledge.cache;

import io.casehub.neocortex.knowledge.SpatialCacheStore;
import io.casehub.neocortex.knowledge.testing.SpatialCacheStoreContractTest;

class InMemorySpatialCacheStoreTest extends SpatialCacheStoreContractTest {

    @Override
    protected SpatialCacheStore createSpatialStore() {
        return new InMemorySpatialCacheStore();
    }
}
