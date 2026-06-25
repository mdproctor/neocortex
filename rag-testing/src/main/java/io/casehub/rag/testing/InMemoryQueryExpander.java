package io.casehub.rag.testing;

import io.casehub.rag.QueryExpander;
import io.casehub.rag.RetrievalQuery;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.ArrayList;
import java.util.List;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryQueryExpander implements QueryExpander {

    private final List<RetrievalQuery> expandedQueries = new ArrayList<>();

    @Override
    public List<RetrievalQuery> expand(RetrievalQuery query) {
        RetrievalQuery expanded = query.withExpansion("hypothetical: " + query.text());
        expandedQueries.add(expanded);
        return List.of(expanded);
    }

    public List<RetrievalQuery> expandedQueries() {
        return List.copyOf(expandedQueries);
    }

    public void clear() {
        expandedQueries.clear();
    }
}
