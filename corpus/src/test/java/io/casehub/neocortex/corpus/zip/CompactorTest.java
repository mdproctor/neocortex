package io.casehub.neocortex.corpus.zip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CompactorTest {

    @TempDir
    Path tempDir;

    @Test
    void tombstonesOnlyRemovesTombstones() throws IOException {
        // Create ZipCorpusStore and append entries + tombstones
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 1024);
        ZipCorpusStore store = new ZipCorpusStore(config);

        store.append("docs/readme.md", "v1".getBytes());
        store.append("docs/guide.md", "v1".getBytes());
        store.delete("docs/guide.md");
        store.append("docs/api.md", "v1".getBytes());

        // Trigger rollover
        forceRollover(store, config);

        // Load manifest
        ChainManifest manifest = ChainManifest.load(tempDir.resolve("chain.json"));
        List<ChainEntry> closedEntries = manifest.entries().stream()
                .filter(e -> "closed".equals(e.status()))
                .filter(e -> e.replacedBy() == null) // Exclude compacted entries
                .toList();
        assertTrue(closedEntries.size() >= 1, "Should have at least one closed entry");

        // Get the first closed entry (the one with our test data, before forceRollover's ZIPs)
        ChainEntry closedEntry = closedEntries.get(0);
        Path zipPath = tempDir.resolve(closedEntry.file());

        // Compact with TOMBSTONES_ONLY
        Compactor.compact(zipPath, CompactionMode.TOMBSTONES_ONLY, manifest, tempDir);

        // Reload manifest
        manifest = ChainManifest.load(tempDir.resolve("chain.json"));

        // Old entry should be compacted
        Optional<ChainEntry> oldEntry = manifest.entries().stream()
                .filter(e -> e.uuid().equals(closedEntry.uuid()))
                .findFirst();
        assertTrue(oldEntry.isPresent());
        assertEquals("compacted", oldEntry.get().status());
        assertNotNull(oldEntry.get().replacedBy());

        // New entry should be closed
        Optional<ChainEntry> newEntry = manifest.entries().stream()
                .filter(e -> e.uuid().equals(oldEntry.get().replacedBy()))
                .findFirst();
        assertTrue(newEntry.isPresent());
        assertEquals("closed", newEntry.get().status());

        // Rebuild index from compacted ZIPs
        ZipCorpusStore reloadedStore = new ZipCorpusStore(config);

        // All docs should still exist
        assertTrue(reloadedStore.exists("docs/readme.md"));
        assertTrue(reloadedStore.exists("docs/api.md"));

        // Deleted doc should still be tombstoned
        assertFalse(reloadedStore.exists("docs/guide.md"));

        // Version history should be preserved
        assertEquals(1, reloadedStore.versions("docs/readme.md").size());
    }

    @Test
    void fullRemovesOldVersions() throws IOException {
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 1024);
        ZipCorpusStore store = new ZipCorpusStore(config);

        // Append same path 3 times
        store.append("docs/readme.md", "v1".getBytes());
        store.append("docs/readme.md", "v2".getBytes());
        store.append("docs/readme.md", "v3".getBytes());

        // Trigger rollover
        forceRollover(store, config);

        // Load manifest
        ChainManifest manifest = ChainManifest.load(tempDir.resolve("chain.json"));
        ChainEntry closedEntry = manifest.entries().stream()
                .filter(e -> "closed".equals(e.status()))
                .findFirst()
                .orElseThrow();

        Path zipPath = tempDir.resolve(closedEntry.file());

        // Compact with FULL
        Compactor.compact(zipPath, CompactionMode.FULL, manifest, tempDir);

        // Rebuild and check
        ZipCorpusStore reloadedStore = new ZipCorpusStore(config);

        // Document should exist
        assertTrue(reloadedStore.exists("docs/readme.md"));

        // Only latest version should remain
        List<?> versions = reloadedStore.versions("docs/readme.md");
        assertEquals(1, versions.size());

        // Latest content should be readable
        Optional<byte[]> content = reloadedStore.read("docs/readme.md");
        assertTrue(content.isPresent());
        assertArrayEquals("v3".getBytes(), content.get());
    }

    @Test
    void fullAlsoRemovesTombstones() throws IOException {
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 1024);
        ZipCorpusStore store = new ZipCorpusStore(config);

        store.append("docs/readme.md", "v1".getBytes());
        store.append("docs/readme.md", "v2".getBytes());
        store.delete("docs/readme.md");

        // Trigger rollover
        forceRollover(store, config);

        ChainManifest manifest = ChainManifest.load(tempDir.resolve("chain.json"));
        ChainEntry closedEntry = manifest.entries().stream()
                .filter(e -> "closed".equals(e.status()))
                .findFirst()
                .orElseThrow();

        Path zipPath = tempDir.resolve(closedEntry.file());

        // Compact with FULL
        Compactor.compact(zipPath, CompactionMode.FULL, manifest, tempDir);

        // Rebuild
        ZipCorpusStore reloadedStore = new ZipCorpusStore(config);

        // Document should still be tombstoned
        assertFalse(reloadedStore.exists("docs/readme.md"));

        // Version history should be empty (tombstone removed, old versions removed)
        List<?> versions = reloadedStore.versions("docs/readme.md");
        assertEquals(0, versions.size());
    }

    @Test
    void compactedZipHasNewUuid() throws IOException {
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 1024);
        ZipCorpusStore store = new ZipCorpusStore(config);

        store.append("docs/readme.md", "v1".getBytes());

        forceRollover(store, config);

        ChainManifest manifest = ChainManifest.load(tempDir.resolve("chain.json"));
        ChainEntry closedEntry = manifest.entries().stream()
                .filter(e -> "closed".equals(e.status()))
                .findFirst()
                .orElseThrow();

        String oldUuid = closedEntry.uuid();
        Path zipPath = tempDir.resolve(closedEntry.file());

        Compactor.compact(zipPath, CompactionMode.FULL, manifest, tempDir);

        // Reload manifest
        manifest = ChainManifest.load(tempDir.resolve("chain.json"));

        // Old entry should show replacedBy
        Optional<ChainEntry> oldEntry = manifest.entries().stream()
                .filter(e -> e.uuid().equals(oldUuid))
                .findFirst();
        assertTrue(oldEntry.isPresent());
        assertEquals("compacted", oldEntry.get().status());
        assertNotNull(oldEntry.get().replacedBy());

        // New entry should exist with different UUID
        String newUuid = oldEntry.get().replacedBy();
        assertNotEquals(oldUuid, newUuid);

        Optional<ChainEntry> newEntry = manifest.entries().stream()
                .filter(e -> e.uuid().equals(newUuid))
                .findFirst();
        assertTrue(newEntry.isPresent());
        assertEquals("closed", newEntry.get().status());
    }

    @Test
    void cannotCompactActiveZip() throws IOException {
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 1024);
        ZipCorpusStore store = new ZipCorpusStore(config);

        store.append("docs/readme.md", "v1".getBytes());

        ChainManifest manifest = ChainManifest.load(tempDir.resolve("chain.json"));
        ChainEntry activeEntry = manifest.activeEntry().orElseThrow();
        Path activeZipPath = tempDir.resolve(activeEntry.file());

        // Should throw ISE
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                Compactor.compact(activeZipPath, CompactionMode.FULL, manifest, tempDir));

        assertTrue(ex.getMessage().contains("active") || ex.getMessage().contains("not closed"));
    }

    @Test
    void readVersionReturnsEmptyAfterFullCompaction() throws IOException {
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 1024);
        ZipCorpusStore store = new ZipCorpusStore(config);

        // Append 3 versions
        store.append("docs/readme.md", "v1".getBytes());
        store.append("docs/readme.md", "v2".getBytes());
        store.append("docs/readme.md", "v3".getBytes());

        forceRollover(store, config);

        ChainManifest manifest = ChainManifest.load(tempDir.resolve("chain.json"));
        ChainEntry closedEntry = manifest.entries().stream()
                .filter(e -> "closed".equals(e.status()))
                .findFirst()
                .orElseThrow();

        Path zipPath = tempDir.resolve(closedEntry.file());

        // Compact with FULL
        Compactor.compact(zipPath, CompactionMode.FULL, manifest, tempDir);

        // Create new store instance (rebuild from disk)
        ZipCorpusStore reloadedStore = new ZipCorpusStore(config);

        // Version 1 and 2 should return empty
        Optional<byte[]> v1 = reloadedStore.readVersion("docs/readme.md", 1);
        assertTrue(v1.isEmpty(), "Version 1 should be compacted away");

        Optional<byte[]> v2 = reloadedStore.readVersion("docs/readme.md", 2);
        assertTrue(v2.isEmpty(), "Version 2 should be compacted away");

        // Version 3 should still exist
        Optional<byte[]> v3 = reloadedStore.readVersion("docs/readme.md", 3);
        assertTrue(v3.isPresent());
        assertArrayEquals("v3".getBytes(), v3.get());
    }

    // ── Helper ──────────────────────────────────────────────────────────

    private void forceRollover(ZipCorpusStore store, CorpusConfig config) throws IOException {
        // Append enough data to exceed maxZipSize (1KB)
        // Use pattern from ZipIntegrityCheckerTest
        byte[] payload = new byte[400];
        new java.util.Random(42).nextBytes(payload);
        store.append("rollover1.bin", payload);
        store.append("rollover2.bin", payload);
        store.append("rollover3.bin", payload); // This should trigger rollover
    }
}
