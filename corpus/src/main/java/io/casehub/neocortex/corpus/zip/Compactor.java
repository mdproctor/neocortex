package io.casehub.neocortex.corpus.zip;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionMethod;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Compacts closed ZIP archives in the corpus chain by removing tombstone
 * markers and optionally old versions.
 */
public final class Compactor {

    private static final String TOMBSTONE_PREFIX = "_tombstones/";
    private static final String CHAIN_PREFIX = "_chain/";

    private Compactor() {
        // static utility class
    }

    /**
     * Compacts a closed ZIP archive according to the specified mode.
     *
     * <p>Steps:
     * <ol>
     * <li>Verify the ZIP is a CLOSED entry in the manifest (not active, not already compacted)
     * <li>Read all entries from source ZIP
     * <li>Filter based on mode (always skip tombstones and _chain/meta.json)
     * <li>Write filtered entries to a new temp ZIP
     * <li>Write new _chain/meta.json inside the new ZIP
     * <li>Compute SHA-256 hash of the new ZIP
     * <li>Generate a new UUID for the rewritten ZIP
     * <li>Call manifest.retireEntry(oldUuid, newUuid) to mark old as compacted
     * <li>Add a new ChainEntry for the compacted ZIP
     * <li>Atomically replace the old ZIP with the new one
     * <li>Save manifest
     * </ol>
     *
     * @param zipPath path to the ZIP file to compact
     * @param mode compaction mode (TOMBSTONES_ONLY or FULL)
     * @param manifest chain manifest
     * @param corpusDir corpus directory (for resolving paths)
     * @throws IllegalStateException if the ZIP is not a closed entry
     * @throws UncheckedIOException on IO errors
     */
    public static void compact(
            Path zipPath,
            CompactionMode mode,
            ChainManifest manifest,
            Path corpusDir) {

        // 1. Verify the ZIP is a CLOSED entry
        String fileName = zipPath.getFileName().toString();
        Optional<ChainEntry> entryOpt = manifest.entries().stream()
                .filter(e -> e.file().equals(fileName))
                .findFirst();

        if (entryOpt.isEmpty()) {
            throw new IllegalStateException("ZIP file not found in manifest: " + fileName);
        }

        ChainEntry entry = entryOpt.get();
        if (!"closed".equals(entry.status())) {
            throw new IllegalStateException(
                    "Cannot compact ZIP that is not closed: " + fileName + " (status=" + entry.status() + ")");
        }
        if (entry.replacedBy() != null) {
            throw new IllegalStateException(
                    "Cannot compact ZIP that is already compacted: " + fileName);
        }

        try {
            // 2. Read all entries from source ZIP
            ZipFile sourceZip = new ZipFile(zipPath.toFile());
            List<FileHeader> headers = sourceZip.getFileHeaders();

            // 3. Determine which entries to keep
            Set<String> entriesToKeep = filterEntries(headers, mode);

            // 4. Create temp ZIP and write filtered entries
            Path tempZip = Files.createTempFile(corpusDir, "compacting-", ".zip");
            try {
                ZipFile targetZip = new ZipFile(tempZip.toFile());

                for (FileHeader header : headers) {
                    String entryName = header.getFileName();
                    if (entriesToKeep.contains(entryName)) {
                        try (InputStream is = sourceZip.getInputStream(header)) {
                            byte[] content = is.readAllBytes();
                            ZipParameters params = new ZipParameters();
                            params.setFileNameInZip(entryName);
                            params.setCompressionMethod(CompressionMethod.DEFLATE);
                            targetZip.addStream(new ByteArrayInputStream(content), params);
                        }
                    }
                }

                // 5. Write new _chain/meta.json
                String newUuid = UUID.randomUUID().toString();
                int entryCount = (int) entriesToKeep.stream()
                        .filter(e -> !e.startsWith(CHAIN_PREFIX))
                        .count();

                String metaJson = buildMetaJson(newUuid, fileName, entry.sequence(), entryCount);
                ZipParameters metaParams = new ZipParameters();
                metaParams.setFileNameInZip(CHAIN_PREFIX + "meta.json");
                metaParams.setCompressionMethod(CompressionMethod.DEFLATE);
                targetZip.addStream(
                        new ByteArrayInputStream(metaJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        metaParams);

                // 6. Compute SHA-256 hash of the new ZIP
                String contentHash = computeHash(tempZip);

                // 7 & 8. Update manifest — retire old entry, add new entry
                manifest.retireEntry(entry.uuid(), newUuid);

                ChainEntry newEntry = new ChainEntry(
                        newUuid,
                        fileName,
                        entry.sequence(),
                        "closed",
                        entry.predecessor(),
                        entryCount,
                        entry.cumulativeEntryCount(),
                        contentHash,
                        entry.domains(),
                        entry.earliest(),
                        entry.latest(),
                        null // replacedBy is null for the new entry
                );
                manifest.addEntry(newEntry);

                // 9. Atomically replace the old ZIP with the new one
                Files.move(tempZip, zipPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);

                // 10. Save manifest
                manifest.save(corpusDir.resolve("chain.json"));

            } catch (IOException e) {
                Files.deleteIfExists(tempZip);
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to compact ZIP: " + fileName, e);
        }
    }

    // ── Filtering ────────────────────────────────────────────────────────

    private static Set<String> filterEntries(List<FileHeader> headers, CompactionMode mode) {
        Set<String> keep = new HashSet<>();

        // First pass: identify tombstoned paths
        Set<String> tombstonedPaths = new HashSet<>();
        for (FileHeader header : headers) {
            String entryName = header.getFileName();
            if (entryName.startsWith(TOMBSTONE_PREFIX)) {
                // Extract original path: remove prefix and .deleted suffix
                String originalPath = entryName.substring(TOMBSTONE_PREFIX.length());
                if (originalPath.endsWith(".deleted")) {
                    originalPath = originalPath.substring(0, originalPath.length() - ".deleted".length());
                }
                tombstonedPaths.add(originalPath);
            }
        }

        // Second pass: filter entries
        // Always skip _chain/meta.json
        for (FileHeader header : headers) {
            String entryName = header.getFileName();

            if (entryName.equals(CHAIN_PREFIX + "meta.json")) continue;

            // Handle tombstones
            if (entryName.startsWith(TOMBSTONE_PREFIX)) {
                if (mode == CompactionMode.TOMBSTONES_ONLY) {
                    // Keep tombstones in TOMBSTONES_ONLY mode
                    keep.add(entryName);
                }
                // FULL mode: skip tombstones
                continue;
            }
            if (entryName.startsWith(CHAIN_PREFIX)) {
                // Keep other _chain/* entries (future-proofing)
                keep.add(entryName);
                continue;
            }

            // Parse versioned entry: <version>/<path>
            int slashPos = entryName.indexOf('/');
            if (slashPos <= 0) {
                // Not a versioned entry — keep it
                keep.add(entryName);
                continue;
            }

            if (mode == CompactionMode.TOMBSTONES_ONLY) {
                // Keep all versioned entries
                keep.add(entryName);
            } else if (mode == CompactionMode.FULL) {
                // Keep only highest version per path
                // We'll track this separately
            }
        }

        // For FULL mode, keep only the highest version per path (excluding tombstoned paths)
        if (mode == CompactionMode.FULL) {
            Map<String, VersionedEntry> latestVersions = new HashMap<>();

            for (FileHeader header : headers) {
                String entryName = header.getFileName();
                if (entryName.startsWith(TOMBSTONE_PREFIX)) continue;
                if (entryName.startsWith(CHAIN_PREFIX)) continue;

                int slashPos = entryName.indexOf('/');
                if (slashPos <= 0) continue;

                int version;
                try {
                    version = Integer.parseInt(entryName.substring(0, slashPos));
                } catch (NumberFormatException e) {
                    continue;
                }
                String path = entryName.substring(slashPos + 1);

                // Skip tombstoned paths entirely
                if (tombstonedPaths.contains(path)) continue;

                VersionedEntry current = latestVersions.get(path);
                if (current == null || version > current.version) {
                    latestVersions.put(path, new VersionedEntry(version, entryName));
                }
            }

            for (VersionedEntry ve : latestVersions.values()) {
                keep.add(ve.entryName);
            }
        }

        return keep;
    }

    private record VersionedEntry(int version, String entryName) {}

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String computeHash(Path zipPath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(zipPath);
            byte[] hash = digest.digest(fileBytes);
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to compute hash of " + zipPath, e);
        }
    }

    private static String buildMetaJson(String uuid, String fileName, int sequence, int entryCount) {
        var sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"uuid\": \"").append(uuid).append("\",\n");
        sb.append("  \"file\": \"").append(fileName).append("\",\n");
        sb.append("  \"sequence\": ").append(sequence).append(",\n");
        sb.append("  \"status\": \"closed\",\n");
        sb.append("  \"entryCount\": ").append(entryCount).append("\n");
        sb.append("}\n");
        return sb.toString();
    }
}
