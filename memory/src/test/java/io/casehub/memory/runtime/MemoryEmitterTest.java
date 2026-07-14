package io.casehub.memory.runtime;

import io.casehub.neocortex.memory.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MemoryEmitterTest {

    private static final MemoryDomain DOMAIN = new MemoryDomain("test");
    private static final MemoryInput SAMPLE = new MemoryInput(
        "entity-1", DOMAIN, "tenant-1", null, "sample text", Map.of());

    @Test
    void emit_delegates_to_store() {
        var store = new RecordingStore();
        var emitter = new MemoryEmitter(store);

        emitter.emit(SAMPLE);

        assertThat(store.storedInputs).containsExactly(SAMPLE);
    }

    @Test
    void emitAll_delegates_to_storeAll() {
        var input2 = new MemoryInput("entity-2", DOMAIN, "tenant-1", null, "text 2", Map.of());
        var store = new RecordingStore();
        var emitter = new MemoryEmitter(store);

        emitter.emitAll(List.of(SAMPLE, input2));

        assertThat(store.storeAllInputs).hasSize(1);
        assertThat(store.storeAllInputs.getFirst()).containsExactly(SAMPLE, input2);
    }

    @Test
    void emitAll_empty_list_does_not_call_store() {
        var store = new RecordingStore();
        var emitter = new MemoryEmitter(store);

        emitter.emitAll(List.of());

        assertThat(store.storedInputs).isEmpty();
        assertThat(store.storeAllInputs).isEmpty();
    }

    @Test
    void emit_swallows_runtime_exception() {
        var store = new ThrowingStore(new RuntimeException("backend down"));
        var emitter = new MemoryEmitter(store);

        assertThatCode(() -> emitter.emit(SAMPLE)).doesNotThrowAnyException();
    }

    @Test
    void emitAll_swallows_runtime_exception() {
        var store = new ThrowingStore(new RuntimeException("backend down"));
        var emitter = new MemoryEmitter(store);

        assertThatCode(() -> emitter.emitAll(List.of(SAMPLE))).doesNotThrowAnyException();
    }

    @Test
    void emit_propagates_security_exception() {
        var store = new ThrowingStore(new SecurityException("tenant mismatch"));
        var emitter = new MemoryEmitter(store);

        assertThatThrownBy(() -> emitter.emit(SAMPLE))
            .isInstanceOf(SecurityException.class)
            .hasMessage("tenant mismatch");
    }

    @Test
    void emitAll_propagates_security_exception() {
        var store = new ThrowingStore(new SecurityException("tenant mismatch"));
        var emitter = new MemoryEmitter(store);

        assertThatThrownBy(() -> emitter.emitAll(List.of(SAMPLE)))
            .isInstanceOf(SecurityException.class)
            .hasMessage("tenant mismatch");
    }

    @Test
    void emitAll_logs_partial_failures_without_throwing() {
        var store = new PartialFailureStore();
        var emitter = new MemoryEmitter(store);

        assertThatCode(() -> emitter.emitAll(List.of(SAMPLE))).doesNotThrowAnyException();
    }

    // --- test doubles ---

    private static class RecordingStore implements CaseMemoryStore {
        final List<MemoryInput> storedInputs = new ArrayList<>();
        final List<List<MemoryInput>> storeAllInputs = new ArrayList<>();

        @Override
        public String store(MemoryInput input) {
            storedInputs.add(input);
            return "mem-" + storedInputs.size();
        }

        @Override
        public List<Memory> query(MemoryQuery query) { return List.of(); }

        @Override
        public int erase(EraseRequest request) { return 0; }

        @Override
        public StoreAllResult storeAll(List<MemoryInput> inputs) {
            storeAllInputs.add(inputs);
            return new StoreAllResult(
                inputs.stream().map(i -> "mem-" + i.entityId()).toList(), List.of());
        }
    }

    private static class ThrowingStore implements CaseMemoryStore {
        private final RuntimeException toThrow;

        ThrowingStore(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public String store(MemoryInput input) { throw toThrow; }

        @Override
        public List<Memory> query(MemoryQuery query) { return List.of(); }

        @Override
        public int erase(EraseRequest request) { return 0; }

        @Override
        public StoreAllResult storeAll(List<MemoryInput> inputs) { throw toThrow; }
    }

    private static class PartialFailureStore implements CaseMemoryStore {
        @Override
        public String store(MemoryInput input) { return ""; }

        @Override
        public List<Memory> query(MemoryQuery query) { return List.of(); }

        @Override
        public int erase(EraseRequest request) { return 0; }

        @Override
        public StoreAllResult storeAll(List<MemoryInput> inputs) {
            var failure = new StoreFailure(0, inputs.getFirst(), new RuntimeException("item failed"));
            return new StoreAllResult(List.of(), List.of(failure));
        }
    }
}
