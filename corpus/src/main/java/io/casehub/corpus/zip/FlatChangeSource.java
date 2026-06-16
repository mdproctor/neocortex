package io.casehub.corpus.zip;

import io.casehub.corpus.ChangeListener;
import io.casehub.corpus.ChangeSet;
import io.casehub.corpus.ChangeType;
import io.casehub.corpus.ChangedEntry;
import io.casehub.corpus.WatchableChangeSource;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class FlatChangeSource implements WatchableChangeSource {

    private static final long DEBOUNCE_MS = 500;

    private final FlatCorpusStore store;
    private final Path rootDir;

    private final Object watchLock = new Object();
    private volatile DirectoryWatcher watcher;
    private volatile ChangeListener listener;
    private volatile Map<String, Long> watchState;
    private ScheduledExecutorService debounceExecutor;
    private ScheduledFuture<?> pendingFlush;
    private final ConcurrentHashMap<String, ChangeType> eventBuffer = new ConcurrentHashMap<>();

    public FlatChangeSource(FlatCorpusStore store, Path rootDir) {
        this.store = store;
        this.rootDir = rootDir;
    }

    @Override
    public ChangeSet fullScan() {
        List<ChangedEntry> entries = new ArrayList<>();
        Map<String, Long> currentState = new HashMap<>();

        for (String path : store.list()) {
            entries.add(new ChangedEntry(path, ChangeType.ADDED));
            currentState.put(path, getLastModified(path));
        }

        return new ChangeSet(entries, serializeCursor(currentState));
    }

    @Override
    public ChangeSet changesSince(String cursor) {
        Map<String, Long> previousState = deserializeCursor(cursor);
        Map<String, Long> currentState = new HashMap<>();
        List<ChangedEntry> entries = new ArrayList<>();

        for (String path : store.list()) {
            long currentMtime = getLastModified(path);
            currentState.put(path, currentMtime);

            Long previousMtime = previousState.get(path);
            if (previousMtime == null) {
                entries.add(new ChangedEntry(path, ChangeType.ADDED));
            } else if (currentMtime != previousMtime) {
                entries.add(new ChangedEntry(path, ChangeType.MODIFIED));
            }
        }

        for (String path : previousState.keySet()) {
            if (!currentState.containsKey(path)) {
                entries.add(new ChangedEntry(path, ChangeType.DELETED));
            }
        }

        return new ChangeSet(entries, serializeCursor(currentState));
    }

    @Override
    public void watch(ChangeListener listener) {
        synchronized (watchLock) {
            if (this.watcher != null) {
                throw new IllegalStateException("Already watching — call close() first");
            }

            this.listener = listener;

            watchState = new HashMap<>();
            for (String path : store.list()) {
                watchState.put(path, getLastModified(path));
            }

            try {
                Files.createDirectories(rootDir);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create watch directory: " + rootDir, e);
            }

            try {
                this.watcher = DirectoryWatcher.builder()
                        .path(rootDir)
                        .listener(this::onRawEvent)
                        .build();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create directory watcher", e);
            }

            debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "flat-change-source-debounce");
                t.setDaemon(true);
                return t;
            });

            watcher.watchAsync();
        }
    }

    @Override
    public String currentCursor() {
        Map<String, Long> snapshot = watchState;
        if (snapshot == null) {
            return fullScan().newCursor();
        }
        synchronized (watchLock) {
            return serializeCursor(new HashMap<>(watchState));
        }
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
                    watchState = null;
                    if (pendingFlush != null) {
                        pendingFlush.cancel(false);
                        pendingFlush = null;
                    }
                    if (debounceExecutor != null) {
                        debounceExecutor.shutdownNow();
                        debounceExecutor = null;
                    }
                    eventBuffer.clear();
                }
            }
        }
    }

    private void onRawEvent(DirectoryChangeEvent event) {
        if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW) {
            handleOverflow();
            return;
        }

        Path absolute = event.path();
        if (Files.isDirectory(absolute)) {
            return;
        }

        String relativePath = rootDir.relativize(absolute).toString().replace('\\', '/');

        if (relativePath.startsWith("_")) {
            return;
        }

        ChangeType type = mapEventType(event.eventType(), relativePath);
        if (type != null) {
            eventBuffer.put(relativePath, type);
            scheduleFlush();
        }
    }

    private ChangeType mapEventType(DirectoryChangeEvent.EventType eventType, String path) {
        return switch (eventType) {
            case CREATE -> watchState != null && watchState.containsKey(path)
                    ? ChangeType.MODIFIED : ChangeType.ADDED;
            case MODIFY -> watchState != null && watchState.containsKey(path)
                    ? ChangeType.MODIFIED : ChangeType.ADDED;
            case DELETE -> ChangeType.DELETED;
            default -> null;
        };
    }

    private void scheduleFlush() {
        synchronized (watchLock) {
            if (debounceExecutor == null || debounceExecutor.isShutdown()) {
                return;
            }
            if (pendingFlush != null) {
                pendingFlush.cancel(false);
            }
            pendingFlush = debounceExecutor.schedule(this::flush, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flush() {
        if (eventBuffer.isEmpty()) {
            return;
        }

        Map<String, ChangeType> batch = new HashMap<>(eventBuffer);
        eventBuffer.clear();

        List<ChangedEntry> entries = new ArrayList<>(batch.size());

        synchronized (watchLock) {
            if (watchState == null) {
                return;
            }

            for (var entry : batch.entrySet()) {
                String path = entry.getKey();
                ChangeType type = entry.getValue();
                entries.add(new ChangedEntry(path, type));

                if (type == ChangeType.DELETED) {
                    watchState.remove(path);
                } else {
                    try {
                        watchState.put(path, getLastModified(path));
                    } catch (UncheckedIOException e) {
                        watchState.remove(path);
                    }
                }
            }
        }

        if (!entries.isEmpty()) {
            ChangeListener l = listener;
            if (l != null) {
                l.onChange(entries);
            }
        }
    }

    private void handleOverflow() {
        synchronized (watchLock) {
            if (watchState == null) {
                return;
            }

            eventBuffer.clear();

            Map<String, Long> newState = new HashMap<>();
            for (String path : store.list()) {
                newState.put(path, getLastModified(path));
            }

            List<ChangedEntry> entries = new ArrayList<>();

            for (var entry : newState.entrySet()) {
                String path = entry.getKey();
                Long oldMtime = watchState.get(path);
                if (oldMtime == null) {
                    entries.add(new ChangedEntry(path, ChangeType.ADDED));
                } else if (!oldMtime.equals(entry.getValue())) {
                    entries.add(new ChangedEntry(path, ChangeType.MODIFIED));
                }
            }

            for (String path : watchState.keySet()) {
                if (!newState.containsKey(path)) {
                    entries.add(new ChangedEntry(path, ChangeType.DELETED));
                }
            }

            watchState = newState;

            if (!entries.isEmpty()) {
                ChangeListener l = listener;
                if (l != null) {
                    l.onChange(entries);
                }
            }
        }
    }

    private long getLastModified(String path) {
        try {
            return Files.getLastModifiedTime(rootDir.resolve(path)).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to get mtime for: " + path, e);
        }
    }

    // --- Cursor serialization (unchanged) ---

    private Map<String, Long> deserializeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new HashMap<>();
        }

        Map<String, Long> state = new HashMap<>();
        String trimmed = cursor.trim();

        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("Invalid cursor format");
        }

        String content = trimmed.substring(1, trimmed.length() - 1).trim();
        if (content.isEmpty()) {
            return state;
        }

        String[] entries = content.split(",");
        for (String entry : entries) {
            String[] parts = entry.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid cursor entry: " + entry);
            }

            String key = parts[0].trim();
            if (key.startsWith("\"") && key.endsWith("\"")) {
                key = unescapeJson(key.substring(1, key.length() - 1));
            }

            long value = Long.parseLong(parts[1].trim());
            state.put(key, value);
        }

        return state;
    }

    private String serializeCursor(Map<String, Long> state) {
        if (state.isEmpty()) {
            return "{}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        List<String> sortedPaths = new ArrayList<>(state.keySet());
        sortedPaths.sort(String::compareTo);

        for (String path : sortedPaths) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(escapeJson(path)).append("\":");
            sb.append(state.get(path));
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String unescapeJson(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
