package io.casehub.neocortex.knowledge.resolution;

import io.casehub.connectors.Page;
import io.casehub.connectors.PageRequest;
import io.casehub.connectors.location.model.Place;
import io.casehub.connectors.location.spi.LocationPlatform;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.PipelinePage;
import io.casehub.neocortex.knowledge.PipelinePageRequest;
import io.casehub.neocortex.knowledge.SearchableProvider;
import io.casehub.neocortex.knowledge.cache.CacheEntityIdGenerator;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class SpatialSearchableProvider implements SearchableProvider {

    private static final String DOMAIN = "location";

    private final LocationPlatform platform;
    private final Duration entityTtl;

    public SpatialSearchableProvider(LocationPlatform platform, Duration entityTtl) {
        this.platform = Objects.requireNonNull(platform);
        this.entityTtl = Objects.requireNonNull(entityTtl);
    }

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public String id() {
        return platform.id();
    }

    @Override
    public PipelinePage<CachedEntity> search(KnowledgeQuery query, PipelinePageRequest page) {
        var search = platform.placeSearch("pipeline");
        PageRequest connectorPage = new PageRequest(page.cursor(), page.pageSize());

        Page<Place> result = switch (query) {
            case KnowledgeQuery.TextSearch t -> search.searchByText(t.query(), connectorPage);
            case KnowledgeQuery.NearbySearch n ->
                search.searchNearby(n.center(), n.radiusMeters(), connectorPage);
            case KnowledgeQuery.CategorySearch c ->
                search.searchByCategory(c.category(), c.center(), c.radiusMeters(), connectorPage);
            default -> throw new UnsupportedOperationException(
                "Unsupported query type for location domain: " + query.getClass().getName());
        };

        Instant now = Instant.now();
        var entities = result.items().stream()
            .map(place -> toEntity(place, now))
            .toList();

        return new PipelinePage<>(entities, result.nextCursor(), result.hasMore());
    }

    @Override
    public boolean supports(KnowledgeQuery query) {
        return query instanceof KnowledgeQuery.TextSearch
            || query instanceof KnowledgeQuery.NearbySearch
            || query instanceof KnowledgeQuery.CategorySearch;
    }

    private CachedEntity toEntity(Place place, Instant now) {
        String entityId = CacheEntityIdGenerator.generate(platform.id(), place.id());
        Instant expiresAt = now.plus(entityTtl);

        Map<String, String> props = new HashMap<>();
        if (place.phoneNumber() != null) props.put("phone", place.phoneNumber());
        if (place.website() != null) props.put("website", place.website());
        if (place.formattedAddress() != null) props.put("address", place.formattedAddress());
        if (place.priceLevel() != null) props.put("priceLevel", place.priceLevel().name());
        if (place.rating() != null) props.put("rating", String.valueOf(place.rating()));

        String category = place.types() != null && !place.types().isEmpty()
            ? place.types().get(0) : null;

        return new CachedEntity(
            entityId, place.name(), place.location(), category,
            platform.id(), place.id(), props, now, null, expiresAt,
            Set.of(), false, DOMAIN);
    }
}
