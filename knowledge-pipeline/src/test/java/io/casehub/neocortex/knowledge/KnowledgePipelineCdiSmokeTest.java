package io.casehub.neocortex.knowledge;

import io.casehub.neocortex.knowledge.cache.EntityMetadataStore;
import io.casehub.neocortex.knowledge.cache.QueryCacheStore;
import io.casehub.neocortex.knowledge.cache.SqliteSpatialCacheStore;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.knowledge.promotion.EntityPromoter;
import io.casehub.neocortex.knowledge.research.ResearchOrchestrator;
import io.casehub.neocortex.knowledge.research.ResearchSessionStore;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePipelineCdiSmokeTest {

    @Test
    void defaultBeansProducerChainResolvesCompletely() {
        var pipelineDs = SqliteDataSourceFactory.create(":memory:", 3, 5000);
        SqliteDataSourceFactory.migrate(pipelineDs, "classpath:db/knowledge-pipeline");

        var beans  = new KnowledgePipelineDefaultBeans();
        var config = createTestConfig();

        var decayPolicy = beans.cacheDecayPolicy(config);
        assertThat(decayPolicy).isNotNull();
        assertThat(decayPolicy.coordinatesTtl()).isNotNull();

        var subsumptionRule = beans.subsumptionRule();
        assertThat(subsumptionRule).isNotNull();

        var matcher = beans.entityMatcher();
        assertThat(matcher).isNotNull();

        var resolutionEngine = beans.entityResolutionEngine(matcher);
        assertThat(resolutionEngine).isNotNull();

        var dataSource = beans.knowledgeDataSource(config);
        assertThat(dataSource).isNotNull();

        var sessionStore = beans.researchSessionStore(config);
        assertThat(sessionStore).isNotNull();

        var cacheStore    = new SqliteSpatialCacheStore(dataSource);
        var queryCache    = new QueryCacheStore(dataSource);
        var dedupStore    = new DedupIndexStore(dataSource);
        var metadataStore = new EntityMetadataStore(dataSource);
        var mindMap       = new InMemoryMindMapStore();
        var promoter      = new EntityPromoter(mindMap, cacheStore, dedupStore, sessionStore);
        var metrics       = new KnowledgePipelineMetrics(io.micrometer.core.instrument.Metrics.globalRegistry);

        var noOpNormalizer = new io.casehub.neocortex.knowledge.normalization.NoOpTermNormalizer();
        var spatialDomain = beans.spatialDomainSupport(
                new EmptyInstance<>(), cacheStore, decayPolicy, subsumptionRule, matcher,
                noOpNormalizer, config);
        assertThat(spatialDomain).isNotNull();
        assertThat(spatialDomain.domain()).isEqualTo("location");

        var domainRegistry = beans.domainRegistry(new SingleInstance<>(spatialDomain));
        assertThat(domainRegistry).isNotNull();

        var orchestrator = beans.knowledgePipelineOrchestrator(
                domainRegistry, cacheStore, queryCache, dedupStore,
                metadataStore, resolutionEngine, promoter, decayPolicy,
                new EmptyInstance<>(), metrics);
        assertThat(orchestrator).isNotNull();

        var scheduler = beans.cacheEvictionScheduler(
                cacheStore, metadataStore, sessionStore, dedupStore, config, metrics);
        assertThat(scheduler).isNotNull();

        var producedMetrics = beans.knowledgePipelineMetrics(new EmptyInstance<>());
        assertThat(producedMetrics).isNotNull();

        var results = orchestrator.search(new KnowledgeQuery.TextSearch("test", null), "t1");
        assertThat(results).isEmpty();

        var session = new ResearchOrchestrator(sessionStore, mindMap,
                                               cacheStore, metadataStore, decayPolicy);
        var created = session.create("Test", null, "t1");
        assertThat(created.state()).isEqualTo(ResearchState.ACTIVE);

        pipelineDs.close();
        dataSource.close();
        sessionStore.close();
    }

    private static KnowledgePipelineConfig createTestConfig() {
        return new KnowledgePipelineConfig() {
            @Override
            public int geohashPrecision() {return 6;}

            @Override
            public SqliteConfig sqlite() {
                return () -> ":memory:";
            }

            @Override
            public CacheConfig cache() {
                return new CacheConfig() {
                    @Override
                    public java.time.Duration maxEntityAge()     {return java.time.Duration.ofDays(90);}

                    @Override
                    public java.time.Duration searchResultsTtl() {return java.time.Duration.ofDays(1);}

                    @Override
                    public java.time.Duration coordinatesTtl()   {return java.time.Duration.ofDays(30);}

                    @Override
                    public java.time.Duration ratingTtl()        {return java.time.Duration.ofDays(3);}

                    @Override
                    public java.time.Duration contactTtl()       {return java.time.Duration.ofDays(7);}

                    @Override
                    public java.time.Duration hoursTtl()         {return java.time.Duration.ofDays(7);}

                    @Override
                    public java.time.Duration reviewsTtl()       {return java.time.Duration.ofDays(3);}

                    @Override
                    public java.time.Duration imagesTtl()        {return java.time.Duration.ofDays(14);}

                    @Override
                    public java.time.Duration evictionInterval() {return java.time.Duration.ofHours(24);}
                };
            }

            @Override
            public ResearchConfig research() {
                return new ResearchConfig() {
                    @Override
                    public java.time.Duration maxSessionDuration() {return java.time.Duration.ofDays(180);}

                    @Override
                    public ResearchSqliteConfig sqlite()           {return () -> ":memory:";}
                };
            }

            @Override
            public NormalizationConfig normalization() {
                return () -> true;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static class SingleInstance<T> implements jakarta.enterprise.inject.Instance<T> {
        private final T value;
        SingleInstance(T value) { this.value = value; }
        @Override public Instance<T> select(java.lang.annotation.Annotation... q) { return this; }
        @Override public <U extends T> Instance<U> select(Class<U> s, java.lang.annotation.Annotation... q) { return (Instance<U>) this; }
        @Override public <U extends T> Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> s, java.lang.annotation.Annotation... q) { return (Instance<U>) this; }
        @Override public boolean isUnsatisfied() { return false; }
        @Override public boolean isAmbiguous() { return false; }
        @Override public boolean isResolvable() { return true; }
        @Override public void destroy(T instance) {}
        @Override public Handle<T> getHandle() { return null; }
        @Override public Iterable<? extends Handle<T>> handles() { return List.of(); }
        @Override public T get() { return value; }
        @Override public java.util.Iterator<T> iterator() { return List.of(value).iterator(); }
        @Override public java.util.stream.Stream<T> stream() { return java.util.stream.Stream.of(value); }
    }

    @SuppressWarnings("unchecked")
    static class EmptyInstance<T> implements jakarta.enterprise.inject.Instance<T> {
        @Override
        public Instance<T> select(java.lang.annotation.Annotation... qualifiers)                                                               {return this;}

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, java.lang.annotation.Annotation... qualifiers)                               {return (Instance<U>) this;}

        @Override
        public <U extends T> Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype, java.lang.annotation.Annotation... qualifiers) {return (Instance<U>) this;}

        @Override
        public boolean isUnsatisfied()                                                                                                         {return true;}

        @Override
        public boolean isAmbiguous()                                                                                                           {return false;}

        @Override
        public boolean isResolvable()                                                                                                          {return false;}

        @Override
        public void destroy(T instance)                                                                                                        {}

        @Override
        public Handle<T> getHandle()                                                                                                           {return null;}

        @Override
        public Iterable<? extends Handle<T>> handles()                                                                                         {return List.of();}

        @Override
        public T get()                                                                                                                         {throw new jakarta.enterprise.inject.UnsatisfiedResolutionException();}

        @Override
        public java.util.Iterator<T> iterator()                                                                                                {return java.util.Collections.emptyIterator();}

        @Override
        public java.util.stream.Stream<T> stream()                                                                                             {return java.util.stream.Stream.empty();}
    }
}
