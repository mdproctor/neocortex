package io.casehub.neocortex.knowledge;

import io.casehub.connectors.Page;
import io.casehub.connectors.PageRequest;
import io.casehub.connectors.location.model.Coordinates;
import io.casehub.connectors.location.model.Place;
import io.casehub.connectors.location.spi.LocationPlatform;
import io.casehub.neocortex.knowledge.cache.CacheDecayPolicy;
import io.casehub.neocortex.knowledge.cache.CacheEntityIdGenerator;
import io.casehub.neocortex.knowledge.cache.CacheKeyGenerator;
import io.casehub.neocortex.knowledge.cache.EntityMetadataStore;
import io.casehub.neocortex.knowledge.cache.QueryCacheStore;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.knowledge.promotion.EntityPromoter;
import io.casehub.neocortex.knowledge.resolution.EntityResolutionEngine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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

    private final List<LocationPlatform> providers;
    private final SpatialCacheStore cacheStore;
    private final QueryCacheStore queryCache;
    private final DedupIndexStore dedupStore;
    private final EntityMetadataStore metadataStore;
    private final EntityResolutionEngine resolutionEngine;
    private final EntityPromoter promoter;
    private final CacheDecayPolicy decayPolicy;
    private final SubsumptionRule subsumptionRule;
    private final int geohashPrecision;

    public KnowledgePipelineOrchestrator(
            List<LocationPlatform> providers,
            SpatialCacheStore cacheStore,
            QueryCacheStore queryCache,
            DedupIndexStore dedupStore,
            EntityMetadataStore metadataStore,
            EntityResolutionEngine resolutionEngine,
            EntityPromoter promoter,
            CacheDecayPolicy decayPolicy,
            SubsumptionRule subsumptionRule,
            int geohashPrecision) {
        this.providers = providers;
        this.cacheStore = cacheStore;
        this.queryCache = queryCache;
        this.dedupStore = dedupStore;
        this.metadataStore = metadataStore;
        this.resolutionEngine = resolutionEngine;
        this.promoter = promoter;
        this.decayPolicy = decayPolicy;
        this.subsumptionRule = subsumptionRule;
        this.geohashPrecision = geohashPrecision;
    }

    @Override
    public List<CachedEntity> search(KnowledgeQuery query, String tenantId) {
        return search(query, tenantId, null);
    }

    @Override
    public List<CachedEntity> search(KnowledgeQuery query, String tenantId,
                                      String researchSessionId) {
        NormalizedQuery normalized = CacheKeyGenerator.generate(query, geohashPrecision);

        var cacheHit = queryCache.lookup(normalized.cacheKey(), tenantId);
        if (cacheHit.isPresent()) {
            List<CachedEntity> cached = new ArrayList<>();
            for (String entityId : cacheHit.get().entityIds()) {
                CachedEntity entity = cacheStore.get(entityId, tenantId);
                if (entity != null) cached.add(entity);
            }
            if (!cached.isEmpty()) {
                if (researchSessionId != null) {
                    cached.forEach(e ->
                        metadataStore.addSession(e.id(), researchSessionId));
                }
                return cached;
            }
        }

        List<QueryCacheStore.QueryCacheEntry> cached = queryCache.listForTenant(tenantId);
        for (var entry : cached) {
            List<CachedEntity> broaderEntities = new ArrayList<>();
            for (String eid : entry.entityIds()) {
                CachedEntity e = cacheStore.get(eid, tenantId);
                if (e != null) broaderEntities.add(e);
            }
            if (broaderEntities.isEmpty()) continue;
            NormalizedQuery broaderNormalized = entry.toNormalizedQuery();
            var subsumed = subsumptionRule.subsume(normalized, broaderEntities, broaderNormalized);
            if (subsumed.isPresent()) {
                List<CachedEntity> result = subsumed.get();
                if (researchSessionId != null) {
                    result.forEach(e -> metadataStore.addSession(e.id(), researchSessionId));
                }
                return result;
            }
        }

        List<ProviderPlace> fetched = fetchFromProviders(query);
        if (fetched.isEmpty()) return List.of();

        Instant now = Instant.now();
        List<CachedEntity> entities = new ArrayList<>();
        for (ProviderPlace pp : fetched) {
            Place place = pp.place();
            String providerId = pp.providerId();
            String entityId = CacheEntityIdGenerator.generate(providerId, place.id());
            Instant expiresAt = now.plus(decayPolicy.coordinatesTtl());

            Map<String, String> props = new HashMap<>();
            if (place.phoneNumber() != null) props.put("phone", place.phoneNumber());
            if (place.website() != null) props.put("website", place.website());
            if (place.formattedAddress() != null) props.put("address", place.formattedAddress());
            if (place.priceLevel() != null) props.put("priceLevel", place.priceLevel().name());
            if (place.rating() != null) props.put("rating", String.valueOf(place.rating()));

            String category = place.types() != null && !place.types().isEmpty()
                ? place.types().get(0) : null;

            entities.add(new CachedEntity(
                entityId, place.name(), place.location(), category,
                providerId, place.id(), props, now, expiresAt,
                researchSessionId != null ? Set.of(researchSessionId) : Set.of(),
                false));
        }

        var resolution = resolutionEngine.resolve(entities, cacheStore, dedupStore, tenantId);
        List<CachedEntity> resolved = resolution.resolved();

        for (CachedEntity entity : resolved) {
            cacheStore.set(entity, tenantId);
            dedupStore.upsert(entity.source(), entity.externalId(), entity.id());
            if (researchSessionId != null) {
                metadataStore.addSession(entity.id(), researchSessionId);
            }
        }

        Instant searchExpiresAt = now.plus(decayPolicy.searchResultsTtl());
        List<String> entityIds = resolved.stream().map(CachedEntity::id).toList();
        String queryType = switch (query) {
            case KnowledgeQuery.TextSearch ignored -> "TEXT";
            case KnowledgeQuery.NearbySearch ignored -> "NEARBY";
            case KnowledgeQuery.CategorySearch ignored -> "CATEGORY";
        };
        Double lat = switch (query) {
            case KnowledgeQuery.NearbySearch n -> n.center().lat();
            case KnowledgeQuery.CategorySearch c -> c.center().lat();
            default -> null;
        };
        Double lng = switch (query) {
            case KnowledgeQuery.NearbySearch n -> n.center().lng();
            case KnowledgeQuery.CategorySearch c -> c.center().lng();
            default -> null;
        };
        Integer radius = switch (query) {
            case KnowledgeQuery.NearbySearch n -> n.radiusMeters();
            case KnowledgeQuery.CategorySearch c -> c.radiusMeters();
            default -> null;
        };
        String cat = query instanceof KnowledgeQuery.CategorySearch c ? c.category() : null;
        queryCache.record(normalized.cacheKey(), tenantId, entityIds, searchExpiresAt,
            queryType, lat, lng, radius, cat);

        return resolved;
    }

    @Override
    public PromotionResult promote(PromotionRequest request) {
        return promoter.promote(request);
    }

    @Override
    public void refreshStale(String tenantId) {
        // placeholder — will scan for stale fields and trigger selective re-fetch
    }

    record ProviderPlace(String providerId, Place place) {}

    private List<ProviderPlace> fetchFromProviders(KnowledgeQuery query) {
        List<ProviderPlace> all = new ArrayList<>();
        for (LocationPlatform provider : providers) {
            if (!provider.supports(LocationPlatform.PlaceSearch.class)) continue;
            try {
                List<Place> providerResults = fetchAllPages(provider, query);
                providerResults.forEach(p -> all.add(new ProviderPlace(provider.id(), p)));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Provider " + provider.id() + " failed", e);
            }
        }
        return all;
    }

    private List<Place> fetchAllPages(LocationPlatform provider, KnowledgeQuery query) {
        List<Place> all = new ArrayList<>();
        PageRequest pageRequest = PageRequest.first(DEFAULT_PAGE_SIZE);
        int pages = 0;

        while (pages < DEFAULT_MAX_PAGES) {
            Page<Place> page = executeFetch(provider, query, pageRequest);
            all.addAll(page.items());
            pages++;
            if (!page.hasMore() || page.nextCursor() == null) break;
            pageRequest = new PageRequest(page.nextCursor(), DEFAULT_PAGE_SIZE);
        }
        return all;
    }

    private Page<Place> executeFetch(LocationPlatform provider, KnowledgeQuery query,
                                      PageRequest pageRequest) {
        var search = provider.placeSearch("pipeline");
        return switch (query) {
            case KnowledgeQuery.TextSearch t -> search.searchByText(t.query(), pageRequest);
            case KnowledgeQuery.NearbySearch n ->
                search.searchNearby(n.center(), n.radiusMeters(), pageRequest);
            case KnowledgeQuery.CategorySearch c ->
                search.searchByCategory(c.category(), c.center(), c.radiusMeters(), pageRequest);
        };
    }

}
