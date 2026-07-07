package io.casehub.neocortex.rag.crossencoder.reranking;

import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.ReactiveCaseRetriever;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import java.util.List;

@Decorator
@Priority(75)
@Unremovable
@IfBuildProperty(name = "casehub.rag.reranking.enabled", stringValue = "true")
public class ReactiveRerankingCaseRetriever implements ReactiveCaseRetriever {

    private final ReactiveCaseRetriever delegate;
    private final CrossEncoderReranker reranker;
    private final RerankingConfig config;

    @Inject
    ReactiveRerankingCaseRetriever(@Delegate @Any ReactiveCaseRetriever delegate,
                                    CrossEncoderReranker reranker,
                                    RerankingConfig config) {
        this.delegate = delegate;
        this.reranker = reranker;
        this.config = config;
    }

    @Override
    public Uni<List<RetrievedChunk>> retrieve(RetrievalQuery query, CorpusRef corpus,
                                               int maxResults, PayloadFilter filter) {
        int fetchSize = Math.max(maxResults, config.rerankPoolSize());
        return delegate.retrieve(query, corpus, fetchSize, filter)
            .onItem().transformToUni(candidates -> {
                if (RerankingLogic.isAlreadyReranked(candidates)) {
                    return Uni.createFrom().item(
                        candidates.subList(0, Math.min(candidates.size(), maxResults)));
                }
                return Uni.createFrom().item(() ->
                    RerankingLogic.stamp(
                        RerankingLogic.rerank(reranker, query.text(),
                            candidates, maxResults)))
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
            });
    }
}
