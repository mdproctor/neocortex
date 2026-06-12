package io.casehub.corpus.zip;

import io.casehub.corpus.ChangeSet;
import io.casehub.corpus.ChangeType;
import io.casehub.corpus.ChangedEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ZipChangeSourceTest {

    @TempDir
    Path tempDir;

    private ZipCorpusStore createStore() {
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 10_000_000);
        return new ZipCorpusStore(config);
    }

    @Test
    void fullScanReturnsAllEntries() {
        ZipCorpusStore store = createStore();
        store.append("docs/a.txt", "content a".getBytes());
        store.append("docs/b.txt", "content b".getBytes());
        store.append("src/Main.java", "class Main {}".getBytes());

        ZipChangeSource source = new ZipChangeSource(store);
        ChangeSet result = source.fullScan();

        assertEquals(3, result.entries().size());
        assertTrue(result.entries().stream()
                .allMatch(e -> e.type() == ChangeType.ADDED));
        assertNotNull(result.newCursor());

        List<String> paths = result.entries().stream()
                .map(ChangedEntry::path)
                .sorted()
                .toList();
        assertEquals(List.of("docs/a.txt", "docs/b.txt", "src/Main.java"), paths);
    }

    @Test
    void changesSinceNullEquivalentToFullScan() {
        ZipCorpusStore store = createStore();
        store.append("docs/a.txt", "content a".getBytes());
        store.append("docs/b.txt", "content b".getBytes());

        ZipChangeSource source = new ZipChangeSource(store);
        ChangeSet fullScan = source.fullScan();
        ChangeSet changesFromNull = source.changesSince(null);

        assertEquals(fullScan.entries().size(), changesFromNull.entries().size());
        assertEquals(fullScan.newCursor(), changesFromNull.newCursor());
    }

    @Test
    void changesSinceDetectsNewEntries() {
        ZipCorpusStore store = createStore();
        store.append("docs/a.txt", "content a".getBytes());
        store.append("docs/b.txt", "content b".getBytes());

        ZipChangeSource source = new ZipChangeSource(store);
        String cursor1 = source.fullScan().newCursor();

        // Add more entries
        store.append("docs/c.txt", "content c".getBytes());
        store.append("src/Main.java", "class Main {}".getBytes());

        ChangeSet changes = source.changesSince(cursor1);

        assertEquals(2, changes.entries().size());
        assertTrue(changes.entries().stream()
                .allMatch(e -> e.type() == ChangeType.ADDED));

        List<String> newPaths = changes.entries().stream()
                .map(ChangedEntry::path)
                .sorted()
                .toList();
        assertEquals(List.of("docs/c.txt", "src/Main.java"), newPaths);
    }

    @Test
    void changesSinceDetectsModifiedEntries() {
        ZipCorpusStore store = createStore();
        store.append("docs/a.txt", "version 1".getBytes());
        store.append("docs/b.txt", "version 1".getBytes());

        ZipChangeSource source = new ZipChangeSource(store);
        String cursor1 = source.fullScan().newCursor();

        // Modify one entry
        store.append("docs/a.txt", "version 2".getBytes());

        ChangeSet changes = source.changesSince(cursor1);

        assertEquals(1, changes.entries().size());
        ChangedEntry changed = changes.entries().get(0);
        assertEquals("docs/a.txt", changed.path());
        assertEquals(ChangeType.MODIFIED, changed.type());
    }

    @Test
    void changesSinceDetectsDeletedEntries() {
        ZipCorpusStore store = createStore();
        store.append("docs/a.txt", "content a".getBytes());
        store.append("docs/b.txt", "content b".getBytes());
        store.append("docs/c.txt", "content c".getBytes());

        ZipChangeSource source = new ZipChangeSource(store);
        String cursor1 = source.fullScan().newCursor();

        // Delete one entry
        store.delete("docs/b.txt");

        ChangeSet changes = source.changesSince(cursor1);

        assertEquals(1, changes.entries().size());
        ChangedEntry changed = changes.entries().get(0);
        assertEquals("docs/b.txt", changed.path());
        assertEquals(ChangeType.DELETED, changed.type());
    }

    @Test
    void changesSinceWithNothingNewReturnsEmpty() {
        ZipCorpusStore store = createStore();
        store.append("docs/a.txt", "content a".getBytes());

        ZipChangeSource source = new ZipChangeSource(store);
        String cursor1 = source.fullScan().newCursor();

        // No changes
        ChangeSet changes = source.changesSince(cursor1);

        assertTrue(changes.entries().isEmpty());
        assertNotNull(changes.newCursor());
    }

    @Test
    void cursorIsStableAcrossInstances() {
        ZipCorpusStore store = createStore();
        store.append("docs/a.txt", "content a".getBytes());
        store.append("docs/b.txt", "content b".getBytes());

        ZipChangeSource source1 = new ZipChangeSource(store);
        String cursor = source1.fullScan().newCursor();

        // Add more entries
        store.append("docs/c.txt", "content c".getBytes());

        // Create new ZipChangeSource instance
        ZipChangeSource source2 = new ZipChangeSource(store);
        ChangeSet changes = source2.changesSince(cursor);

        assertEquals(1, changes.entries().size());
        assertEquals("docs/c.txt", changes.entries().get(0).path());
        assertEquals(ChangeType.ADDED, changes.entries().get(0).type());
    }

    @Test
    void changesSinceCombinesMultipleChangeTypes() {
        ZipCorpusStore store = createStore();
        store.append("docs/a.txt", "version 1".getBytes());
        store.append("docs/b.txt", "version 1".getBytes());
        store.append("docs/c.txt", "version 1".getBytes());

        ZipChangeSource source = new ZipChangeSource(store);
        String cursor = source.fullScan().newCursor();

        // Make multiple changes
        store.append("docs/a.txt", "version 2".getBytes()); // MODIFIED
        store.delete("docs/b.txt");                          // DELETED
        store.append("docs/d.txt", "new".getBytes());        // ADDED

        ChangeSet changes = source.changesSince(cursor);

        assertEquals(3, changes.entries().size());

        long addedCount = changes.entries().stream()
                .filter(e -> e.type() == ChangeType.ADDED).count();
        long modifiedCount = changes.entries().stream()
                .filter(e -> e.type() == ChangeType.MODIFIED).count();
        long deletedCount = changes.entries().stream()
                .filter(e -> e.type() == ChangeType.DELETED).count();

        assertEquals(1, addedCount);
        assertEquals(1, modifiedCount);
        assertEquals(1, deletedCount);
    }
}
