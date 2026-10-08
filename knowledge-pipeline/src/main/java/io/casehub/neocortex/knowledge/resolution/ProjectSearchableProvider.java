package io.casehub.neocortex.knowledge.resolution;

import io.casehub.connectors.Page;
import io.casehub.connectors.PageRequest;
import io.casehub.connectors.project.model.Issue;
import io.casehub.connectors.project.model.OwnerRepo;
import io.casehub.connectors.project.spi.ProjectPlatform;
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
import java.util.stream.Collectors;

public class ProjectSearchableProvider implements SearchableProvider {

    private static final String DOMAIN = "projects";
    private static final int MAX_BODY_LENGTH = 500;
    private final ProjectPlatform platform;
    private final OwnerRepo defaultRepo;
    private final Duration entityTtl;

    public ProjectSearchableProvider(ProjectPlatform platform, OwnerRepo defaultRepo,
                                     Duration entityTtl) {
        this.platform = Objects.requireNonNull(platform);
        this.defaultRepo = Objects.requireNonNull(defaultRepo);
        this.entityTtl = Objects.requireNonNull(entityTtl);
    }

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String id() { return platform.id(); }

    @Override
    public PipelinePage<CachedEntity> search(KnowledgeQuery query, PipelinePageRequest page) {
        var issues = platform.issues("pipeline");
        PageRequest connectorPage = new PageRequest(page.cursor(), page.pageSize());

        Page<Issue> result = switch (query) {
            case KnowledgeQuery.TextSearch t -> issues.search(defaultRepo, t.query(), connectorPage);
            default -> throw new UnsupportedOperationException(
                "Unsupported query type for projects domain: " + query.getClass().getName());
        };

        Instant now = Instant.now();
        var entities = result.items().stream().map(i -> toEntity(i, now)).toList();
        return new PipelinePage<>(entities, result.nextCursor(), result.hasMore());
    }

    @Override
    public boolean supports(KnowledgeQuery query) {
        return query instanceof KnowledgeQuery.TextSearch;
    }

    private CachedEntity toEntity(Issue issue, Instant now) {
        String entityId = CacheEntityIdGenerator.generate(platform.id(), issue.id());
        Map<String, String> props = new HashMap<>();
        props.put("number", String.valueOf(issue.number()));
        if (issue.state() != null) props.put("state", issue.state());
        if (issue.body() != null) {
            String body = issue.body().length() > MAX_BODY_LENGTH
                ? issue.body().substring(0, MAX_BODY_LENGTH) : issue.body();
            props.put("body", body);
        }
        if (issue.assignees() != null && !issue.assignees().isEmpty()) {
            props.put("assignees", String.join(",", issue.assignees()));
        }
        if (issue.milestone() != null && issue.milestone().title() != null) {
            props.put("milestone", issue.milestone().title());
        }

        String category = issue.labels() != null && !issue.labels().isEmpty()
            ? issue.labels().get(0).name() : null;

        return new CachedEntity(entityId, issue.title(), null, category,
            platform.id(), issue.id(), props, now, null, now.plus(entityTtl),
            Set.of(), false, DOMAIN);
    }
}
