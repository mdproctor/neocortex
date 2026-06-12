package io.casehub.corpus.zip;

import io.casehub.corpus.ChangeSet;
import io.casehub.corpus.ChangeSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Composite change source that syncs externally written flat files into
 * the ZIP store before reporting changes.
 *
 * <p>The sync-then-report pattern:
 * <ol>
 *   <li>Walk the flat directory for files not yet in the ZIP store,
 *       or modified since last sync (comparing flat file mtime against
 *       the MasterIndex entry timestamp).</li>
 *   <li>Append each new/modified file into the ZipCorpusStore.</li>
 *   <li>Delegate to a {@link ZipChangeSource} for change detection
 *       against the now-updated ZIP state.</li>
 * </ol>
 *
 * <p>The cursor is the {@link ZipChangeSource} cursor. Flat file
 * scanning is stateless — it compares the current flat directory
 * against what the MasterIndex already knows.
 */
public final class CompositeChangeSource implements ChangeSource {

    private final ZipCorpusStore zipStore;
    private final Path flatDir;
    private final ZipChangeSource zipChangeSource;

    public CompositeChangeSource(ZipCorpusStore zipStore, Path flatDir) {
        this.zipStore = zipStore;
        this.flatDir = flatDir;
        this.zipChangeSource = new ZipChangeSource(zipStore);
    }

    @Override
    public ChangeSet fullScan() {
        syncFlatToZip();
        return zipChangeSource.fullScan();
    }

    @Override
    public ChangeSet changesSince(String cursor) {
        syncFlatToZip();
        return zipChangeSource.changesSince(cursor);
    }

    /**
     * Walks the flat directory and syncs any new or modified files
     * into the ZIP store.
     *
     * <p>A flat file is synced if:
     * <ul>
     *   <li>The path does not exist in the MasterIndex (new file), or</li>
     *   <li>The flat file's last-modified time is newer than the
     *       MasterIndex entry timestamp (modified externally).</li>
     * </ul>
     *
     * <p>Files with paths starting with {@code _} are skipped (reserved
     * namespace, matching the convention in both store implementations).
     */
    private void syncFlatToZip() {
        if (!Files.isDirectory(flatDir)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(flatDir)) {
            walk.filter(Files::isRegularFile)
                .forEach(this::syncFileIfNeeded);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk flat directory: " + flatDir, e);
        }
    }

    private void syncFileIfNeeded(Path file) {
        String relativePath = flatDir.relativize(file).toString();

        // Skip reserved paths
        if (relativePath.startsWith("_")) {
            return;
        }

        MasterIndex index = zipStore.masterIndex();

        try {
            long flatMtime = Files.getLastModifiedTime(file).toMillis();

            var existing = index.get(relativePath);
            if (existing.isEmpty()) {
                // New file — not in ZIP yet
                byte[] content = Files.readAllBytes(file);
                zipStore.append(relativePath, content);
            } else if (flatMtime > existing.get().timestamp()) {
                // Modified externally — flat file is newer
                byte[] content = Files.readAllBytes(file);
                zipStore.append(relativePath, content);
            }
            // else: flat file is same age or older — no sync needed
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to sync flat file: " + relativePath, e);
        }
    }
}
