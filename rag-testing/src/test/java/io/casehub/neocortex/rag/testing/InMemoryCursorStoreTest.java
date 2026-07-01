package io.casehub.neocortex.rag.testing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCursorStoreTest {

    private InMemoryCursorStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryCursorStore();
    }

    @Test
    void loadReturnsEmptyWhenNoCursorSaved() {
        assertThat(store.load("garden")).isEmpty();
    }

    @Test
    void saveAndLoad() {
        store.save("garden", "{\"tools/git.md\":1}");
        assertThat(store.load("garden")).contains("{\"tools/git.md\":1}");
    }

    @Test
    void saveOverwritesPreviousCursor() {
        store.save("garden", "cursor-1");
        store.save("garden", "cursor-2");
        assertThat(store.load("garden")).contains("cursor-2");
    }

    @Test
    void isolationBetweenCorpora() {
        store.save("garden", "cursor-g");
        store.save("legal", "cursor-l");
        assertThat(store.load("garden")).contains("cursor-g");
        assertThat(store.load("legal")).contains("cursor-l");
    }

    @Test
    void resetClearsAllCursors() {
        store.save("garden", "cursor-1");
        store.save("legal", "cursor-2");
        store.reset();
        assertThat(store.load("garden")).isEmpty();
        assertThat(store.load("legal")).isEmpty();
    }

    @Test
    void getAllReturnsSnapshot() {
        store.save("garden", "c1");
        store.save("legal", "c2");
        var all = store.getAll();
        assertThat(all).containsEntry("garden", "c1").containsEntry("legal", "c2");
    }

    @Test
    void deleteRemovesCursor() {
        store.save("garden", "cursor-1");
        store.delete("garden");
        assertThat(store.load("garden")).isEmpty();
    }

    @Test
    void deleteNonExistentIsNoOp() {
        store.delete("nonexistent"); // no exception
        assertThat(store.load("nonexistent")).isEmpty();
    }
}
