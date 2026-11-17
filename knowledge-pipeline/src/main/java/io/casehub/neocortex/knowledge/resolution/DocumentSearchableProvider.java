package io.casehub.neocortex.knowledge.resolution;

import io.casehub.connectors.Page;
import io.casehub.connectors.PageRequest;
import io.casehub.connectors.document.model.DocumentSummary;
import io.casehub.connectors.document.spi.DocumentPlatform;
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

public class DocumentSearchableProvider implements SearchableProvider {

    private static final String DOMAIN = "documents";
    private final DocumentPlatform platform;
    private final Duration entityTtl;

    public DocumentSearchableProvider(DocumentPlatform platform, Duration entityTtl) {
        this.platform = Objects.requireNonNull(platform);
        this.entityTtl = Objects.requireNonNull(entityTtl);
    }

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String id() { return platform.id(); }

    @Override
    public PipelinePage<CachedEntity> search(KnowledgeQuery query, PipelinePageRequest page) {
        var search = platform.search();
        PageRequest connectorPage = new PageRequest(page.cursor(), page.pageSize());

        Page<DocumentSummary> result = switch (query) {
            case KnowledgeQuery.TextSearch t -> search.search(t.query(), connectorPage);
            default -> throw new UnsupportedOperationException(
                "Unsupported query type for documents domain: " + query.getClass().getName());
        };

        Instant now = Instant.now();
        var entities = result.items().stream().map(d -> toEntity(d, now)).toList();
        return new PipelinePage<>(entities, result.nextCursor(), result.hasMore());
    }

    @Override
    public boolean supports(KnowledgeQuery query) {
        return query instanceof KnowledgeQuery.TextSearch;
    }

    private CachedEntity toEntity(DocumentSummary doc, Instant now) {
        String entityId = CacheEntityIdGenerator.generate(platform.id(), doc.id());
        Map<String, String> props = new HashMap<>();
        if (doc.folderId() != null) props.put("folderId", doc.folderId());
        if (doc.contentType() != null) props.put("contentType", doc.contentType());
        props.put("size", String.valueOf(doc.size()));
        if (doc.createdAt() != null) props.put("createdAt", doc.createdAt().toString());
        if (doc.modifiedAt() != null) props.put("modifiedAt", doc.modifiedAt().toString());

        return new CachedEntity(entityId, doc.name(), null, doc.contentType(),
            platform.id(), doc.id(), props, now, null, now.plus(entityTtl),
            Set.of(), false, DOMAIN);
    }
}
