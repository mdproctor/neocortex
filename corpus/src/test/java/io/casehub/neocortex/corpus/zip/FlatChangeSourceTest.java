package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.ChangeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FlatChangeSourceTest {

    @Test
    void fullScanReturnsAllFiles(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);
        var changeSource = new FlatChangeSource(store, tempDir);

        store.append("a.txt", "a".getBytes());
        store.append("b.txt", "b".getBytes());
        store.append("sub/c.txt", "c".getBytes());

        var changeSet = changeSource.fullScan();

        assertEquals(3, changeSet.entries().size());
        assertTrue(changeSet.entries().stream()
            .allMatch(e -> e.type() == ChangeType.ADDED));
        assertTrue(changeSet.entries().stream()
            .anyMatch(e -> e.path().equals("a.txt")));
        assertTrue(changeSet.entries().stream()
            .anyMatch(e -> e.path().equals("b.txt")));
        assertTrue(changeSet.entries().stream()
            .anyMatch(e -> e.path().equals("sub/c.txt")));
        assertNotNull(changeSet.newCursor());
    }

    @Test
    void changesSinceDetectsNewFiles(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);
        var changeSource = new FlatChangeSource(store, tempDir);

        store.append("existing.txt", "content".getBytes());
        var cursor = changeSource.fullScan().newCursor();

        store.append("new.txt", "new content".getBytes());

        var changeSet = changeSource.changesSince(cursor);

        assertEquals(1, changeSet.entries().size());
        assertEquals("new.txt", changeSet.entries().get(0).path());
        assertEquals(ChangeType.ADDED, changeSet.entries().get(0).type());
    }

    @Test
    void changesSinceDetectsDeletedFiles(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);
        var changeSource = new FlatChangeSource(store, tempDir);

        store.append("file.txt", "content".getBytes());
        var cursor = changeSource.fullScan().newCursor();

        store.delete("file.txt");

        var changeSet = changeSource.changesSince(cursor);

        assertEquals(1, changeSet.entries().size());
        assertEquals("file.txt", changeSet.entries().get(0).path());
        assertEquals(ChangeType.DELETED, changeSet.entries().get(0).type());
    }

    @Test
    void changesSinceDetectsModifiedFiles(@TempDir Path tempDir) throws IOException, InterruptedException {
        var store = new FlatCorpusStore(tempDir);
        var changeSource = new FlatChangeSource(store, tempDir);

        store.append("file.txt", "original".getBytes());
        var cursor = changeSource.fullScan().newCursor();

        // Sleep briefly to ensure mtime changes
        TimeUnit.MILLISECONDS.sleep(10);

        store.append("file.txt", "modified".getBytes());

        var changeSet = changeSource.changesSince(cursor);

        assertEquals(1, changeSet.entries().size());
        assertEquals("file.txt", changeSet.entries().get(0).path());
        assertEquals(ChangeType.MODIFIED, changeSet.entries().get(0).type());
    }

    @Test
    void changesSinceWithNothingNewReturnsEmpty(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);
        var changeSource = new FlatChangeSource(store, tempDir);

        store.append("file.txt", "content".getBytes());
        var cursor = changeSource.fullScan().newCursor();

        var changeSet = changeSource.changesSince(cursor);

        assertTrue(changeSet.entries().isEmpty());
        assertNotNull(changeSet.newCursor());
    }

    @Test
    void fullScanWithEmptyDirectoryReturnsEmptyChangeSet(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);
        var changeSource = new FlatChangeSource(store, tempDir);

        var changeSet = changeSource.fullScan();

        assertTrue(changeSet.entries().isEmpty());
        assertNotNull(changeSet.newCursor());
    }

    @Test
    void changesSinceWithNullCursorBehavesLikeFullScan(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);
        var changeSource = new FlatChangeSource(store, tempDir);

        store.append("a.txt", "a".getBytes());
        store.append("b.txt", "b".getBytes());

        var changeSet = changeSource.changesSince(null);

        assertEquals(2, changeSet.entries().size());
        assertTrue(changeSet.entries().stream()
            .allMatch(e -> e.type() == ChangeType.ADDED));
    }

    @Test
    void changesSinceDetectsMultipleChangeTypes(@TempDir Path tempDir) throws InterruptedException {
        var store = new FlatCorpusStore(tempDir);
        var changeSource = new FlatChangeSource(store, tempDir);

        store.append("existing.txt", "content".getBytes());
        store.append("to-delete.txt", "delete me".getBytes());
        store.append("to-modify.txt", "original".getBytes());
        var cursor = changeSource.fullScan().newCursor();

        TimeUnit.MILLISECONDS.sleep(10);

        store.append("new.txt", "new".getBytes());
        store.delete("to-delete.txt");
        store.append("to-modify.txt", "modified".getBytes());

        var changeSet = changeSource.changesSince(cursor);

        assertEquals(3, changeSet.entries().size());
        assertTrue(changeSet.entries().stream()
            .anyMatch(e -> e.path().equals("new.txt") && e.type() == ChangeType.ADDED));
        assertTrue(changeSet.entries().stream()
            .anyMatch(e -> e.path().equals("to-delete.txt") && e.type() == ChangeType.DELETED));
        assertTrue(changeSet.entries().stream()
            .anyMatch(e -> e.path().equals("to-modify.txt") && e.type() == ChangeType.MODIFIED));
    }
}
