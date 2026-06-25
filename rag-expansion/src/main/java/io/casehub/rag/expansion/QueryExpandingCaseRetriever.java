package io.casehub.rag.expansion;

import io.casehub.rag.CaseRetriever;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
import io.casehub.rag.QueryExpander;
import io.casehub.rag.RetrievalQuery;
import io.casehub.rag.RetrievedChunk;
import io.casehub.rag.RrfFusion;
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
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus,
                                          int maxResults, PayloadFilter filter) {
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

        return RrfFusion.fuse(resultSets, maxResults);
    }
}
