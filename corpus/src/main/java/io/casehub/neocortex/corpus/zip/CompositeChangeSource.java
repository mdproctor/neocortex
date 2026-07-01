package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.ChangeListener;
import io.casehub.neocortex.corpus.ChangeSet;
import io.casehub.neocortex.corpus.ChangedEntry;
import io.casehub.neocortex.corpus.WatchableChangeSource;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class CompositeChangeSource implements WatchableChangeSource {

    private final ZipCorpusStore zipStore;
    private final Path flatDir;
    private final ZipChangeSource zipChangeSource;

    private final Object watchLock = new Object();
    private volatile DirectoryWatcher watcher;
    private volatile ChangeListener listener;
    private volatile String lastCursor;

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

    @Override
    public void watch(ChangeListener listener) {
        synchronized (watchLock) {
            if (this.watcher != null) {
                throw new IllegalStateException("Already watching — call close() first");
            }

            this.listener = listener;

            syncFlatToZip();
            this.lastCursor = zipChangeSource.fullScan().newCursor();

            try {
                Files.createDirectories(flatDir);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create watch directory: " + flatDir, e);
            }

            try {
                this.watcher = DirectoryWatcher.builder()
                        .path(flatDir)
                        .listener(this::onRawEvent)
                        .build();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create directory watcher", e);
            }

            watcher.watchAsync();
        }
    }

    @Override
    public String currentCursor() {
        String cursor = lastCursor;
        if (cursor != null) {
            return cursor;
        }
        return zipChangeSource.fullScan().newCursor();
    }

    @Override
    public void close() {
        synchronized (watchLock) {
            if (watcher != null) {
                try {
                    watcher.close();
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to close directory watcher", e);
                } finally {
                    watcher = null;
                    listener = null;
                    lastCursor = null;
                }
            }
        }
    }

    private void onRawEvent(DirectoryChangeEvent event) {
        if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW) {
            handleOverflowOrChange();
            return;
        }

        Path absolute = event.path();
        if (Files.isDirectory(absolute)) {
            return;
        }

        String relativePath = flatDir.relativize(absolute).toString().replace('\\', '/');
        if (relativePath.startsWith("_")) {
            return;
        }

        handleOverflowOrChange();
    }

    private void handleOverflowOrChange() {
        synchronized (watchLock) {
            if (listener == null) {
                return;
            }

            syncFlatToZip();

            String cursor = lastCursor;
            ChangeSet changes = cursor != null
                    ? zipChangeSource.changesSince(cursor)
                    : zipChangeSource.fullScan();

            lastCursor = changes.newCursor();

            if (!changes.entries().isEmpty()) {
                listener.onChange(changes.entries());
            }
        }
    }

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

        if (relativePath.startsWith("_")) {
            return;
        }

        MasterIndex index = zipStore.masterIndex();

        try {
            long flatMtime = Files.getLastModifiedTime(file).toMillis();

            var existing = index.get(relativePath);
            if (existing.isEmpty()) {
                byte[] content = Files.readAllBytes(file);
                zipStore.append(relativePath, content);
            } else if (flatMtime > existing.get().timestamp()) {
                byte[] content = Files.readAllBytes(file);
                zipStore.append(relativePath, content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to sync flat file: " + relativePath, e);
        }
    }
}
