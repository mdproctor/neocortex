package io.casehub.neocortex.knowledge;

import java.util.List;

@FunctionalInterface
public interface BlockingStrategy {
    List<CachedEntity> findCandidates(CachedEntity entity, CacheStore store, String tenantId);
}
