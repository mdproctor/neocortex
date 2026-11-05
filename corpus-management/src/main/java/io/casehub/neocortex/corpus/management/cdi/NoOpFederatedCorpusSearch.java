package io.casehub.neocortex.corpus.management.cdi;

import io.casehub.neocortex.corpus.management.CorpusQuery;
import io.casehub.neocortex.corpus.management.FederatedCorpusSearch;
import io.casehub.neocortex.corpus.management.FederatedSearchResult;
import io.casehub.neocortex.corpus.management.SearchReport;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@DefaultBean
@ApplicationScoped
public class NoOpFederatedCorpusSearch implements FederatedCorpusSearch {

    @Override
    public FederatedSearchResult search(CorpusQuery query, String tenantId) {
        return new FederatedSearchResult(List.of(), new SearchReport(List.of(), List.of(), false));
    }
}
