package io.casehub.neocortex.knowledge.cache;

import com.zaxxer.hikari.HikariDataSource;
import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.ResearchState;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.knowledge.research.ResearchSessionStore;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CacheEvictionSchedulerTest {

    private HikariDataSource pipelineDs;
    private HikariDataSource researchDs;
    private InMemorySpatialCacheStore cacheStore;
    private EntityMetadataStore metadataStore;
    private ResearchSessionStore sessionStore;
    private DedupIndexStore dedupStore;
    private CacheEvictionScheduler scheduler;

    @BeforeEach
    void setUp() {
        pipelineDs = SqliteDataSourceFactory.create(":memory:", 3, 5000);
        SqliteDataSourceFactory.migrate(pipelineDs, "classpath:db/knowledge-pipeline");
        researchDs = SqliteDataSourceFactory.create(":memory:", 3, 5000);
        SqliteDataSourceFactory.migrate(researchDs, "classpath:db/knowledge-research");

        cacheStore = new InMemorySpatialCacheStore();
        metadataStore = new EntityMetadataStore(pipelineDs);
        sessionStore = new ResearchSessionStore(researchDs);
        dedupStore = new DedupIndexStore(pipelineDs);

        scheduler = new CacheEvictionScheduler(
            cacheStore, metadataStore, sessionStore, dedupStore,
            Duration.ofDays(90), Duration.ofDays(180));
    }

    @AfterEach
    void tearDown() {
        pipelineDs.close();
        researchDs.close();
    }

    @Test
    void evictsExpiredEntityWithNoSession() {
        var entity = expiredEntity("e1", "tenant-1");
        cacheStore.set(entity, "tenant-1");

        scheduler.runEviction();

        assertThat(cacheStore.get("e1", "tenant-1")).isNull();
    }

    @Test
    void preservesNonExpiredEntity() {
        var entity = validEntity("e1", "tenant-1");
        cacheStore.set(entity, "tenant-1");

        scheduler.runEviction();

        assertThat(cacheStore.get("e1", "tenant-1")).isNotNull();
    }

    @Test
    void protectsEntityWithActiveSession() {
        var entity = expiredEntity("e1", "tenant-1");
        cacheStore.set(entity, "tenant-1");
        metadataStore.addSession("e1", "session-1");

        var session = new io.casehub.neocortex.knowledge.ResearchSession(
            "session-1", "Test", null, "sg-1", ResearchState.ACTIVE,
            "tenant-1", Instant.now(), Instant.now());
        sessionStore.insert(session);

        scheduler.runEviction();

        assertThat(cacheStore.get("e1", "tenant-1")).isNotNull();
    }

    @Test
    void protectsEntityWithPausedSession() {
        var entity = expiredEntity("e1", "tenant-1");
        cacheStore.set(entity, "tenant-1");
        metadataStore.addSession("e1", "session-1");

        var session = new io.casehub.neocortex.knowledge.ResearchSession(
            "session-1", "Test", null, "sg-1", ResearchState.PAUSED,
            "tenant-1", Instant.now(), Instant.now());
        sessionStore.insert(session);

        scheduler.runEviction();

        assertThat(cacheStore.get("e1", "tenant-1")).isNotNull();
    }

    @Test
    void evictsEntityWithOnlyCompletedSession() {
        var entity = expiredEntity("e1", "tenant-1");
        cacheStore.set(entity, "tenant-1");
        metadataStore.addSession("e1", "session-1");

        var session = new io.casehub.neocortex.knowledge.ResearchSession(
            "session-1", "Test", null, "sg-1", ResearchState.COMPLETED,
            "tenant-1", Instant.now(), Instant.now());
        sessionStore.insert(session);

        scheduler.runEviction();

        assertThat(cacheStore.get("e1", "tenant-1")).isNull();
    }

    @Test
    void preservesDedupIndexAfterEviction() {
        var entity = expiredEntity("e1", "tenant-1");
        cacheStore.set(entity, "tenant-1");
        dedupStore.upsert("google", "g1", "e1");

        scheduler.runEviction();

        assertThat(cacheStore.get("e1", "tenant-1")).isNull();
        assertThat(dedupStore.lookup("google", "g1")).isPresent();
    }

    @Test
    void multiTenantEviction() {
        cacheStore.set(expiredEntity("e1", "t1"), "t1");
        cacheStore.set(validEntity("e2", "t1"), "t1");
        cacheStore.set(expiredEntity("e3", "t2"), "t2");

        scheduler.runEviction();

        assertThat(cacheStore.get("e1", "t1")).isNull();
        assertThat(cacheStore.get("e2", "t1")).isNotNull();
        assertThat(cacheStore.get("e3", "t2")).isNull();
    }

    @Test
    void autoCompletesSessionsPastMaxDuration() {
        Instant old = Instant.now().minus(Duration.ofDays(200));
        var session = new io.casehub.neocortex.knowledge.ResearchSession(
                "s1", "Old Session", null, "sg-1", ResearchState.ACTIVE,
                "tenant-1", old, old);
        sessionStore.insert(session);

        cacheStore.set(expiredEntity("e1", "tenant-1"), "tenant-1");
        metadataStore.addSession("e1", "s1");

        scheduler.runEviction();

        assertThat(sessionStore.get("s1").get().state()).isEqualTo(ResearchState.COMPLETED);
    }

    @Test
    void evictsEntitiesPastMaxAgeEvenWithActiveSession() {
        var ancient = new io.casehub.neocortex.knowledge.CachedEntity(
                "e1", "Old Place", new io.casehub.connectors.location.model.Coordinates(51.5, -0.1),
                "restaurant", "google", "ext-1", Map.of(),
                Instant.now().minus(Duration.ofDays(100)),
                null, Instant.now().plusSeconds(86400),
                Set.of(), false, null);
        cacheStore.set(ancient, "tenant-1");

        var session = new io.casehub.neocortex.knowledge.ResearchSession(
                "s1", "Test", null, "sg-1", ResearchState.ACTIVE,
                "tenant-1", Instant.now(), Instant.now());
        sessionStore.insert(session);
        metadataStore.addSession("e1", "s1");

        scheduler.runEviction();

        assertThat(cacheStore.get("e1", "tenant-1")).isNull();
    }


    private CachedEntity expiredEntity(String id, String tenantId) {
        return new CachedEntity(id, "Test", new Coordinates(51.5, -0.1),
            "restaurant", "google", "g-" + id, Map.of(),
            Instant.now().minus(Duration.ofDays(2)),
            null, Instant.now().minus(Duration.ofHours(1)),
            Set.of(), false, null);
    }

    private CachedEntity validEntity(String id, String tenantId) {
        return new CachedEntity(id, "Test", new Coordinates(51.5, -0.1),
            "restaurant", "google", "g-" + id, Map.of(),
            Instant.now(), null, Instant.now().plus(Duration.ofDays(30)),
            Set.of(), false, null);
    }
}
