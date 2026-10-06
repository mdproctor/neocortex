package io.casehub.neocortex.knowledge;

import io.casehub.connectors.location.spi.LocationPlatform;
import io.casehub.neocortex.knowledge.cache.CacheDecayPolicy;
import io.casehub.neocortex.knowledge.cache.EntityMetadataStore;
import io.casehub.neocortex.knowledge.cache.QueryCacheStore;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.knowledge.promotion.EntityPromoter;
import io.casehub.neocortex.knowledge.resolution.EntityResolutionEngine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class KnowledgePipelineOrchestrator implements KnowledgePipelineService {

    private static final Logger LOG = Logger.getLogger(
        KnowledgePipelineOrchestrator.class.getName());

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int DEFAULT_MAX_PAGES = 3;

    private final DomainRegistry domainRegistry;
    private final CacheStore cacheStore;
    private final QueryCacheStore queryCache;
    private final DedupIndexStore dedupStore;
    private final EntityMetadataStore metadataStore;
    private final EntityResolutionEngine resolutionEngine;
    private final EntityPromoter promoter;
    private final CacheDecayPolicy decayPolicy;
    private final List<LocationPlatform> locationProviders;
    private       KnowledgePipelineMetrics metrics;

    public KnowledgePipelineOrchestrator(
            DomainRegistry domainRegistry,
            CacheStore cacheStore,
            QueryCacheStore queryCache,
            DedupIndexStore dedupStore,
            EntityMetadataStore metadataStore,
            EntityResolutionEngine resolutionEngine,
            EntityPromoter promoter,
            CacheDecayPolicy decayPolicy,
            List<LocationPlatform> locationProviders) {
        this.domainRegistry = domainRegistry;
        this.cacheStore = cacheStore;
        this.queryCache = queryCache;
        this.dedupStore = dedupStore;
        this.metadataStore = metadataStore;
        this.resolutionEngine = resolutionEngine;
        this.promoter = promoter;
        this.decayPolicy = decayPolicy;
        this.locationProviders = locationProviders;
    }

    void setMetrics(KnowledgePipelineMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public List<CachedEntity> search(KnowledgeQuery query, String tenantId) {
        return search(query, tenantId, null);
    }

    @Override
    public List<CachedEntity> search(KnowledgeQuery query, String tenantId,
                                      String researchSessionId) {
        String domain = query.domain() != null ? query.domain() : "location";
        DomainSupport domainSupport = domainRegistry.lookup(domain)
            .orElseThrow(() -> new UnsupportedOperationException(
                "No domain registered: " + domain));

        Map<String, ExpandedTerm> expansions = normalize(query, domainSupport);
        NormalizedQuery normalized = domainSupport.keyGenerator()
            .generate(query, expansions);

        var cacheHit = queryCache.lookup(normalized.cacheKey(), tenantId);
        if (cacheHit.isPresent()) {
            List<CachedEntity> cached = new ArrayList<>();
            for (String entityId : cacheHit.get().entityIds()) {
                CachedEntity entity = cacheStore.get(entityId, tenantId);
                if (entity != null) cached.add(entity);
            }
            if (!cached.isEmpty()) {
                if (metrics != null) metrics.recordCacheHit(domain, tenantId);
                if (researchSessionId != null) {
                    cached.forEach(e ->
                        metadataStore.addSession(e.id(), researchSessionId));
                }
                return cached;
            }
        }

        List<QueryCacheStore.QueryCacheEntry> cachedQueries = queryCache.listForTenant(tenantId);
        for (var entry : cachedQueries) {
            List<CachedEntity> broaderEntities = new ArrayList<>();
            for (String eid : entry.entityIds()) {
                CachedEntity e = cacheStore.get(eid, tenantId);
                if (e != null) broaderEntities.add(e);
            }
            if (broaderEntities.isEmpty()) continue;
            NormalizedQuery broaderNormalized = entry.toNormalizedQuery();
            var subsumed = domainSupport.subsumption()
                .subsume(normalized, broaderEntities, broaderNormalized);
            if (subsumed.isPresent()) {
                List<CachedEntity> result = subsumed.get();
                if (researchSessionId != null) {
                    result.forEach(e -> metadataStore.addSession(e.id(), researchSessionId));
                }
                return result;
            }
        }

        if (metrics != null) metrics.recordCacheMiss(domain, tenantId);

        List<CachedEntity> fetched = fetchFromDomain(query, domainSupport);
        if (fetched.isEmpty()) return List.of();

        if (researchSessionId != null) {
            fetched = fetched.stream()
                .map(e -> new CachedEntity(e.id(), e.name(), e.coordinates(),
                    e.category(), e.source(), e.externalId(), e.properties(),
                    e.fetchedAt(), e.detailFetchedAt(), e.expiresAt(),
                    Set.of(researchSessionId), e.hasDetail(), e.domain()))
                .toList();
        }

        var resolution = resolutionEngine.resolve(
            fetched, domainSupport.blockingStrategy(), cacheStore, dedupStore, tenantId);
        List<CachedEntity> resolved = resolution.resolved();

        for (CachedEntity entity : resolved) {
            cacheStore.set(entity, tenantId);
            dedupStore.upsert(entity.source(), entity.externalId(), entity.id());
            if (researchSessionId != null) {
                metadataStore.addSession(entity.id(), researchSessionId);
            }
        }

        Instant searchExpiresAt = Instant.now().plus(decayPolicy.searchResultsTtl());
        List<String> entityIds = resolved.stream().map(CachedEntity::id).toList();
        recordQueryCache(normalized.cacheKey(), tenantId, entityIds,
            searchExpiresAt, query, domain);

        return resolved;
    }

    @Override
    public PromotionResult promote(PromotionRequest request) {
        return promoter.promote(request);
    }

    @Override
    public void refreshStale(String tenantId) {
        List<CachedEntity> entities = cacheStore.listAll(tenantId);
        Instant now = Instant.now();
        int refreshed = 0;

        for (CachedEntity entity : entities) {
            try {
                var stale = decayPolicy.staleGroups(entity, now);
                if (stale.isEmpty()) continue;

                if (stale.contains(CacheDecayPolicy.StaleFieldGroup.DETAIL)
                        && "location".equals(entity.domain())) {
                    refreshed += refreshLocationDetail(entity, tenantId, now);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Refresh failed for entity " + entity.id(), e);
            }
        }

        if (refreshed > 0) {
            LOG.info("Refreshed " + refreshed + " stale entities for tenant " + tenantId);
        }
    }

    private int refreshLocationDetail(CachedEntity entity, String tenantId, Instant now) {
        for (LocationPlatform provider : locationProviders) {
            if (!provider.supports(LocationPlatform.PlaceDetails.class)) continue;
            if (!provider.id().equals(entity.source())) continue;
            try {
                var details = provider.placeDetails("pipeline").get(entity.externalId());
                if (details != null) {
                    Map<String, String> props = new HashMap<>(entity.properties());
                    if (details.phoneNumber() != null) props.put("phone", details.phoneNumber());
                    if (details.website() != null) props.put("website", details.website());
                    if (details.formattedAddress() != null) {
                        props.put("address", details.formattedAddress());
                    }
                    if (details.priceLevel() != null) {
                        props.put("priceLevel", details.priceLevel().name());
                    }
                    if (details.rating() != null) {
                        props.put("rating", String.valueOf(details.rating()));
                    }

                    CachedEntity updated = new CachedEntity(
                        entity.id(), entity.name(), entity.coordinates(),
                        entity.category(), entity.source(), entity.externalId(),
                        props, entity.fetchedAt(), now, entity.expiresAt(),
                        entity.sessionIds(), true, entity.domain());
                    cacheStore.set(updated, tenantId);
                    return 1;
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Detail refresh failed for " + entity.id(), e);
            }
            break;
        }
        return 0;
    }

    private void recordQueryCache(String cacheKey, String tenantId,
                                   List<String> entityIds, Instant expiresAt,
                                   KnowledgeQuery query, String domain) {
        String queryType;
        Double lat = null, lng = null;
        Integer radius = null;
        String cat = null;
        switch (query) {
            case KnowledgeQuery.TextSearch ignored -> queryType = "TEXT";
            case KnowledgeQuery.NearbySearch n -> {
                queryType = "NEARBY";
                lat = n.center().lat();
                lng = n.center().lng();
                radius = n.radiusMeters();
            }
            case KnowledgeQuery.CategorySearch c -> {
                queryType = "CATEGORY";
                lat = c.center().lat();
                lng = c.center().lng();
                radius = c.radiusMeters();
                cat = c.category();
            }
            default -> queryType = domain.toUpperCase();
        }
        queryCache.record(cacheKey, tenantId, entityIds, expiresAt,
            queryType, lat, lng, radius, cat);
    }

    private Map<String, ExpandedTerm> normalize(KnowledgeQuery query,
                                                 DomainSupport domainSupport) {
        if (!(query instanceof KnowledgeQuery.TextSearch textSearch)) {
            return Map.of();
        }
        Map<String, ExpandedTerm> expansions = new LinkedHashMap<>();
        String text = textSearch.query().toLowerCase().strip();
        String[] tokens = text.split("\\s+");
        for (String token : tokens) {
            for (TermNormalizer normalizer : domainSupport.normalizerChain()) {
                ExpandedTerm expanded = normalizer.normalize(token, query.domain());
                if (!token.equals(expanded.canonical()) || expanded.variants().size() > 1) {
                    expansions.put(token, expanded);
                    break;
                }
            }
        }
        return expansions;
    }

    private List<CachedEntity> fetchFromDomain(KnowledgeQuery query,
                                                DomainSupport domainSupport) {
        SearchableProvider provider = domainSupport.provider();
        if (!provider.supports(query)) {
            LOG.warning("Provider " + provider.id() + " does not support query: "
                + query.getClass().getSimpleName());
            return List.of();
        }

        List<CachedEntity> all = new ArrayList<>();
        PipelinePageRequest pageRequest = PipelinePageRequest.first(DEFAULT_PAGE_SIZE);
        int pages = 0;

        while (pages < DEFAULT_MAX_PAGES) {
            try {
                PipelinePage<CachedEntity> page = provider.search(query, pageRequest);
                all.addAll(page.items());
                pages++;
                if (!page.hasMore() || page.nextCursor() == null) break;
                pageRequest = new PipelinePageRequest(page.nextCursor(), DEFAULT_PAGE_SIZE);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Provider " + provider.id() + " failed", e);
                if (metrics != null) metrics.recordProviderError(provider.id(), "");
                break;
            }
        }
        return all;
    }
}
