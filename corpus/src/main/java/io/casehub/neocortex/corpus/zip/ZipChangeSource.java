package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.ChangeSet;
import io.casehub.neocortex.corpus.ChangeSource;
import io.casehub.neocortex.corpus.ChangeType;
import io.casehub.neocortex.corpus.ChangedEntry;
import io.casehub.neocortex.corpus.VersionInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks changes in a {@link ZipCorpusStore} by comparing snapshots of
 * paths and their version numbers.
 *
 * <p>The cursor is a JSON-encoded {@code Map<String, Integer>} representing
 * the known state at the time of the last scan. Comparing the cursor to the
 * current state determines what has changed.
 *
 * <p>Change detection:
 * <ul>
 *   <li>ADDED: path exists now but not in cursor</li>
 *   <li>MODIFIED: path exists in both, but version number increased</li>
 *   <li>DELETED: path exists in cursor but is now tombstoned or absent</li>
 * </ul>
 */
public final class ZipChangeSource implements ChangeSource {

    private final ZipCorpusStore store;

    public ZipChangeSource(ZipCorpusStore store) {
        this.store = store;
    }

    @Override
    public ChangeSet fullScan() {
        MasterIndex index = store.masterIndex();
        Map<String, Integer> currentState = buildCurrentState(index);
        List<ChangedEntry> entries = currentState.keySet().stream()
                .sorted()
                .map(path -> new ChangedEntry(path, ChangeType.ADDED))
                .toList();
        String cursor = encodeCursor(currentState);
        return new ChangeSet(entries, cursor);
    }

    @Override
    public ChangeSet changesSince(String cursor) {
        Map<String, Integer> previousState = decodeCursor(cursor);
        MasterIndex index = store.masterIndex();
        Map<String, Integer> currentState = buildCurrentState(index);

        List<ChangedEntry> changes = new ArrayList<>();

        // Detect ADDED and MODIFIED
        for (Map.Entry<String, Integer> entry : currentState.entrySet()) {
            String path = entry.getKey();
            Integer currentVersion = entry.getValue();
            Integer previousVersion = previousState.get(path);

            if (previousVersion == null) {
                changes.add(new ChangedEntry(path, ChangeType.ADDED));
            } else if (currentVersion > previousVersion) {
                changes.add(new ChangedEntry(path, ChangeType.MODIFIED));
            }
        }

        // Detect DELETED
        for (String path : previousState.keySet()) {
            if (!currentState.containsKey(path)) {
                changes.add(new ChangedEntry(path, ChangeType.DELETED));
            }
        }

        // Sort for stable output
        changes.sort((a, b) -> {
            int typeCompare = a.type().compareTo(b.type());
            return typeCompare != 0 ? typeCompare : a.path().compareTo(b.path());
        });

        String newCursor = encodeCursor(currentState);
        return new ChangeSet(changes, newCursor);
    }

    // ── internal ────────────────────────────────────────────────────────

    /**
     * Builds a snapshot of current state: path → latest version number.
     * Excludes tombstoned paths.
     */
    private Map<String, Integer> buildCurrentState(MasterIndex index) {
        Map<String, Integer> state = new HashMap<>();
        for (String path : index.list()) {
            List<VersionInfo> versions = index.versions(path);
            if (!versions.isEmpty()) {
                int latestVersion = versions.stream()
                        .mapToInt(VersionInfo::version)
                        .max()
                        .orElse(0);
                state.put(path, latestVersion);
            }
        }
        return state;
    }

    /**
     * Encodes a state map as a JSON cursor string.
     */
    private String encodeCursor(Map<String, Integer> state) {
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

    /**
     * Decodes a JSON cursor string to a state map.
     * Returns empty map if cursor is null or malformed.
     */
    private Map<String, Integer> decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.equals("{}")) {
            return new HashMap<>();
        }

        Map<String, Integer> state = new HashMap<>();
        try {
            // Simple JSON parser for {"path":version,...}
            String content = cursor.substring(1, cursor.length() - 1).trim();
            if (content.isEmpty()) {
                return state;
            }

            String[] pairs = content.split(",");
            for (String pair : pairs) {
                int colonPos = pair.indexOf(':');
                if (colonPos <= 0) continue;

                String key = pair.substring(0, colonPos).trim();
                String value = pair.substring(colonPos + 1).trim();

                // Remove quotes from key
                if (key.startsWith("\"") && key.endsWith("\"")) {
                    key = key.substring(1, key.length() - 1);
                }
                key = unescapeJson(key);

                int version = Integer.parseInt(value);
                state.put(key, version);
            }
        } catch (Exception e) {
            // If cursor is malformed, return empty state (treat as initial scan)
            return new HashMap<>();
        }
        return state;
    }

    /**
     * Escapes special JSON characters in a string.
     */
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Unescapes special JSON characters in a string.
     */
    private String unescapeJson(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
