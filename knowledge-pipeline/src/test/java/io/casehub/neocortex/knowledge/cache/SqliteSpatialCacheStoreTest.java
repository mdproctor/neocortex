package io.casehub.neocortex.knowledge.cache;

import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.CacheFilter;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.SpatialCacheStore;
import io.casehub.neocortex.knowledge.testing.SpatialCacheStoreContractTest;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteSpatialCacheStoreTest extends SpatialCacheStoreContractTest {

    private HikariDataSource ds;

    @Override
    protected SpatialCacheStore createSpatialStore() {
        ds = SqliteDataSourceFactory.create(":memory:", 3, 5000);
        SqliteDataSourceFactory.migrate(ds, "classpath:db/knowledge-pipeline");
        return new SqliteSpatialCacheStore(ds);
    }

    @AfterEach
    void tearDown() {
        if (ds != null) ds.close();
    }

    @Test
    void removeAlsoDeletesSpatialIndex() {
        spatialStore.set(entity("e1", 55.9533, -3.1883), "t1");
        spatialStore.remove("e1", "t1");
        assertThat(spatialStore.nearby(new Coordinates(55.9533, -3.1883), 100,
            CacheFilter.none(), "t1")).isEmpty();
    }

    @Test
    void setUpdatesExistingEntity() {
        spatialStore.set(entity("e1", 55.9533, -3.1883), "t1");
        var updated = new CachedEntity("e1", "Ondine Seafood",
            new Coordinates(55.9534, -3.1884), "restaurant",
            "google", "ext-e1", Map.of("phone", "0131 226 1888"),
            Instant.now(), null, Instant.now().plus(Duration.ofDays(30)),
            Set.of(), true, null);
        spatialStore.set(updated, "t1");

        var result = spatialStore.get("e1", "t1");
        assertThat(result.name()).isEqualTo("Ondine Seafood");
        assertThat(result.hasDetail()).isTrue();
        assertThat(result.properties()).containsEntry("phone", "0131 226 1888");
    }
}
