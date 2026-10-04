package io.casehub.neocortex.knowledge;

import io.casehub.connectors.location.model.Coordinates;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface SpatialCacheStore {

    void set(CachedEntity entity, String tenantId);

    CachedEntity get(String entityId, String tenantId);

    List<CachedEntity> nearby(Coordinates center, int radiusMeters,
                               CacheFilter filters, String tenantId);

    List<CachedEntity> within(BoundingBox box, CacheFilter filters, String tenantId);

    void remove(String entityId, String tenantId);

    void expire(String entityId, Instant expiresAt, String tenantId);


    List<CachedEntity> listAll(String tenantId);

    List<String> findExpired(String tenantId, Instant now);

    Set<String> discoverTenants();
}
