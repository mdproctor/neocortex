package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.VersionInfo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory index built from ZIP central directories. NOT persisted — rebuilt
 * on startup by scanning the chain's ZIP files.
 *
 * <p>Tracks the current (latest) version of each path, the full version
 * history, and tombstones (deleted paths whose history is still accessible).
 */
public final class MasterIndex {

    private final Map<String, EntryLocation> current = new HashMap<>();
    private final Map<String, List<EntryLocation>> history = new HashMap<>();
    private final Set<String> tombstones = new HashSet<>();

    /**
     * Adds a new version of a path. The new entry becomes current; any
     * previous entry stays in history. Version numbers are assigned by the
     * caller (based on central directory order).
     *
     * <p>If the path was previously tombstoned, the tombstone is automatically
     * cleared since a new version is being added.
     */
    public void put(String path, EntryLocation location) {
        current.put(path, location);
        history.computeIfAbsent(path, k -> new ArrayList<>()).add(location);
        tombstones.remove(path);
    }

    /**
     * Marks a path as deleted. Does NOT remove from history — previous
     * versions remain accessible via {@link #versions(String)}.
     */
    public void tombstone(String path) {
        tombstones.add(path);
    }

    /**
     * Returns the current location for a path, or empty if the path is
     * tombstoned or absent.
     */
    public Optional<EntryLocation> get(String path) {
        if (tombstones.contains(path)) return Optional.empty();
        return Optional.ofNullable(current.get(path));
    }

    /**
     * Returns all versions for a path ordered by version number. Returns
     * versions even for tombstoned paths.
     */
    public List<VersionInfo> versions(String path) {
        List<EntryLocation> locs = history.get(path);
        if (locs == null) return List.of();
        return locs.stream()
                .sorted(Comparator.comparingInt(EntryLocation::version))
                .map(loc -> new VersionInfo(
                        loc.version(),
                        Instant.ofEpochMilli(loc.timestamp()),
                        loc.zipFile()))
                .toList();
    }

    /**
     * Returns all paths that are NOT tombstoned, sorted alphabetically.
     */
    public List<String> list() {
        return current.keySet().stream()
                .filter(p -> !tombstones.contains(p))
                .sorted()
                .toList();
    }

    /**
     * Returns paths starting with prefix that are NOT tombstoned, sorted
     * alphabetically.
     */
    public List<String> list(String prefix) {
        return current.keySet().stream()
                .filter(p -> p.startsWith(prefix))
                .filter(p -> !tombstones.contains(p))
                .sorted()
                .toList();
    }

    /**
     * Returns true if a path has a current entry and is NOT tombstoned.
     */
    public boolean exists(String path) {
        return current.containsKey(path) && !tombstones.contains(path);
    }

    /**
     * Resets all state — current locations, history, and tombstones.
     */
    public void clear() {
        current.clear();
        history.clear();
        tombstones.clear();
    }

    /**
     * Clears the tombstone flag for a path, making it visible again if it
     * has a current entry. Used when a path is re-appended after deletion.
     */
    public void removeTombstone(String path) {
        tombstones.remove(path);
    }
}
