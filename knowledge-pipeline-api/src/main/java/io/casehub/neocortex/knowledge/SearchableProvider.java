package io.casehub.neocortex.knowledge;

public interface SearchableProvider {
    String domain();
    String id();
    PipelinePage<CachedEntity> search(KnowledgeQuery query, PipelinePageRequest page);
    boolean supports(KnowledgeQuery query);
}
