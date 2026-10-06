package io.casehub.neocortex.knowledge.resolution;

import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.CacheStore;
import io.casehub.neocortex.knowledge.cache.InMemorySpatialCacheStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SpatialBlockingStrategyTest {

    @Test
    void findsCandidatesWithinRadius() {
        var store = new InMemorySpatialCacheStore();
        var nearby = testEntity("e2", "Place B", 51.5075, -0.1279);
        store.set(nearby, "t1");

        var entity = testEntity("e1", "Place A", 51.5074, -0.1278);
        var strategy = new SpatialBlockingStrategy(store, 500);
        var candidates = strategy.findCandidates(entity, store, "t1");

        assertFalse(candidates.isEmpty());
    }

    @Test
    void returnsEmptyWhenNoCoordinates() {
        var store = new InMemorySpatialCacheStore();
        var entity = new CachedEntity("e1", "Place A", null, null,
            "src", "ext1", Map.of(), Instant.now(), null,
            Instant.now().plusSeconds(3600), Set.of(), false, null);

        var strategy = new SpatialBlockingStrategy(store, 500);
        var candidates = strategy.findCandidates(entity, store, "t1");

        assertTrue(candidates.isEmpty());
    }

    @Test
    void returnsEmptyWhenNoCandidatesInRadius() {
        var store = new InMemorySpatialCacheStore();
        var farAway = testEntity("e2", "Far Place", 52.0, 0.0);
        store.set(farAway, "t1");

        var entity = testEntity("e1", "Place A", 51.5074, -0.1278);
        var strategy = new SpatialBlockingStrategy(store, 500);
        var candidates = strategy.findCandidates(entity, store, "t1");

        assertTrue(candidates.isEmpty());
    }

    private CachedEntity testEntity(String id, String name, double lat, double lng) {
        return new CachedEntity(id, name, new Coordinates(lat, lng), null,
            "src", id, Map.of(), Instant.now(), null,
            Instant.now().plusSeconds(3600), Set.of(), false, null);
    }
}
