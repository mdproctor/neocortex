package io.casehub.neocortex.knowledge.resolution;

import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.PipelinePage;
import io.casehub.neocortex.knowledge.PipelinePageRequest;
import io.casehub.neocortex.knowledge.SearchableProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CompositeSearchableProvider implements SearchableProvider {

    private static final Logger LOG = Logger.getLogger(
        CompositeSearchableProvider.class.getName());

    private final String domain;
    private final List<SearchableProvider> delegates;

    public CompositeSearchableProvider(String domain, List<SearchableProvider> delegates) {
        this.domain = Objects.requireNonNull(domain);
        this.delegates = List.copyOf(delegates);
        if (delegates.isEmpty()) {
            throw new IllegalArgumentException("At least one delegate provider required");
        }
    }

    @Override
    public String domain() {
        return domain;
    }

    @Override
    public String id() {
        return domain;
    }

    @Override
    public PipelinePage<CachedEntity> search(KnowledgeQuery query, PipelinePageRequest page) {
        List<CachedEntity> merged = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (SearchableProvider delegate : delegates) {
            if (!delegate.supports(query)) continue;
            try {
                PipelinePage<CachedEntity> result = delegate.search(query, page);
                for (CachedEntity entity : result.items()) {
                    if (entity.externalId() != null && !seenIds.add(
                            entity.source() + ":" + entity.externalId())) {
                        continue;
                    }
                    merged.add(entity);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Provider " + delegate.id() + " failed", e);
            }
        }

        return new PipelinePage<>(merged, null, false);
    }

    @Override
    public boolean supports(KnowledgeQuery query) {
        return delegates.stream().anyMatch(d -> d.supports(query));
    }

    public List<SearchableProvider> delegates() {
        return delegates;
    }
}
