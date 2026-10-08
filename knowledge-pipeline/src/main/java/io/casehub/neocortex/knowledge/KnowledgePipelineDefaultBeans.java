package io.casehub.neocortex.knowledge;

import com.zaxxer.hikari.HikariDataSource;
import io.casehub.connectors.commerce.spi.CommercePlatform;
import io.casehub.connectors.contacts.spi.ContactsPlatform;
import io.casehub.connectors.document.spi.DocumentPlatform;
import io.casehub.connectors.location.spi.LocationPlatform;
import io.casehub.connectors.project.model.OwnerRepo;
import io.casehub.connectors.project.spi.ProjectPlatform;
import io.casehub.neocortex.knowledge.cache.CacheDecayPolicy;
import io.casehub.neocortex.knowledge.cache.CacheEvictionScheduler;
import io.casehub.neocortex.knowledge.cache.EntityMetadataStore;
import io.casehub.neocortex.knowledge.cache.QueryCacheStore;
import io.casehub.neocortex.knowledge.cache.SpatialCacheKeyGenerator;
import io.casehub.neocortex.knowledge.cache.SpatialSubsumptionRule;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.knowledge.promotion.EntityPromoter;
import io.casehub.neocortex.knowledge.research.ResearchSessionStore;
import io.casehub.neocortex.knowledge.resolution.CompositeSearchableProvider;
import io.casehub.neocortex.knowledge.resolution.EntityResolutionEngine;
import io.casehub.neocortex.knowledge.resolution.PlaceMatcher;
import io.casehub.neocortex.knowledge.resolution.SpatialBlockingStrategy;
import io.casehub.neocortex.knowledge.resolution.CommerceSearchableProvider;
import io.casehub.neocortex.knowledge.resolution.ContactsSearchableProvider;
import io.casehub.neocortex.knowledge.resolution.DocumentSearchableProvider;
import io.casehub.neocortex.knowledge.resolution.ProjectSearchableProvider;
import io.casehub.neocortex.knowledge.resolution.SpatialSearchableProvider;
import io.casehub.neocortex.knowledge.cache.NormalizingTextCacheKeyGenerator;
import io.casehub.neocortex.knowledge.normalization.PhoneticTermNormalizer;
import io.casehub.neocortex.knowledge.normalization.StemmingTermNormalizer;
import io.casehub.neocortex.knowledge.normalization.WordNetTermNormalizer;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

import java.util.List;

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
            DomainRegistry domainRegistry,
            SpatialCacheStore cacheStore,
            QueryCacheStore queryCache,
            DedupIndexStore dedupStore,
            EntityMetadataStore metadataStore,
            EntityResolutionEngine resolutionEngine,
            EntityPromoter promoter,
            CacheDecayPolicy decayPolicy,
            Instance<LocationPlatform> locationProviders,
            KnowledgePipelineMetrics metrics) {
        var orch = new KnowledgePipelineOrchestrator(
                domainRegistry, cacheStore, queryCache, dedupStore,
                metadataStore, resolutionEngine, promoter, decayPolicy,
                locationProviders.stream().toList());
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

    @Produces
    @DefaultBean
    @ApplicationScoped
    @Named("location")
    DomainSupport spatialDomainSupport(
            Instance<LocationPlatform> platforms,
            SpatialCacheStore spatialCacheStore,
            CacheDecayPolicy decayPolicy,
            SubsumptionRule subsumptionRule,
            EntityMatcher<CachedEntity> entityMatcher,
            WordNetTermNormalizer normalizer,
            KnowledgePipelineConfig config) {
        List<SearchableProvider> providers = platforms.stream()
                .filter(p -> p.supports(LocationPlatform.PlaceSearch.class))
                .map(p -> (SearchableProvider) new SpatialSearchableProvider(
                        p, decayPolicy.coordinatesTtl()))
                .toList();

        SearchableProvider provider;
        if (providers.isEmpty()) {
            provider = DomainSupport.minimal("location",
                    new io.casehub.neocortex.knowledge.NoOpSearchableProvider("location")).provider();
        } else if (providers.size() == 1) {
            provider = providers.get(0);
        } else {
            provider = new CompositeSearchableProvider("location", providers);
        }

        return new DomainSupport("location", provider,
                new SpatialCacheKeyGenerator(config.geohashPrecision()),
                subsumptionRule, entityMatcher,
                new SpatialBlockingStrategy(spatialCacheStore, 200),
                List.of(normalizer));
    }


    @Produces
    @DefaultBean
    @ApplicationScoped
    @Named("commerce")
    DomainSupport commerceDomainSupport(
            Instance<CommercePlatform> platforms,
            CacheDecayPolicy decayPolicy,
            WordNetTermNormalizer wordnet) {
        List<SearchableProvider> providers = platforms.stream()
                                                      .filter(p -> p.supports(CommercePlatform.ProductSearch.class))
                                                      .map(p -> (SearchableProvider) new CommerceSearchableProvider(p, decayPolicy.coordinatesTtl()))
                                                      .toList();
        SearchableProvider provider = resolveProvider("commerce", providers);
        return new DomainSupport("commerce", provider,
                                 NormalizingTextCacheKeyGenerator.INSTANCE,
                                 TextSubsumptionRule.INSTANCE, NameEntityMatcher.INSTANCE,
                                 NoOpBlockingStrategy.INSTANCE, List.of(wordnet));
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    @Named("contacts")
    DomainSupport contactsDomainSupport(
            Instance<ContactsPlatform> platforms,
            CacheDecayPolicy decayPolicy,
            PhoneticTermNormalizer phonetic) {
        List<SearchableProvider> providers = platforms.stream()
                                                      .filter(p -> p.supports(ContactsPlatform.ContactRead.class))
                                                      .map(p -> (SearchableProvider) new ContactsSearchableProvider(p, decayPolicy.coordinatesTtl()))
                                                      .toList();
        SearchableProvider provider = resolveProvider("contacts", providers);
        return new DomainSupport("contacts", provider,
                                 NormalizingTextCacheKeyGenerator.INSTANCE,
                                 TextSubsumptionRule.INSTANCE, NameEntityMatcher.INSTANCE,
                                 NoOpBlockingStrategy.INSTANCE, List.of(phonetic));
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    @Named("documents")
    DomainSupport documentsDomainSupport(
            Instance<DocumentPlatform> platforms,
            CacheDecayPolicy decayPolicy,
            StemmingTermNormalizer stemmer,
            WordNetTermNormalizer wordnet) {
        List<SearchableProvider> providers = platforms.stream()
                                                      .filter(p -> p.supports(DocumentPlatform.SearchOperations.class))
                                                      .map(p -> (SearchableProvider) new DocumentSearchableProvider(p, decayPolicy.coordinatesTtl()))
                                                      .toList();
        SearchableProvider provider = resolveProvider("documents", providers);
        return new DomainSupport("documents", provider,
                                 NormalizingTextCacheKeyGenerator.INSTANCE,
                                 TextSubsumptionRule.INSTANCE, NameEntityMatcher.INSTANCE,
                                 NoOpBlockingStrategy.INSTANCE, List.of(stemmer, wordnet));
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    @Named("projects")
    DomainSupport projectsDomainSupport(
            Instance<ProjectPlatform> platforms,
            CacheDecayPolicy decayPolicy,
            StemmingTermNormalizer stemmer,
            WordNetTermNormalizer wordnet) {
        List<SearchableProvider> providers = platforms.stream()
                                                      .filter(p -> p.supports(ProjectPlatform.Issues.class))
                                                      .map(p -> (SearchableProvider) new ProjectSearchableProvider(
                                                              p, new OwnerRepo("casehubio", "neocortex"), decayPolicy.coordinatesTtl()))
                                                      .toList();
        SearchableProvider provider = resolveProvider("projects", providers);
        return new DomainSupport("projects", provider,
                                 NormalizingTextCacheKeyGenerator.INSTANCE,
                                 TextSubsumptionRule.INSTANCE, NameEntityMatcher.INSTANCE,
                                 NoOpBlockingStrategy.INSTANCE, List.of(stemmer, wordnet));
    }

    private SearchableProvider resolveProvider(String domain, List<SearchableProvider> providers) {
        if (providers.isEmpty()) {
            return new NoOpSearchableProvider(domain);
        } else if (providers.size() == 1) {
            return providers.get(0);
        } else {
            return new CompositeSearchableProvider(domain, providers);
        }
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    DomainRegistry domainRegistry(@Any Instance<DomainSupport> domains) {
        return new DomainRegistry(domains.stream().toList());
    }

}
