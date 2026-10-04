package io.casehub.neocortex.knowledge;

import com.zaxxer.hikari.HikariDataSource;
import io.casehub.connectors.location.spi.LocationPlatform;
import io.casehub.neocortex.knowledge.cache.CacheDecayPolicy;
import io.casehub.neocortex.knowledge.cache.CacheEvictionScheduler;
import io.casehub.neocortex.knowledge.cache.EntityMetadataStore;
import io.casehub.neocortex.knowledge.cache.QueryCacheStore;
import io.casehub.neocortex.knowledge.cache.SpatialSubsumptionRule;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.knowledge.promotion.EntityPromoter;
import io.casehub.neocortex.knowledge.research.ResearchSessionStore;
import io.casehub.neocortex.knowledge.resolution.EntityResolutionEngine;
import io.casehub.neocortex.knowledge.resolution.PlaceMatcher;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class KnowledgePipelineDefaultBeans {

    @Produces @DefaultBean @ApplicationScoped
    CacheDecayPolicy cacheDecayPolicy(KnowledgePipelineConfig config) {
        var c = config.cache();
        return new CacheDecayPolicy(c.coordinatesTtl(), c.ratingTtl(),
            c.contactTtl(), c.hoursTtl(), c.reviewsTtl(), c.imagesTtl(),
            c.searchResultsTtl());
    }

    @Produces @DefaultBean @ApplicationScoped
    SubsumptionRule subsumptionRule() {
        return new SpatialSubsumptionRule();
    }

    @Produces @DefaultBean @ApplicationScoped
    EntityMatcher<CachedEntity> entityMatcher() {
        return new PlaceMatcher();
    }

    @Produces @DefaultBean @ApplicationScoped
    EntityResolutionEngine entityResolutionEngine(EntityMatcher<CachedEntity> matcher) {
        return new EntityResolutionEngine(matcher);
    }

    @Produces @DefaultBean @ApplicationScoped
    HikariDataSource knowledgeDataSource(KnowledgePipelineConfig config) {
        HikariDataSource ds = SqliteDataSourceFactory.create(config.sqlite().path(), 3, 5000);
        SqliteDataSourceFactory.migrate(ds, "classpath:db/knowledge-pipeline");
        return ds;
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    ResearchSessionStore researchSessionStore(KnowledgePipelineConfig config) {
        return new ResearchSessionStore(config.research().sqlite().path());
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    KnowledgePipelineOrchestrator knowledgePipelineOrchestrator(
            Instance<LocationPlatform> providers,
            SpatialCacheStore cacheStore,
            QueryCacheStore queryCache,
            DedupIndexStore dedupStore,
            EntityMetadataStore metadataStore,
            EntityResolutionEngine resolutionEngine,
            EntityPromoter promoter,
            CacheDecayPolicy decayPolicy,
            SubsumptionRule subsumptionRule,
            KnowledgePipelineConfig config,
            KnowledgePipelineMetrics metrics) {
        var orch = new KnowledgePipelineOrchestrator(
                providers.stream().toList(), cacheStore, queryCache, dedupStore,
                metadataStore, resolutionEngine, promoter, decayPolicy,
                subsumptionRule, config.geohashPrecision());
        orch.setMetrics(metrics);
        return orch;
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    CacheEvictionScheduler cacheEvictionScheduler(
            SpatialCacheStore cacheStore,
            EntityMetadataStore metadataStore,
            ResearchSessionStore sessionStore,
            DedupIndexStore dedupStore,
            KnowledgePipelineConfig config,
            KnowledgePipelineMetrics metrics) {
        var sched = new CacheEvictionScheduler(
                cacheStore, metadataStore, sessionStore, dedupStore,
                config.cache().maxEntityAge(), config.research().maxSessionDuration());
        sched.setMetrics(metrics);
        return sched;
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    KnowledgePipelineMetrics knowledgePipelineMetrics(Instance<MeterRegistry> registry) {
        return new KnowledgePipelineMetrics(
                registry.isResolvable() ? registry.get() : Metrics.globalRegistry);
    }

    void closeResearchSessionStore(@Disposes ResearchSessionStore store) {
        store.close();
    }


}
