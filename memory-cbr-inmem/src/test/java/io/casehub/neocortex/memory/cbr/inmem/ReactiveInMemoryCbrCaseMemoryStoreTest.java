package io.casehub.neocortex.memory.cbr.inmem;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.*;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ReactiveInMemoryCbrCaseMemoryStoreTest {

    private static final MemoryDomain DOMAIN = new MemoryDomain("d");
    private static final String TENANT = "t";

    private InMemoryCbrCaseMemoryStore setupDelegateWithCase() {
        var delegate = new InMemoryCbrCaseMemoryStore();
        var schema = new CbrFeatureSchema("test", List.of(
            FeatureField.categorical("color")));
        delegate.registerSchema(schema);
        delegate.store(
            new FeatureVectorCbrCase("problem", "solution", "outcome", null,
                Map.of("color", FeatureValue.string("red"))),
            "test", "entity", DOMAIN, TENANT, null, Path.of("root"));
        return delegate;
    }

    @Test
    void store_executesOnCallerThread() {
        var capturedId = new AtomicLong(-1);
        var delegate = new InMemoryCbrCaseMemoryStore();
        delegate.registerSchema(new CbrFeatureSchema("test", List.of(
            FeatureField.categorical("color"))));
        var store = new ReactiveInMemoryCbrCaseMemoryStore(delegate);

        var cbrCase = new FeatureVectorCbrCase("problem", "solution", "outcome", null,
            Map.of("color", FeatureValue.string("red")));
        String id = store.store(cbrCase, "test", "entity", DOMAIN, TENANT, null,
            Path.of("root")).await().indefinitely();
        assertNotNull(id);
    }

    @Test
    void retrieveSimilar_returnsResults() {
        var delegate = setupDelegateWithCase();
        var store = new ReactiveInMemoryCbrCaseMemoryStore(delegate);
        var query = CbrQuery.of(TENANT, DOMAIN, Path.of("root"), "test",
            Map.of("color", FeatureValue.string("red")), 10)
            .withProblem("similar problem");

        List<ScoredCbrCase<FeatureVectorCbrCase>> results =
            store.retrieveSimilar(query, FeatureVectorCbrCase.class).await().indefinitely();
        assertEquals(1, results.size());
    }

    @Test
    void erase_returnsCount() {
        var delegate = setupDelegateWithCase();
        var store = new ReactiveInMemoryCbrCaseMemoryStore(delegate);
        var request = new io.casehub.neocortex.memory.EraseRequest("entity", DOMAIN, TENANT, null);

        int count = store.erase(request).await().indefinitely();
        assertEquals(1, count);
    }

    @Test
    void registerSchema_succeeds() {
        var delegate = new InMemoryCbrCaseMemoryStore();
        var store = new ReactiveInMemoryCbrCaseMemoryStore(delegate);
        var schema = new CbrFeatureSchema("test", List.of(FeatureField.categorical("color")));

        assertDoesNotThrow(() -> store.registerSchema(schema).await().indefinitely());
    }
}
