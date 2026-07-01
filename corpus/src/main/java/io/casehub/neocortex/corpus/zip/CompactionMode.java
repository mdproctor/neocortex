package io.casehub.neocortex.corpus.zip;

/**
 * Defines compaction strategies for ZIP archives in the corpus chain.
 *
 * <ul>
 * <li>{@link #TOMBSTONES_ONLY} — removes only tombstone markers
 *     ({@code _tombstones/*}), leaving all document versions intact.
 * <li>{@link #FULL} — removes tombstone markers AND old versions of
 *     documents, keeping only the latest version of each path.
 * </ul>
 */
public enum CompactionMode {

    /**
     * Remove tombstone markers only. All document versions are preserved.
     */
    TOMBSTONES_ONLY,

    /**
     * Remove tombstone markers AND old versions. Only the latest version
     * of each path is kept.
     */
    FULL
}
