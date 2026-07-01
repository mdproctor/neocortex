package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.ChangeType;
import io.casehub.neocortex.corpus.ChangedEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class FlatChangeSourceWatchTest {

    @Test
    void watchDetectsFileCreation(@TempDir Path tempDir) throws Exception {
        var store = new FlatCorpusStore(tempDir);
        var source = new FlatChangeSource(store, tempDir);
        var received = new CopyOnWriteArrayList<ChangedEntry>();

        source.watch(received::addAll);

        try {
            Thread.sleep(1000);
            Files.writeString(tempDir.resolve("new-file.txt"), "hello");

            await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(received).anyMatch(e ->
                            e.path().equals("new-file.txt") && e.type() == ChangeType.ADDED));
        } finally {
            source.close();
        }
    }

    @Test
    void watchDetectsFileDeletion(@TempDir Path tempDir) throws Exception {
        var store = new FlatCorpusStore(tempDir);
        store.append("doomed.txt", "goodbye".getBytes());

        var source = new FlatChangeSource(store, tempDir);
        var received = new CopyOnWriteArrayList<ChangedEntry>();

        source.watch(received::addAll);

        try {
            Thread.sleep(1000);
            Files.delete(tempDir.resolve("doomed.txt"));

            await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(received).anyMatch(e ->
                            e.path().equals("doomed.txt") && e.type() == ChangeType.DELETED));
        } finally {
            source.close();
        }
    }

    @Test
    void watchDetectsFileModification(@TempDir Path tempDir) throws Exception {
        var store = new FlatCorpusStore(tempDir);
        store.append("existing.txt", "original".getBytes());

        var source = new FlatChangeSource(store, tempDir);
        var received = new CopyOnWriteArrayList<ChangedEntry>();

        source.watch(received::addAll);

        try {
            Thread.sleep(1000);
            Files.writeString(tempDir.resolve("existing.txt"), "modified");

            await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(received).anyMatch(e ->
                            e.path().equals("existing.txt") && e.type() == ChangeType.MODIFIED));
        } finally {
            source.close();
        }
    }

    @Test
    void watchIgnoresReservedPaths(@TempDir Path tempDir) throws Exception {
        var store = new FlatCorpusStore(tempDir);
        var source = new FlatChangeSource(store, tempDir);
        var received = new CopyOnWriteArrayList<ChangedEntry>();

        source.watch(received::addAll);

        try {
            Thread.sleep(1000);
            Files.writeString(tempDir.resolve("_reserved.txt"), "skip me");
            Thread.sleep(100);
            Files.writeString(tempDir.resolve("normal.txt"), "include me");

            await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(received).anyMatch(e -> e.path().equals("normal.txt")));

            assertThat(received).noneMatch(e -> e.path().startsWith("_"));
        } finally {
            source.close();
        }
    }

    @Test
    void currentCursorReflectsWatchedState(@TempDir Path tempDir) throws Exception {
        var store = new FlatCorpusStore(tempDir);
        store.append("baseline.txt", "data".getBytes());
        var source = new FlatChangeSource(store, tempDir);
        var received = new CopyOnWriteArrayList<ChangedEntry>();

        source.watch(received::addAll);

        try {
            Thread.sleep(1000);
            Files.writeString(tempDir.resolve("added.txt"), "new data");

            await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(received).anyMatch(e -> e.path().equals("added.txt")));

            String cursor = source.currentCursor();
            assertThat(cursor).contains("baseline.txt");
            assertThat(cursor).contains("added.txt");
        } finally {
            source.close();
        }
    }

    @Test
    void closeStopsWatching(@TempDir Path tempDir) throws Exception {
        var store = new FlatCorpusStore(tempDir);
        var source = new FlatChangeSource(store, tempDir);
        var received = new CopyOnWriteArrayList<ChangedEntry>();

        source.watch(received::addAll);
        source.close();

        Files.writeString(tempDir.resolve("after-close.txt"), "should not detect");
        Thread.sleep(2000);

        assertThat(received).noneMatch(e -> e.path().equals("after-close.txt"));
    }

    @Test
    void doubleWatchThrows(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);
        var source = new FlatChangeSource(store, tempDir);

        source.watch(entries -> {});

        try {
            assertThatThrownBy(() -> source.watch(entries -> {}))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            source.close();
        }
    }

    @Test
    void pullMethodsStillWorkDuringWatch(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);
        store.append("a.txt", "a".getBytes());
        var source = new FlatChangeSource(store, tempDir);

        source.watch(entries -> {});

        try {
            var fullScan = source.fullScan();
            assertThat(fullScan.entries()).hasSize(1);
            assertThat(fullScan.entries().getFirst().path()).isEqualTo("a.txt");

            var delta = source.changesSince(fullScan.newCursor());
            assertThat(delta.entries()).isEmpty();
        } finally {
            source.close();
        }
    }
}
