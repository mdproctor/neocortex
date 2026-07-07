package io.casehub.neocortex.rag.expansion;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.QueryExpander;
import io.casehub.neocortex.rag.ReactiveCaseRetriever;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.RrfFusion;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Decorator
@Priority(200)
@Unremovable
@IfBuildProperty(name = "casehub.rag.expansion.enabled", stringValue = "true")
public class ReactiveQueryExpandingCaseRetriever implements ReactiveCaseRetriever {

    private static final Logger LOG = Logger.getLogger(ReactiveQueryExpandingCaseRetriever.class.getName());

    private final ReactiveCaseRetriever delegate;
    private final QueryExpander expander;

    @Inject
    public ReactiveQueryExpandingCaseRetriever(@Delegate @Any ReactiveCaseRetriever delegate,
                                               QueryExpander expander) {
        this.delegate = delegate;
        this.expander = expander;
        LOG.fine(() -> "Reactive query expansion decorator active, expander: " + expander.getClass().getSimpleName());
    }

    @Override
    public Uni<List<RetrievedChunk>> retrieve(RetrievalQuery query, CorpusRef corpus,
                                               int maxResults, PayloadFilter filter) {
        LOG.fine(() -> "Intercepting reactive retrieve for corpus " + corpus + ", query: " + query.text());

        return Uni.createFrom().item(() -> expander.expand(query))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .onFailure().recoverWithItem(t -> {
                LOG.log(Level.WARNING, "Query expansion failed, using original query", t);
                return List.of(query);
            })
            .map(expanded -> expanded.isEmpty() ? List.of(query) : expanded)
            .map(expanded -> {
                if (!expanded.contains(query)) {
                    var withOriginal = new ArrayList<RetrievalQuery>(expanded.size() + 1);
                    withOriginal.add(query);
                    withOriginal.addAll(expanded);
                    return withOriginal;
                }
                return expanded;
            })
            .chain(expanded -> {
                // Single-query fast path: skip RRF fusion
                if (expanded.size() == 1) {
                    return delegate.retrieve(expanded.get(0), corpus, maxResults, filter);
                }

                // Multi-query path: fan out concurrently and merge via RRF
                List<Uni<List<RetrievedChunk>>> retrievalUnis = expanded.stream()
                    .map(expandedQuery -> delegate.retrieve(expandedQuery, corpus, maxResults, filter))
                    .collect(Collectors.toList());

                return Uni.combine().all().unis(retrievalUnis).with(resultSets -> {
                    // Type-erased List<?> from combinator - cast to List<List<RetrievedChunk>>
                    @SuppressWarnings("unchecked")
                    List<List<RetrievedChunk>> typedResultSets = (List<List<RetrievedChunk>>) resultSets;
                    return RrfFusion.fuse(typedResultSets, maxResults);
                });
            });
    }
}
