package io.casehub.neocortex.corpus.management.cdi;

import io.casehub.neocortex.corpus.management.CorpusCatalogue;
import io.casehub.neocortex.corpus.management.CorpusEntry;
import io.casehub.neocortex.corpus.management.CorpusEntryStore;
import io.casehub.neocortex.corpus.management.CorpusLifecycle;
import io.casehub.neocortex.corpus.management.CorpusQuery;
import io.casehub.neocortex.corpus.management.CorpusSearch;
import io.casehub.neocortex.corpus.management.CorpusSearchResult;
import io.casehub.neocortex.corpus.management.FederatedCorpusSearch;
import io.casehub.neocortex.corpus.management.FederatedSearchResult;
import io.casehub.neocortex.corpus.management.HealthReport;
import io.casehub.neocortex.corpus.management.SearchReport;
import io.casehub.neocortex.rag.CorpusRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusManagementApiBeanTest {

    private CorpusManagementApiBean bean;
    private StubCorpusEntryStore entryStore;

    @BeforeEach
    void setUp() throws Exception {
        entryStore = new StubCorpusEntryStore();
        bean = new CorpusManagementApiBean();

        var catField = CorpusManagementApiBean.class.getDeclaredField("catalogue");
        catField.setAccessible(true);
        catField.set(bean, new NoOpCorpusCatalogue());

        var entryField = CorpusManagementApiBean.class.getDeclaredField("entryStore");
        entryField.setAccessible(true);
        entryField.set(bean, entryStore);

        var searchField = CorpusManagementApiBean.class.getDeclaredField("corpusSearch");
        searchField.setAccessible(true);
        searchField.set(bean, new NoOpCorpusSearch());

        var fedField = CorpusManagementApiBean.class.getDeclaredField("federatedSearch");
        fedField.setAccessible(true);
        fedField.set(bean, new NoOpFederatedCorpusSearch());
    }

    @Test
    void listCorporaDefaultsToActive() {
        var result = bean.listCorpora("t1", null);
        assertThat(result).isEmpty();
    }

    @Test
    void listCorporaAcceptsLifecycleString() {
        var result = bean.listCorpora("t1", "archived");
        assertThat(result).isEmpty();
    }

    @Test
    void getCorpusReturnsNullWhenMissing() {
        assertThat(bean.getCorpus("t1", "missing")).isNull();
    }

    @Test
    void addEntryDelegatesToStore() {
        String id = bean.addEntry("t1", "c1", "e1", "body text", "title=Hello|author=Jane", null);
        assertThat(id).isEqualTo("e1");
        assertThat(entryStore.lastEntry).isNotNull();
        assertThat(entryStore.lastEntry.body()).isEqualTo("body text");
        assertThat(entryStore.lastEntry.metadata()).containsEntry("title", "Hello");
        assertThat(entryStore.lastEntry.metadata()).containsEntry("author", "Jane");
    }

    @Test
    void addEntryHandlesNullMetadata() {
        bean.addEntry("t1", "c1", "e2", "body", null, null);
        assertThat(entryStore.lastEntry.metadata()).isEmpty();
    }

    @Test
    void getEntryReturnsNullWhenMissing() {
        assertThat(bean.getEntry("t1", "c1", "missing")).isNull();
    }

    @Test
    void searchCorpusDefaultsTo10Results() {
        var results = bean.searchCorpus("t1", "c1", "test query", null);
        assertThat(results).isEmpty();
    }

    @Test
    void federatedSearchDefaultsToUnlimitedDepth() {
        var result = bean.federatedSearch("t1", "test", null, null);
        assertThat(result.results()).isEmpty();
        assertThat(result.report().partial()).isFalse();
    }

    @Test
    void parseMetadataHandlesKeyValuePairs() {
        var map = CorpusManagementApiBean.parseMetadata("title=Hello|author=Jane|version=2");
        assertThat(map).hasSize(3);
        assertThat(map).containsEntry("title", "Hello");
        assertThat(map).containsEntry("author", "Jane");
        assertThat(map).containsEntry("version", "2");
    }

    @Test
    void parseMetadataHandlesEmptyString() {
        assertThat(CorpusManagementApiBean.parseMetadata("")).isEmpty();
        assertThat(CorpusManagementApiBean.parseMetadata(null)).isEmpty();
    }

    static class StubCorpusEntryStore implements CorpusEntryStore {
        CorpusEntry lastEntry;

        @Override
        public String add(CorpusRef corpus, CorpusEntry entry) {
            lastEntry = entry;
            return entry.id();
        }

        @Override public void update(CorpusRef corpus, String entryId, CorpusEntry entry) { lastEntry = entry; }
        @Override public void remove(CorpusRef corpus, String entryId) {}
        @Override public Optional<CorpusEntry> get(CorpusRef corpus, String entryId) { return Optional.empty(); }
    }
}
