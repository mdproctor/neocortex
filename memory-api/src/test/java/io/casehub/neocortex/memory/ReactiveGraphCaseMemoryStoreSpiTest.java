package io.casehub.neocortex.memory;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReactiveGraphCaseMemoryStoreSpiTest {

    @Test
    void defaultGraphQuery_returnsEmptyList() {
        ReactiveGraphCaseMemoryStore store = new ReactiveGraphCaseMemoryStore() {
            @Override public Uni<String> store(MemoryInput input) { return Uni.createFrom().item(""); }
            @Override public Uni<List<Memory>> query(MemoryQuery query) { return Uni.createFrom().item(List.of()); }
            @Override public Uni<Integer> erase(EraseRequest request) { return Uni.createFrom().item(0); }
        };
        List<Memory> result = store.graphQuery(
            GraphMemoryQuery.forEntity("entity", new MemoryDomain("d"), "tenant", "question"))
            .await().indefinitely();
        assertTrue(result.isEmpty());
    }

    @Test
    void subtypeIsAssignableToReactiveCaseMemoryStore() {
        ReactiveGraphCaseMemoryStore store = new ReactiveGraphCaseMemoryStore() {
            @Override public Uni<String> store(MemoryInput input) { return Uni.createFrom().item(""); }
            @Override public Uni<List<Memory>> query(MemoryQuery query) { return Uni.createFrom().item(List.of()); }
            @Override public Uni<Integer> erase(EraseRequest request) { return Uni.createFrom().item(0); }
        };
        assertInstanceOf(ReactiveCaseMemoryStore.class, store);
    }
}
