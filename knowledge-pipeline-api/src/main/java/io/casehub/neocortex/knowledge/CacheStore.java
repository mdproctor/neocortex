package io.casehub.neocortex.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface CacheStore {
    void set(CachedEntity entity, String tenantId);
    CachedEntity get(String entityId, String tenantId);
    void remove(String entityId, String tenantId);
    void expire(String entityId, Instant expiresAt, String tenantId);
    List<CachedEntity> listAll(String tenantId);
    List<String> findExpired(String tenantId, Instant now);
    Set<String> discoverTenants();
}
