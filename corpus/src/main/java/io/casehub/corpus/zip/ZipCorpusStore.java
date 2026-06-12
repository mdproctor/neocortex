package io.casehub.corpus.zip;

import io.casehub.corpus.CorpusReader;
import io.casehub.corpus.CorpusStore;
import io.casehub.corpus.VersionInfo;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ZIP-backed implementation of {@link CorpusStore} and {@link CorpusReader}.
 *
 * <p>Documents are stored in a chain of ZIP archives managed by a
 * {@link ChainManifest}. Versions are tracked in a {@link MasterIndex}
 * that is rebuilt from the ZIP central directories on startup.
 *
 * <p>Each document entry inside a ZIP is named {@code <version>/<path>}
 * (e.g. {@code 1/docs/readme.md}). Tombstone markers for deleted paths
 * are stored as {@code _tombstones/<path>.deleted}.
 */
public final class ZipCorpusStore implements CorpusStore, CorpusReader {

    private static final String TOMBSTONE_PREFIX = "_tombstones/";
    private static final String TOMBSTONE_SUFFIX = ".deleted";
    private static final String CHAIN_PREFIX = "_chain/";

    private final CorpusConfig config;
    private final ChainManifest manifest;
    private final MasterIndex index;
    private Path activeZipPath;

    public ZipCorpusStore(CorpusConfig config) {
        this.config = config;
        this.index = new MasterIndex();

        try {
            Files.createDirectories(config.source());

            Path chainJson = config.source().resolve("chain.json");
            if (Files.exists(chainJson)) {
                this.manifest = ChainManifest.load(chainJson);
                rebuildIndex();
                // Set activeZipPath from the manifest's active entry
                manifest.activeEntry().ifPresentOrElse(
                        entry -> this.activeZipPath = config.source().resolve(entry.file()),
                        () -> {
                            // No active entry — create a new one
                            createNewActiveZip(nextSequence());
                        }
                );
            } else {
                this.manifest = ChainManifest.create(config.corpusName());
                createNewActiveZip(0);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize ZipCorpusStore", e);
        }
    }

    // ── CorpusStore ─────────────────────────────────────────────────────

    @Override
    public void append(String path, byte[] content) {
        validatePath(path);
        try {
            int version = index.versions(path).size() + 1;

            String entryName = version + "/" + path;
            ZipFile zipFile = new ZipFile(activeZipPath.toFile());
            ZipParameters params = new ZipParameters();
            params.setFileNameInZip(entryName);
            params.setCompressionMethod(CompressionMethod.DEFLATE);
            zipFile.addStream(new ByteArrayInputStream(content), params);

            EntryLocation location = new EntryLocation(
                    activeZipPath.getFileName().toString(),
                    version,
                    System.currentTimeMillis());
            index.put(path, location);

            saveManifest();
            checkRollover();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append entry: " + path, e);
        }
    }

    @Override
    public void append(String path, InputStream content) {
        try {
            append(path, content.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read InputStream for: " + path, e);
        }
    }

    @Override
    public void append(String path, Path file) {
        try {
            append(path, Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file: " + file, e);
        }
    }

    @Override
    public void delete(String path) {
        try {
            // Write tombstone marker to active ZIP
            String tombstoneEntry = TOMBSTONE_PREFIX + path + TOMBSTONE_SUFFIX;
            ZipFile zipFile = new ZipFile(activeZipPath.toFile());
            ZipParameters params = new ZipParameters();
            params.setFileNameInZip(tombstoneEntry);
            params.setCompressionMethod(CompressionMethod.DEFLATE);
            zipFile.addStream(new ByteArrayInputStream(new byte[0]), params);

            index.tombstone(path);
            saveManifest();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete entry: " + path, e);
        }
    }

    // ── CorpusReader ────────────────────────────────────────────────────

    @Override
    public Optional<byte[]> read(String path) {
        Optional<EntryLocation> loc = index.get(path);
        if (loc.isEmpty()) return Optional.empty();
        return readEntry(path, loc.get());
    }

    @Override
    public Optional<InputStream> readStream(String path) {
        return read(path).map(ByteArrayInputStream::new);
    }

    @Override
    public Optional<byte[]> readVersion(String path, int version) {
        List<VersionInfo> allVersions = index.versions(path);
        return allVersions.stream()
                .filter(v -> v.version() == version)
                .findFirst()
                .flatMap(vi -> {
                    EntryLocation loc = new EntryLocation(
                            vi.zipFile(), vi.version(), vi.timestamp().toEpochMilli());
                    return readEntry(path, loc);
                });
    }

    @Override
    public List<VersionInfo> versions(String path) {
        return index.versions(path);
    }

    @Override
    public List<String> list() {
        return index.list();
    }

    @Override
    public List<String> list(String prefix) {
        return index.list(prefix);
    }

    @Override
    public boolean exists(String path) {
        return index.exists(path);
    }

    // ── rollover ────────────────────────────────────────────────────────

    private void checkRollover() {
        long size = activeZipPath.toFile().length();
        if (size >= config.maxZipSize()) {
            rollover();
        }
    }

    private void rollover() {
        ChainEntry active = manifest.activeEntry()
                .orElseThrow(() -> new IllegalStateException("No active entry during rollover"));

        int entryCount = countDocumentEntries(activeZipPath);
        writeInternalMeta(active, null);
        String contentHash = computeHash(activeZipPath);

        manifest.closeEntry(active.uuid(), contentHash, entryCount);
        createNewActiveZip(nextSequence());
    }

    private String computeHash(Path zipPath) {
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

    private void writeInternalMeta(ChainEntry entry, String contentHash) {
        try {
            String metaJson = buildMetaJson(entry, contentHash);
            ZipFile zipFile = new ZipFile(activeZipPath.toFile());
            ZipParameters params = new ZipParameters();
            params.setFileNameInZip(CHAIN_PREFIX + "meta.json");
            params.setCompressionMethod(CompressionMethod.DEFLATE);
            zipFile.addStream(
                    new ByteArrayInputStream(metaJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    params);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write internal meta.json", e);
        }
    }

    private String buildMetaJson(ChainEntry entry, String contentHash) {
        var sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"uuid\": \"").append(entry.uuid()).append("\",\n");
        sb.append("  \"file\": \"").append(entry.file()).append("\",\n");
        sb.append("  \"sequence\": ").append(entry.sequence()).append(",\n");
        sb.append("  \"status\": \"").append(entry.status()).append("\"");
        if (contentHash != null) {
            sb.append(",\n  \"contentHash\": \"").append(contentHash).append("\"");
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    private int countDocumentEntries(Path zipPath) {
        try {
            ZipFile zipFile = new ZipFile(zipPath.toFile());
            int count = 0;
            for (FileHeader header : zipFile.getFileHeaders()) {
                String name = header.getFileName();
                if (!name.startsWith(CHAIN_PREFIX) && !name.startsWith(TOMBSTONE_PREFIX)) {
                    count++;
                }
            }
            return count;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to count entries in " + zipPath, e);
        }
    }

    // ── internal ────────────────────────────────────────────────────────

    private Optional<byte[]> readEntry(String path, EntryLocation location) {
        Path zipPath = config.source().resolve(location.zipFile());
        String entryName = location.version() + "/" + path;
        try {
            ZipFile zipFile = new ZipFile(zipPath.toFile());
            FileHeader header = zipFile.getFileHeader(entryName);
            if (header == null) return Optional.empty();
            try (InputStream is = zipFile.getInputStream(header)) {
                return Optional.of(is.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read entry " + entryName + " from " + location.zipFile(), e);
        }
    }

    private void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be null or blank");
        }
        if (path.startsWith("_")) {
            throw new IllegalArgumentException(
                    "paths starting with '_' are reserved: " + path);
        }
    }

    private void rebuildIndex() {
        index.clear();
        List<ChainEntry> entries = manifest.entries().stream()
                .sorted(Comparator.comparingInt(ChainEntry::sequence))
                .toList();

        for (ChainEntry chainEntry : entries) {
            if (chainEntry.replacedBy() != null) continue; // skip compacted

            Path zipPath = config.source().resolve(chainEntry.file());
            if (!Files.exists(zipPath)) continue;

            try {
                ZipFile zipFile = new ZipFile(zipPath.toFile());
                List<FileHeader> headers = zipFile.getFileHeaders();

                for (FileHeader header : headers) {
                    String entryName = header.getFileName();

                    if (entryName.startsWith(CHAIN_PREFIX)) continue;

                    if (entryName.startsWith(TOMBSTONE_PREFIX)) {
                        // Extract original path: remove prefix and .deleted suffix
                        String originalPath = entryName.substring(TOMBSTONE_PREFIX.length());
                        if (originalPath.endsWith(TOMBSTONE_SUFFIX)) {
                            originalPath = originalPath.substring(
                                    0, originalPath.length() - TOMBSTONE_SUFFIX.length());
                        }
                        index.tombstone(originalPath);
                        continue;
                    }

                    // Parse versioned entry name: <version>/<path>
                    int slashPos = entryName.indexOf('/');
                    if (slashPos <= 0) continue;

                    int version;
                    try {
                        version = Integer.parseInt(entryName.substring(0, slashPos));
                    } catch (NumberFormatException e) {
                        continue; // skip entries without a version prefix
                    }
                    String logicalPath = entryName.substring(slashPos + 1);

                    EntryLocation location = new EntryLocation(
                            chainEntry.file(),
                            version,
                            header.getLastModifiedTimeEpoch());
                    index.put(logicalPath, location);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Failed to rebuild index from " + chainEntry.file(), e);
            }
        }
    }

    private void createNewActiveZip(int sequence) {
        String fileName = config.corpusName() + "-" + (sequence + 1) + ".zip";
        this.activeZipPath = config.source().resolve(fileName);

        String uuid = UUID.randomUUID().toString();
        String predecessor = manifest.entries().isEmpty()
                ? null
                : manifest.entries().getLast().uuid();

        ChainEntry entry = new ChainEntry(
                uuid, fileName, sequence, "active", predecessor,
                0, 0, null, null, null, null, null);
        manifest.addEntry(entry);
        saveManifest();
    }

    private int nextSequence() {
        return manifest.entries().stream()
                .mapToInt(ChainEntry::sequence)
                .max()
                .orElse(-1) + 1;
    }

    private void saveManifest() {
        try {
            manifest.save(config.source().resolve("chain.json"));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save chain.json", e);
        }
    }
}
