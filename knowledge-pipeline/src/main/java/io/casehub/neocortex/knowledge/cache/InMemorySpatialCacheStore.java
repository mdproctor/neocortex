package io.casehub.neocortex.knowledge.cache;

import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.BoundingBox;
import io.casehub.neocortex.knowledge.CacheFilter;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.SpatialCacheStore;
import io.casehub.neocortex.knowledge.resolution.Haversine;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(2)
@ApplicationScoped
public class InMemorySpatialCacheStore implements SpatialCacheStore {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, CachedEntity>> tenants =
        new ConcurrentHashMap<>();

    @Override
    public void set(CachedEntity entity, String tenantId) {
        tenants.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
            .put(entity.id(), entity);
    }

    @Override
    public CachedEntity get(String entityId, String tenantId) {
        var entities = tenants.get(tenantId);
        return entities != null ? entities.get(entityId) : null;
    }

    @Override
    public List<CachedEntity> nearby(Coordinates center, int radiusMeters,
                                      CacheFilter filters, String tenantId) {
        var entities = tenants.get(tenantId);
        if (entities == null) return List.of();
        return entities.values().stream()
            .filter(e -> e.coordinates() != null)
            .filter(e -> Haversine.distanceMeters(center, e.coordinates()) <= radiusMeters)
            .filter(e -> matchesFilter(e, filters))
            .toList();
    }

    @Override
    public List<CachedEntity> within(BoundingBox box, CacheFilter filters,
                                      String tenantId) {
        var entities = tenants.get(tenantId);
        if (entities == null) return List.of();
        return entities.values().stream()
            .filter(e -> e.coordinates() != null)
            .filter(e -> e.coordinates().lat() >= box.minLat()
                      && e.coordinates().lat() <= box.maxLat()
                      && e.coordinates().lng() >= box.minLng()
                      && e.coordinates().lng() <= box.maxLng())
            .filter(e -> matchesFilter(e, filters))
            .toList();
    }

    @Override
    public void remove(String entityId, String tenantId) {
        var entities = tenants.get(tenantId);
        if (entities != null) entities.remove(entityId);
    }

    @Override
    public void expire(String entityId, Instant expiresAt, String tenantId) {
        var entities = tenants.get(tenantId);
        if (entities == null) return;
        var existing = entities.get(entityId);
        if (existing == null) return;
        entities.put(entityId, new CachedEntity(
            existing.id(), existing.name(), existing.coordinates(),
            existing.category(), existing.source(), existing.externalId(),
            existing.properties(), existing.fetchedAt(), existing.detailFetchedAt(), expiresAt,
            existing.sessionIds(), existing.hasDetail(), existing.domain()));
    }


    @Override
    public List<CachedEntity> listAll(String tenantId) {
        var entities = tenants.get(tenantId);
        if (entities == null) {return List.of();}
        return List.copyOf(entities.values());
    }

    @Override
    public List<String> findExpired(String tenantId, Instant now) {
        var entities = tenants.get(tenantId);
        if (entities == null) return List.of();
        List<String> expired = new ArrayList<>();
        for (var entry : entities.entrySet()) {
            if (entry.getValue().expiresAt() != null
                    && now.isAfter(entry.getValue().expiresAt())) {
                expired.add(entry.getKey());
            }
        }
        return expired;
    }

    @Override
    public Set<String> discoverTenants() {
        return Set.copyOf(tenants.keySet());
    }

    private boolean matchesFilter(CachedEntity entity, CacheFilter filter) {
        if (filter == null) return true;
        if (filter.category() != null && !filter.category().equals(entity.category()))
            return false;
        return true;
    }
}
