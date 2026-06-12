package io.casehub.corpus.zip;

import io.casehub.corpus.ChangeSet;
import io.casehub.corpus.ChangeType;
import io.casehub.corpus.ChangedEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeChangeSourceTest {

    @TempDir
    Path tempDir;

    private Path zipDir;
    private Path flatDir;
    private ZipCorpusStore zipStore;
    private FlatCorpusStore flatStore;
    private CompositeCorpusStore compositeStore;
    private CompositeChangeSource changeSource;

    @BeforeEach
    void setUp() {
        zipDir = tempDir.resolve("zips");
        flatDir = tempDir.resolve("flat");
        zipStore = new ZipCorpusStore(new CorpusConfig("test-corpus", zipDir));
        flatStore = new FlatCorpusStore(flatDir);
        compositeStore = new CompositeCorpusStore(zipStore, flatStore);
        changeSource = new CompositeChangeSource(zipStore, flatDir);
    }

    // --- external flat file detected after sync ---

    @Test
    void externalFlatFileDetectedAfterSync() throws Exception {
        // Write a file directly to flat directory (bypassing composite store)
        Path docPath = flatDir.resolve("docs/external.md");
        Files.createDirectories(docPath.getParent());
        Files.writeString(docPath, "externally written");

        // changesSince triggers sync — the flat file should be synced to ZIP
        ChangeSet changes = changeSource.fullScan();

        // The file should appear as ADDED
        assertThat(changes.entries()).hasSize(1);
        ChangedEntry entry = changes.entries().get(0);
        assertThat(entry.path()).isEqualTo("docs/external.md");
        assertThat(entry.type()).isEqualTo(ChangeType.ADDED);

        // Verify the file is now readable from the ZIP store
        Optional<byte[]> fromZip = zipStore.read("docs/external.md");
        assertThat(fromZip).isPresent();
        assertThat(new String(fromZip.get(), StandardCharsets.UTF_8))
                .isEqualTo("externally written");
    }

    // --- modified flat file re-synced to ZIP ---

    @Test
    void modifiedFlatFileResyncedToZip() throws Exception {
        // Initial write via composite (goes to both stores)
        compositeStore.append("docs/readme.md", "version one".getBytes(StandardCharsets.UTF_8));

        // Get initial cursor
        ChangeSet initial = changeSource.fullScan();
        String cursor = initial.newCursor();

        // Modify the flat file directly (simulating external tool edit)
        Thread.sleep(50); // ensure mtime differs
        Files.writeString(flatDir.resolve("docs/readme.md"), "version two externally");

        // changesSince triggers sync — the modified flat file should update ZIP
        ChangeSet changes = changeSource.changesSince(cursor);

        // The file should appear as MODIFIED
        assertThat(changes.entries()).hasSize(1);
        ChangedEntry entry = changes.entries().get(0);
        assertThat(entry.path()).isEqualTo("docs/readme.md");
        assertThat(entry.type()).isEqualTo(ChangeType.MODIFIED);

        // Verify ZIP now has the updated content
        Optional<byte[]> fromZip = zipStore.read("docs/readme.md");
        assertThat(fromZip).isPresent();
        assertThat(new String(fromZip.get(), StandardCharsets.UTF_8))
                .isEqualTo("version two externally");
    }

    // --- read after sync finds content in ZIP ---

    @Test
    void readAfterSyncFindsInZip() throws Exception {
        // Write flat file directly
        Path docPath = flatDir.resolve("docs/synced.md");
        Files.createDirectories(docPath.getParent());
        Files.writeString(docPath, "sync me");

        // Trigger sync via fullScan
        changeSource.fullScan();

        // Composite store should now see it (reads from ZIP)
        assertThat(compositeStore.exists("docs/synced.md")).isTrue();
        Optional<byte[]> content = compositeStore.read("docs/synced.md");
        assertThat(content).isPresent();
        assertThat(new String(content.get(), StandardCharsets.UTF_8)).isEqualTo("sync me");
    }

    // --- no changes returns empty ---

    @Test
    void changesSinceWithNothingNewReturnsEmpty() {
        // Get cursor with nothing in the store
        ChangeSet initial = changeSource.fullScan();
        String cursor = initial.newCursor();

        // No changes since
        ChangeSet changes = changeSource.changesSince(cursor);

        assertThat(changes.entries()).isEmpty();
    }

    // --- fullScan includes files from both sources ---

    @Test
    void fullScanIncludesAllFiles() throws Exception {
        // Write one file via composite
        compositeStore.append("docs/via-composite.md", "composite".getBytes(StandardCharsets.UTF_8));

        // Write another directly to flat
        Path externalPath = flatDir.resolve("docs/external.md");
        Files.createDirectories(externalPath.getParent());
        Files.writeString(externalPath, "external");

        // fullScan should sync the external file and report both
        ChangeSet changes = changeSource.fullScan();

        assertThat(changes.entries()).hasSize(2);
        assertThat(changes.entries().stream().map(ChangedEntry::path))
                .containsExactlyInAnyOrder("docs/via-composite.md", "docs/external.md");
    }

    // --- multiple external files synced ---

    @Test
    void multipleExternalFilesSynced() throws Exception {
        Path dir = flatDir.resolve("docs");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.md"), "alpha");
        Files.writeString(dir.resolve("b.md"), "bravo");
        Files.writeString(dir.resolve("c.md"), "charlie");

        ChangeSet changes = changeSource.fullScan();

        assertThat(changes.entries()).hasSize(3);
        // All synced to ZIP
        assertThat(zipStore.exists("docs/a.md")).isTrue();
        assertThat(zipStore.exists("docs/b.md")).isTrue();
        assertThat(zipStore.exists("docs/c.md")).isTrue();
    }
}
