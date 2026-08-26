package io.casehub.neocortex.mindmap.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.testing.MindMapStoreContractTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteMindMapStoreTest extends MindMapStoreContractTest {

    @Override
    protected MindMapStore createStore() {
        SqliteMindMapStore store = new SqliteMindMapStore();
        store.path = ":memory:";
        store.maxPoolSize = 1;
        store.busyTimeoutMs = 5000;
        store.objectMapper = new ObjectMapper();
        store.init();
        return store;
    }

    @Test
    void fts5SearchMatchesNodeName() {
        String id = store.addNode(nodeInput("Artificial Intelligence"), TENANT);
        store.addNode(nodeInput("Machine Learning"), TENANT);

        var results = store.search(new io.casehub.neocortex.mindmap.MindMapQuery(
            TENANT, null, "Artificial", null, null, null, null, false, 10));
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().name()).isEqualTo("Artificial Intelligence");
    }
}
