package io.casehub.neocortex.knowledge.resolution;

import io.casehub.connectors.Page;
import io.casehub.connectors.PageRequest;
import io.casehub.connectors.commerce.model.Product;
import io.casehub.connectors.commerce.spi.CommercePlatform;
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

public class CommerceSearchableProvider implements SearchableProvider {

    private static final String DOMAIN = "commerce";
    private final CommercePlatform platform;
    private final Duration entityTtl;

    public CommerceSearchableProvider(CommercePlatform platform, Duration entityTtl) {
        this.platform = Objects.requireNonNull(platform);
        this.entityTtl = Objects.requireNonNull(entityTtl);
    }

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String id() { return platform.id(); }

    @Override
    public PipelinePage<CachedEntity> search(KnowledgeQuery query, PipelinePageRequest page) {
        var search = platform.productSearch("pipeline");
        PageRequest connectorPage = new PageRequest(page.cursor(), page.pageSize());

        Page<Product> result = switch (query) {
            case KnowledgeQuery.TextSearch t -> search.search(t.query(), connectorPage);
            default -> throw new UnsupportedOperationException(
                "Unsupported query type for commerce domain: " + query.getClass().getName());
        };

        Instant now = Instant.now();
        var entities = result.items().stream().map(p -> toEntity(p, now)).toList();
        return new PipelinePage<>(entities, result.nextCursor(), result.hasMore());
    }

    @Override
    public boolean supports(KnowledgeQuery query) {
        return query instanceof KnowledgeQuery.TextSearch;
    }

    private CachedEntity toEntity(Product product, Instant now) {
        String entityId = CacheEntityIdGenerator.generate(platform.id(), product.id());
        Map<String, String> props = new HashMap<>();
        if (product.brand() != null) props.put("brand", product.brand());
        if (product.price() != null) {
            props.put("price", product.price().amount() + " " + product.price().currency());
        }
        if (product.rating() != null) props.put("rating", String.valueOf(product.rating()));
        if (product.reviewCount() != null) props.put("reviewCount", String.valueOf(product.reviewCount()));
        props.put("inStock", String.valueOf(product.inStock()));
        if (product.thumbnailUrl() != null) props.put("thumbnailUrl", product.thumbnailUrl());

        return new CachedEntity(entityId, product.name(), null, product.category(),
            platform.id(), product.id(), props, now, null, now.plus(entityTtl),
            Set.of(), false, DOMAIN);
    }
}
