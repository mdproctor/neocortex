package io.casehub.neocortex.knowledge.testing;

import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.BoundingBox;
import io.casehub.neocortex.knowledge.CacheFilter;
import io.casehub.neocortex.knowledge.CacheStore;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.SpatialCacheStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class SpatialCacheStoreContractTest extends CacheStoreContractTest {

    protected SpatialCacheStore spatialStore;

    protected abstract SpatialCacheStore createSpatialStore();

    @Override
    protected CacheStore createStore() {
        spatialStore = createSpatialStore();
        return spatialStore;
    }

    protected CachedEntity entity(String id, double lat, double lng) {
        return new CachedEntity(id, "Place " + id, new Coordinates(lat, lng),
            "restaurant", "google", "ext-" + id, Map.of(),
            Instant.now(), null, Instant.now().plusSeconds(86400), Set.of(), false, null);
    }

    @Test
    void nearbyReturnsEntitiesWithinRadius() {
        spatialStore.set(entity("e1", 51.5, -0.1), "t1");
        spatialStore.set(entity("e2", 51.50005, -0.10005), "t1");
        spatialStore.set(entity("e3", 55.9, -3.1), "t1");

        var nearby = spatialStore.nearby(new Coordinates(51.5, -0.1), 1000,
            CacheFilter.none(), "t1");
        assertThat(nearby).extracting(CachedEntity::id)
            .containsExactlyInAnyOrder("e1", "e2");
    }

    @Test
    void withinReturnsBoundedEntities() {
        spatialStore.set(entity("e1", 51.5, -0.1), "t1");
        spatialStore.set(entity("e2", 51.6, -0.2), "t1");
        spatialStore.set(entity("e3", 55.9, -3.1), "t1");

        var within = spatialStore.within(
            new BoundingBox(51.4, -0.3, 51.7, 0.0), CacheFilter.none(), "t1");
        assertThat(within).extracting(CachedEntity::id)
            .containsExactlyInAnyOrder("e1", "e2");
    }

    @Test
    void nearbyFiltersByCategory() {
        var italian = new CachedEntity("e1", "Ondine", new Coordinates(51.5, -0.1),
            "italian", "google", "ext-1", Map.of(),
            Instant.now(), null, Instant.now().plusSeconds(86400), Set.of(), false, null);
        var coffee = new CachedEntity("e2", "Costa", new Coordinates(51.50001, -0.10001),
            "coffee", "google", "ext-2", Map.of(),
            Instant.now(), null, Instant.now().plusSeconds(86400), Set.of(), false, null);
        spatialStore.set(italian, "t1");
        spatialStore.set(coffee, "t1");

        var filtered = spatialStore.nearby(new Coordinates(51.5, -0.1), 1000,
            new CacheFilter("italian", null, null, null), "t1");
        assertThat(filtered).extracting(CachedEntity::id).containsExactly("e1");
    }

}
