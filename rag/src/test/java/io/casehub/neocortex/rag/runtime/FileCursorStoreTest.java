package io.casehub.neocortex.rag.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileCursorStoreTest {

    @TempDir
    Path tempDir;
    private FileCursorStore store;

    @BeforeEach
    void setUp() {
        store = new FileCursorStore(tempDir.toString());
    }

    @Test
    void loadReturnsEmptyWhenNoFile() {
        assertThat(store.load("garden")).isEmpty();
    }

    @Test
    void saveAndLoad() {
        store.save("garden", "{\"tools/git.md\":1}");
        assertThat(store.load("garden")).contains("{\"tools/git.md\":1}");
    }

    @Test
    void saveOverwritesPrevious() {
        store.save("garden", "cursor-1");
        store.save("garden", "cursor-2");
        assertThat(store.load("garden")).contains("cursor-2");
    }

    @Test
    void isolationBetweenCorpora() {
        store.save("garden", "cg");
        store.save("legal", "cl");
        assertThat(store.load("garden")).contains("cg");
        assertThat(store.load("legal")).contains("cl");
    }

    @Test
    void createsDirectoryIfMissing() {
        Path nested = tempDir.resolve("sub/dir");
        var nestedStore = new FileCursorStore(nested.toString());
        nestedStore.save("garden", "cursor");
        assertThat(nestedStore.load("garden")).contains("cursor");
        assertThat(Files.exists(nested)).isTrue();
    }

    @Test
    void cursorFileNameMatchesCorpus() {
        store.save("garden", "cursor");
        assertThat(Files.exists(tempDir.resolve("garden.cursor"))).isTrue();
    }

    @Test
    void emptyFileReturnsEmpty() throws Exception {
        Files.writeString(tempDir.resolve("garden.cursor"), "");
        assertThat(store.load("garden")).isEmpty();
    }

    @Test
    void whitespaceOnlyFileReturnsEmpty() throws Exception {
        Files.writeString(tempDir.resolve("garden.cursor"), "   \n  ");
        assertThat(store.load("garden")).isEmpty();
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

    @Test
    void deleteRemovesFile() {
        store.save("garden", "cursor-1");
        assertThat(Files.exists(tempDir.resolve("garden.cursor"))).isTrue();
        store.delete("garden");
        assertThat(Files.exists(tempDir.resolve("garden.cursor"))).isFalse();
    }
}
