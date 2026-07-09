package io.casehub.neocortex.rag.expansion;

import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.QueryExpander;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.fusion.ScoreFusion;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Decorator
@Priority(200)
@Unremovable
@IfBuildProperty(name = "casehub.rag.expansion.enabled", stringValue = "true")
public class QueryExpandingCaseRetriever implements CaseRetriever {

    private static final Logger LOG = Logger.getLogger(QueryExpandingCaseRetriever.class.getName());

    private final CaseRetriever delegate;
    private final QueryExpander expander;

    @Inject
    public QueryExpandingCaseRetriever(@Delegate @Any CaseRetriever delegate,
                                       QueryExpander expander) {
        this.delegate = delegate;
        this.expander = expander;
        LOG.fine(() -> "Query expansion decorator active, expander: " + expander.getClass().getSimpleName());
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus,
                                          int maxResults, PayloadFilter filter) {
        LOG.fine(() -> "Intercepting retrieve for corpus " + corpus + ", query: " + query.text());

        List<RetrievalQuery> expanded;
        try {
            expanded = expander.expand(query);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Query expansion failed, using original query", e);
            expanded = List.of(query);
        }

        if (expanded.isEmpty()) {
            expanded = List.of(query);
        }

        // Ensure original query is in the expanded set
        if (!expanded.contains(query)) {
            var withOriginal = new ArrayList<RetrievalQuery>(expanded.size() + 1);
            withOriginal.add(query);
            withOriginal.addAll(expanded);
            expanded = withOriginal;
        }

        // Single-query fast path: skip RRF fusion
        if (expanded.size() == 1) {
            return delegate.retrieve(expanded.get(0), corpus, maxResults, filter);
        }

        // Multi-query path: fan out retrievals and merge via RRF
        var resultSets = new ArrayList<List<RetrievedChunk>>(expanded.size());
        for (var expandedQuery : expanded) {
            var results = delegate.retrieve(expandedQuery, corpus, maxResults, filter);
            resultSets.add(results);
        }

        List<ScoreFusion.ScoredLeg<RetrievedChunk>> legs = resultSets.stream()
            .map(rs -> new ScoreFusion.ScoredLeg<>(rs, RetrievedChunk::relevanceScore, 1.0))
            .toList();
        return ScoreFusion.rrf(legs, RetrievedChunk::fusionKey, maxResults, 60)
            .stream().map(f -> f.item().withRelevanceScore(f.score())).toList();
    }
}
