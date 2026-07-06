package io.casehub.neocortex.rag.expansion;

import io.casehub.neocortex.rag.QueryExpander;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@DefaultBean
@ApplicationScoped
public class NoOpQueryExpander implements QueryExpander {

    @Override
    public List<RetrievalQuery> expand(RetrievalQuery query) {
        return List.of(query);
    }
}
