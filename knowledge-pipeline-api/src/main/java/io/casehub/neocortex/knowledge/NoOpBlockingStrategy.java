package io.casehub.neocortex.knowledge;

import java.util.List;

public enum NoOpBlockingStrategy implements BlockingStrategy {
    INSTANCE;

    @Override
    public List<CachedEntity> findCandidates(CachedEntity entity, CacheStore store, String tenantId) {
        return List.of();
    }
}
