package io.casehub.neocortex.corpus.management.cdi;

import io.casehub.neocortex.corpus.management.CorpusEntry;
import io.casehub.neocortex.corpus.management.CorpusLifecycle;
import io.casehub.neocortex.corpus.management.CorpusQuery;
import io.casehub.neocortex.corpus.management.CorpusType;
import io.casehub.neocortex.corpus.management.HealthReport;
import io.casehub.neocortex.rag.CorpusRef;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpBeansTest {

    private final NoOpCorpusCatalogue catalogue = new NoOpCorpusCatalogue();
    private final NoOpCorpusEntryStore entryStore = new NoOpCorpusEntryStore();
    private final NoOpFederatedCorpusSearch federatedSearch = new NoOpFederatedCorpusSearch();
    private final NoOpCorpusSearch corpusSearch = new NoOpCorpusSearch();

    @Test
    void catalogueReturnsEmpty() {
        assertThat(catalogue.list("t1", CorpusLifecycle.ACTIVE)).isEmpty();
        assertThat(catalogue.get(new CorpusRef("t1", "c1"))).isEmpty();
        assertThat(catalogue.forType(new CorpusType("test", Map.of(), Map.of(), null), "t1")).isEmpty();
        assertThat(catalogue.searchScope(new CorpusRef("t1", "c1"))).isEmpty();
        assertThat(catalogue.reconcile("t1")).isEqualTo(HealthReport.HEALTHY);
    }

    @Test
    void entryStoreReturnsIdAndEmpty() {
        var entry = new CorpusEntry("e1", Map.of(), "body", null);
        assertThat(entryStore.add(new CorpusRef("t1", "c1"), entry)).isEqualTo("e1");
        assertThat(entryStore.get(new CorpusRef("t1", "c1"), "e1")).isEmpty();
    }

    @Test
    void federatedSearchReturnsEmpty() {
        var result = federatedSearch.search(CorpusQuery.of("test"), "t1");
        assertThat(result.results()).isEmpty();
        assertThat(result.report().partial()).isFalse();
    }

    @Test
    void corpusSearchReturnsEmpty() {
        assertThat(corpusSearch.search(new CorpusRef("t1", "c1"), CorpusQuery.of("test"))).isEmpty();
    }
}
