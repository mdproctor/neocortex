package io.casehub.neocortex.knowledge;

import java.util.List;

public final class NoOpSearchableProvider implements SearchableProvider {

    private final String domain;

    public NoOpSearchableProvider(String domain) {
        this.domain = domain;
    }

    @Override
    public String domain() {
        return domain;
    }

    @Override
    public String id() {
        return domain + "-noop";
    }

    @Override
    public PipelinePage<CachedEntity> search(KnowledgeQuery query, PipelinePageRequest page) {
        return new PipelinePage<>(List.of(), null, false);
    }

    @Override
    public boolean supports(KnowledgeQuery query) {
        return false;
    }
}
