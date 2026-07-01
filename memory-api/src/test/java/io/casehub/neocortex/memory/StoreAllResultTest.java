package io.casehub.neocortex.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class StoreAllResultTest {

    static final MemoryInput INPUT = new MemoryInput("e1", new MemoryDomain("d"), "t1", null, "text", Map.of());

    // ── StoreAllResult construction ───────────────────────────────────────────

    @Test
    void empty_returns_empty_result() {
        var r = StoreAllResult.empty();
        assertThat(r.stored()).isEmpty();
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void allSucceeded_trueWhenNoFailures() {
        assertThat(new StoreAllResult(List.of("id-1"), List.of()).allSucceeded()).isTrue();
    }

    @Test
    void allSucceeded_falseWhenAnyFailure() {
        var failure = new StoreFailure(0, INPUT, new RuntimeException("oops"));
        assertThat(new StoreAllResult(List.of(), List.of(failure)).allSucceeded()).isFalse();
    }

    @Test
    void stored_isUnmodifiable() {
        var r = new StoreAllResult(List.of("id-1"), List.of());
        assertThatThrownBy(() -> r.stored().add("extra"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void failures_isUnmodifiable() {
        var r = new StoreAllResult(List.of(), List.of());
        assertThatThrownBy(() -> r.failures().add(new StoreFailure(0, INPUT, new RuntimeException())))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── StoreFailure ──────────────────────────────────────────────────────────

    @Test
    void storeFailure_exposesAllFields() {
        var cause = new IllegalStateException("backend failed");
        var f = new StoreFailure(2, INPUT, cause);
        assertThat(f.inputIndex()).isEqualTo(2);
        assertThat(f.input()).isSameAs(INPUT);
        assertThat(f.cause()).isSameAs(cause);
    }
}
