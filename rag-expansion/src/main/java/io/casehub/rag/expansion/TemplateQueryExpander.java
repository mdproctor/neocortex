package io.casehub.rag.expansion;

import io.casehub.rag.QueryExpander;
import io.casehub.rag.RetrievalQuery;
import java.util.List;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@IfBuildProperty(name = "casehub.rag.expansion.mode", stringValue = "template")
public class TemplateQueryExpander implements QueryExpander {

    static final String DEFAULT_TEMPLATE =
        "A document that answers the question \"%s\" would contain the following information:";

    private final ExpansionConfig config;

    @Inject
    public TemplateQueryExpander(ExpansionConfig config) {
        this.config = config;
    }

    @Override
    public List<RetrievalQuery> expand(RetrievalQuery query) {
        String expanded = config.template().orElse(DEFAULT_TEMPLATE)
            .formatted(query.text());
        return List.of(query.withExpansion(expanded));
    }
}
