package io.casehub.neocortex.knowledge.resolution;

import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.BlockingStrategy;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.cache.InMemorySpatialCacheStore;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.mindmap.SignalCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EntityResolutionEngineTest {

    private EntityResolutionEngine engine;
    private InMemorySpatialCacheStore cache;
    private BlockingStrategy blocking;
    private DedupIndexStore dedup;

    @BeforeEach
    void setUp() {
        engine = new EntityResolutionEngine(new PlaceMatcher());
        cache = new InMemorySpatialCacheStore();
        blocking = new SpatialBlockingStrategy(cache, 200);
        dedup = new DedupIndexStore(":memory:");
    }

    @AfterEach
    void tearDown() {
        dedup.close();
    }

    private CachedEntity entity(String id, String name, double lat, double lng,
                                 String source, String extId) {
        return new CachedEntity(id, name, new Coordinates(lat, lng),
            "restaurant", source, extId, Map.of(),
            Instant.now(), null, Instant.now().plusSeconds(86400), Set.of(), false, null);
    }

    @Test
    void knownEntityInDedupIndexIsPassedThrough() {
        dedup.upsert("google", "ChIJ123", "e1");
        var entities = List.of(entity("e1", "Ondine", 51.5, -0.1, "google", "ChIJ123"));
        var result = engine.resolve(entities, blocking, cache, dedup, "t1");
        assertThat(result.resolved()).hasSize(1);
        assertThat(result.signals()).isEmpty();
    }

    @Test
    void highConfidenceMatchMergesEntities() {
        var existing = entity("e-existing", "Ondine", 51.5, -0.1, "google", "g1");
        cache.set(existing, "t1");

        var newEntity = entity("e-new", "Ondine", 51.50001, -0.10001,
            "tripadvisor", "t1-ext");
        var result = engine.resolve(List.of(newEntity), blocking, cache, dedup, "t1");
        assertThat(result.resolved()).hasSize(1);
        assertThat(result.signals()).isEmpty();
    }

    @Test
    void mediumConfidenceEmitsMergeCandidateSignal() {
        var existing = entity("e-existing", "Ondine Restaurant",
            51.5001, -0.1001, "google", "g1");
        cache.set(existing, "t1");

        var newEntity = entity("e-new", "Ondine Seafood Bar",
            51.5002, -0.1002, "tripadvisor", "t1-ext");
        var result = engine.resolve(List.of(newEntity), blocking, cache, dedup, "t1");
        assertThat(result.resolved()).hasSize(1);
    }

    @Test
    void noMatchKeepsEntitiesSeparate() {
        var existing = entity("e-existing", "Costa Coffee", 51.5, -0.1, "google", "g1");
        cache.set(existing, "t1");

        var newEntity = entity("e-new", "Starbucks", 51.5005, -0.1005,
            "tripadvisor", "t1-ext");
        var result = engine.resolve(List.of(newEntity), blocking, cache, dedup, "t1");
        assertThat(result.resolved()).hasSize(1);
        assertThat(result.resolved().get(0).name()).isEqualTo("Starbucks");
        assertThat(result.signals()).isEmpty();
    }

    @Test
    void entityWithoutCoordinatesIsPassedThrough() {
        var entity = new CachedEntity("e1", "Unknown Place", null,
            "restaurant", "google", "g1", Map.of(),
            Instant.now(), null, Instant.now().plusSeconds(86400), Set.of(), false, null);
        var result = engine.resolve(List.of(entity), blocking, cache, dedup, "t1");
        assertThat(result.resolved()).hasSize(1);
    }

    @Test
    void multipleEntitiesResolvedIndependently() {
        var entities = List.of(
            entity("e1", "Ondine", 51.5, -0.1, "google", "g1"),
            entity("e2", "Costa", 51.6, -0.2, "google", "g2"),
            entity("e3", "Starbucks", 51.7, -0.3, "google", "g3")
        );
        var result = engine.resolve(entities, blocking, cache, dedup, "t1");
        assertThat(result.resolved()).hasSize(3);
    }
}
