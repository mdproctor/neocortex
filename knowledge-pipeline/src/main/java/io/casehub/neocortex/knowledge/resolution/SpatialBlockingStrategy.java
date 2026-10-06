package io.casehub.neocortex.knowledge.resolution;

import io.casehub.neocortex.knowledge.BlockingStrategy;
import io.casehub.neocortex.knowledge.CacheFilter;
import io.casehub.neocortex.knowledge.CacheStore;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.SpatialCacheStore;

import java.util.List;

public class SpatialBlockingStrategy implements BlockingStrategy {

    private final SpatialCacheStore spatialStore;
    private final int blockingRadiusMeters;

    public SpatialBlockingStrategy(SpatialCacheStore spatialStore, int blockingRadiusMeters) {
        this.spatialStore = spatialStore;
        this.blockingRadiusMeters = blockingRadiusMeters;
    }

    @Override
    public List<CachedEntity> findCandidates(CachedEntity entity, CacheStore store, String tenantId) {
        if (entity.coordinates() == null) {
            return List.of();
        }
        return spatialStore.nearby(entity.coordinates(), blockingRadiusMeters,
            CacheFilter.none(), tenantId);
    }
}
