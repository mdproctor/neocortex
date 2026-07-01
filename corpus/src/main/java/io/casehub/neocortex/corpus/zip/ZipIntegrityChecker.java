package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.CorpusIntegrity;
import io.casehub.neocortex.corpus.IntegrityIssue;
import io.casehub.neocortex.corpus.IntegrityReport;
import io.casehub.neocortex.corpus.Severity;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionMethod;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Implements integrity checking and recovery for ZIP-based corpus storage.
 *
 * <p>Provides three levels of checking:
 * <ul>
 *   <li>{@link #check()} — read-only detection of issues</li>
 *   <li>{@link #checkAndRecover()} — detection + automatic repair</li>
 *   <li>{@link #fullHashVerification()} — expensive hash verification of all closed ZIPs</li>
 * </ul>
 */
public final class ZipIntegrityChecker implements CorpusIntegrity {

    private static final String CHAIN_PREFIX = "_chain/";
    private static final String TOMBSTONE_PREFIX = "_tombstones/";

    private final Path corpusSource;
    private final String corpusName;

    public ZipIntegrityChecker(Path corpusSource, String corpusName) {
        this.corpusSource = corpusSource;
        this.corpusName = corpusName;
    }

    @Override
    public IntegrityReport check() {
        List<IntegrityIssue> issues = new ArrayList<>();
        int chainLength = 0;
        long totalEntries = 0;

        // Load chain.json
        Path chainJsonPath = corpusSource.resolve("chain.json");
        ChainManifest manifest;

        if (!Files.exists(chainJsonPath)) {
            issues.add(new IntegrityIssue(Severity.WARNING, null,
                    "chain.json not found — can be reconstructed from internal _chain/meta.json"));
            // Attempt to load from internal meta
            manifest = reconstructManifestFromZips();
            if (manifest == null) {
                return new IntegrityReport(corpusName, 0, 0, "FAILED", issues, List.of());
            }
        } else {
            try {
                manifest = ChainManifest.load(chainJsonPath);
            } catch (IOException e) {
                issues.add(new IntegrityIssue(Severity.ERROR, null,
                        "Failed to load chain.json: " + e.getMessage()));
                return new IntegrityReport(corpusName, 0, 0, "FAILED", issues, List.of());
            }
        }

        chainLength = manifest.entries().size();

        // Check each entry in chain.json
        Set<String> referencedZips = new HashSet<>();
        for (ChainEntry entry : manifest.entries()) {
            referencedZips.add(entry.file());
            Path zipPath = corpusSource.resolve(entry.file());

            // Check if ZIP exists
            if (!Files.exists(zipPath)) {
                issues.add(new IntegrityIssue(Severity.ERROR, entry.file(),
                        "ZIP file not found: " + entry.file()));
                continue;
            }

            try {
                ZipFile zipFile = new ZipFile(zipPath.toFile());

                // Count document entries
                int actualEntryCount = countDocumentEntries(zipFile);
                totalEntries += actualEntryCount;

                // Check entry count mismatch
                if ("closed".equals(entry.status()) && entry.entryCount() != actualEntryCount) {
                    issues.add(new IntegrityIssue(Severity.WARNING, entry.file(),
                            "Entry count mismatch: chain.json reports " + entry.entryCount() +
                                    " but ZIP contains " + actualEntryCount));
                }

                // For closed entries, check internal meta.json
                if ("closed".equals(entry.status())) {
                    FileHeader metaHeader = zipFile.getFileHeader(CHAIN_PREFIX + "meta.json");
                    if (metaHeader == null) {
                        issues.add(new IntegrityIssue(Severity.INFO, entry.file(),
                                "_chain/meta.json missing in closed ZIP (can be reconstructed)"));
                    } else {
                        // Verify internal meta agrees with chain.json
                        try (InputStream is = zipFile.getInputStream(metaHeader)) {
                            String metaJson = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                            if (!verifyInternalMeta(metaJson, entry)) {
                                issues.add(new IntegrityIssue(Severity.WARNING, entry.file(),
                                        "_chain/meta.json does not match chain.json"));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                issues.add(new IntegrityIssue(Severity.ERROR, entry.file(),
                        "Failed to read ZIP file: " + e.getMessage()));
            }
        }

        // Scan for orphaned ZIPs
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(corpusSource, "*.zip")) {
            for (Path zipPath : stream) {
                String fileName = zipPath.getFileName().toString();
                if (!referencedZips.contains(fileName)) {
                    issues.add(new IntegrityIssue(Severity.WARNING, fileName,
                            "Orphaned ZIP file not referenced in chain.json"));
                }
            }
        } catch (IOException e) {
            issues.add(new IntegrityIssue(Severity.WARNING, null,
                    "Failed to scan directory for orphaned ZIPs: " + e.getMessage()));
        }

        // Determine status
        String status = determineStatus(issues);

        return new IntegrityReport(corpusName, chainLength, totalEntries, status, issues, List.of());
    }

    @Override
    public IntegrityReport checkAndRecover() {
        // First run check
        IntegrityReport initialReport = check();
        List<String> recovered = new ArrayList<>();
        List<IntegrityIssue> remainingIssues = new ArrayList<>(initialReport.issues());

        // Load manifest
        Path chainJsonPath = corpusSource.resolve("chain.json");
        ChainManifest manifest;
        boolean chainJsonRecovered = false;

        if (!Files.exists(chainJsonPath)) {
            // Reconstruct chain.json from internal meta
            manifest = reconstructManifestFromZips();
            if (manifest != null) {
                try {
                    manifest.save(chainJsonPath);
                    recovered.add("Reconstructed chain.json from internal _chain/meta.json files");
                    chainJsonRecovered = true;
                    // Remove the warning about missing chain.json
                    remainingIssues.removeIf(i -> i.message().contains("chain.json not found"));
                } catch (IOException e) {
                    remainingIssues.add(new IntegrityIssue(Severity.ERROR, null,
                            "Failed to save reconstructed chain.json: " + e.getMessage()));
                }
            } else {
                // Cannot reconstruct
                return new IntegrityReport(corpusName, initialReport.chainLength(),
                        initialReport.totalEntries(), "FAILED", remainingIssues, recovered);
            }
        } else {
            try {
                manifest = ChainManifest.load(chainJsonPath);
            } catch (IOException e) {
                // Cannot proceed without a valid manifest
                return new IntegrityReport(corpusName, initialReport.chainLength(),
                        initialReport.totalEntries(), "FAILED", remainingIssues, recovered);
            }
        }

        // Recover missing internal meta.json in closed ZIPs
        for (ChainEntry entry : manifest.entries()) {
            if (!"closed".equals(entry.status())) continue;

            Path zipPath = corpusSource.resolve(entry.file());
            if (!Files.exists(zipPath)) continue;

            try {
                ZipFile zipFile = new ZipFile(zipPath.toFile());
                FileHeader metaHeader = zipFile.getFileHeader(CHAIN_PREFIX + "meta.json");

                if (metaHeader == null) {
                    // Reconstruct and add _chain/meta.json
                    String metaJson = buildMetaJson(entry);
                    ZipParameters params = new ZipParameters();
                    params.setFileNameInZip(CHAIN_PREFIX + "meta.json");
                    params.setCompressionMethod(CompressionMethod.DEFLATE);
                    zipFile.addStream(
                            new ByteArrayInputStream(metaJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            params);

                    recovered.add("Reconstructed _chain/meta.json in " + entry.file());
                    // Remove the INFO issue about missing internal meta
                    remainingIssues.removeIf(i -> i.zipFile() != null &&
                            i.zipFile().equals(entry.file()) &&
                            i.message().contains("_chain/meta.json missing"));
                }
            } catch (IOException e) {
                remainingIssues.add(new IntegrityIssue(Severity.ERROR, entry.file(),
                        "Failed to recover _chain/meta.json: " + e.getMessage()));
            }
        }

        // Recover orphaned ZIPs by adding them to chain.json
        if (!chainJsonRecovered) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(corpusSource, "*.zip")) {
                for (Path zipPath : stream) {
                    String fileName = zipPath.getFileName().toString();
                    boolean referenced = manifest.entries().stream()
                            .anyMatch(e -> e.file().equals(fileName));

                    if (!referenced) {
                        // Attempt to add to manifest
                        ChainEntry orphanEntry = createEntryFromOrphanedZip(zipPath, manifest);
                        if (orphanEntry != null) {
                            manifest.addEntry(orphanEntry);
                            recovered.add("Added orphaned ZIP to chain.json: " + fileName);
                            // Remove the warning about orphaned ZIP
                            remainingIssues.removeIf(i -> i.zipFile() != null &&
                                    i.zipFile().equals(fileName) &&
                                    i.message().contains("Orphaned"));
                        }
                    }
                }

                // Save manifest if we added orphaned ZIPs
                if (recovered.stream().anyMatch(r -> r.contains("orphaned ZIP"))) {
                    manifest.save(chainJsonPath);
                }
            } catch (IOException e) {
                remainingIssues.add(new IntegrityIssue(Severity.WARNING, null,
                        "Failed to recover orphaned ZIPs: " + e.getMessage()));
            }
        }

        String status = determineStatus(remainingIssues);

        return new IntegrityReport(corpusName, manifest.entries().size(),
                initialReport.totalEntries(), status, remainingIssues, recovered);
    }

    @Override
    public IntegrityReport fullHashVerification() {
        List<IntegrityIssue> issues = new ArrayList<>();
        int chainLength = 0;
        long totalEntries = 0;

        // Load chain.json
        Path chainJsonPath = corpusSource.resolve("chain.json");
        if (!Files.exists(chainJsonPath)) {
            issues.add(new IntegrityIssue(Severity.ERROR, null,
                    "chain.json not found — cannot perform hash verification"));
            return new IntegrityReport(corpusName, 0, 0, "FAILED", issues, List.of());
        }

        ChainManifest manifest;
        try {
            manifest = ChainManifest.load(chainJsonPath);
        } catch (IOException e) {
            issues.add(new IntegrityIssue(Severity.ERROR, null,
                    "Failed to load chain.json: " + e.getMessage()));
            return new IntegrityReport(corpusName, 0, 0, "FAILED", issues, List.of());
        }

        chainLength = manifest.entries().size();

        // Verify hash for each closed entry with a contentHash
        for (ChainEntry entry : manifest.entries()) {
            if (!"closed".equals(entry.status())) continue;
            if (entry.contentHash() == null || entry.contentHash().isBlank()) continue;

            Path zipPath = corpusSource.resolve(entry.file());
            if (!Files.exists(zipPath)) {
                issues.add(new IntegrityIssue(Severity.ERROR, entry.file(),
                        "ZIP file not found: " + entry.file()));
                continue;
            }

            try {
                String actualHash = computeHash(zipPath);
                if (!actualHash.equals(entry.contentHash())) {
                    issues.add(new IntegrityIssue(Severity.ERROR, entry.file(),
                            "Content hash mismatch: expected " + entry.contentHash() +
                                    " but got " + actualHash));
                } else {
                    issues.add(new IntegrityIssue(Severity.INFO, entry.file(),
                            "Hash verification passed"));
                }

                // Count entries
                ZipFile zipFile = new ZipFile(zipPath.toFile());
                totalEntries += countDocumentEntries(zipFile);
            } catch (IOException e) {
                issues.add(new IntegrityIssue(Severity.ERROR, entry.file(),
                        "Failed to verify hash: " + e.getMessage()));
            }
        }

        String status = determineStatus(issues);

        return new IntegrityReport(corpusName, chainLength, totalEntries, status, issues, List.of());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private int countDocumentEntries(ZipFile zipFile) throws IOException {
        int count = 0;
        for (FileHeader header : zipFile.getFileHeaders()) {
            String name = header.getFileName();
            if (!name.startsWith(CHAIN_PREFIX) && !name.startsWith(TOMBSTONE_PREFIX)) {
                count++;
            }
        }
        return count;
    }

    private String computeHash(Path zipPath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream is = Files.newInputStream(zipPath)) {
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hash = digest.digest();
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private boolean verifyInternalMeta(String metaJson, ChainEntry entry) {
        // Simple verification: check if UUID matches
        return metaJson.contains("\"uuid\": \"" + entry.uuid() + "\"");
    }

    private String buildMetaJson(ChainEntry entry) {
        var sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"uuid\": \"").append(entry.uuid()).append("\",\n");
        sb.append("  \"file\": \"").append(entry.file()).append("\",\n");
        sb.append("  \"sequence\": ").append(entry.sequence()).append(",\n");
        sb.append("  \"status\": \"closed\",\n");
        String hash = entry.contentHash() != null ? entry.contentHash() : "null";
        if (entry.contentHash() != null) {
            sb.append("  \"contentHash\": \"").append(hash).append("\"\n");
        } else {
            sb.append("  \"contentHash\": null\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private ChainManifest reconstructManifestFromZips() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(corpusSource, "*.zip")) {
            ChainManifest manifest = ChainManifest.create(corpusName);

            List<Path> zipPaths = new ArrayList<>();
            stream.forEach(zipPaths::add);
            zipPaths.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));

            String prevUuid = null;
            for (Path zipPath : zipPaths) {
                try {
                    ZipFile zipFile = new ZipFile(zipPath.toFile());
                    FileHeader metaHeader = zipFile.getFileHeader(CHAIN_PREFIX + "meta.json");

                    if (metaHeader != null) {
                        try (InputStream is = zipFile.getInputStream(metaHeader)) {
                            String metaJson = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                            ChainEntry entry = parseMetaJson(metaJson, zipPath.getFileName().toString(), prevUuid);
                            if (entry != null) {
                                manifest.addEntry(entry);
                                prevUuid = entry.uuid();
                            }
                        }
                    }
                } catch (IOException e) {
                    // Skip this ZIP
                }
            }

            return manifest.entries().isEmpty() ? null : manifest;
        } catch (IOException e) {
            return null;
        }
    }

    private ChainEntry parseMetaJson(String metaJson, String fileName, String predecessor) {
        // Simple JSON parsing for the fields we need
        String uuid = extractJsonString(metaJson, "uuid");
        int sequence = extractJsonInt(metaJson, "sequence");
        String contentHash = extractJsonString(metaJson, "contentHash");

        if (uuid == null) return null;

        return new ChainEntry(uuid, fileName, sequence, "closed", predecessor,
                0, 0, contentHash, null, null, null, null);
    }

    private ChainEntry createEntryFromOrphanedZip(Path zipPath, ChainManifest manifest) {
        try {
            ZipFile zipFile = new ZipFile(zipPath.toFile());
            FileHeader metaHeader = zipFile.getFileHeader(CHAIN_PREFIX + "meta.json");

            if (metaHeader != null) {
                // Has internal meta — use it
                try (InputStream is = zipFile.getInputStream(metaHeader)) {
                    String metaJson = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    String prevUuid = manifest.entries().isEmpty() ? null : manifest.entries().getLast().uuid();
                    return parseMetaJson(metaJson, zipPath.getFileName().toString(), prevUuid);
                }
            } else {
                // No internal meta — create a basic entry
                int nextSequence = manifest.entries().stream()
                        .mapToInt(ChainEntry::sequence)
                        .max()
                        .orElse(-1) + 1;
                String prevUuid = manifest.entries().isEmpty() ? null : manifest.entries().getLast().uuid();

                return new ChainEntry(
                        java.util.UUID.randomUUID().toString(),
                        zipPath.getFileName().toString(),
                        nextSequence,
                        "closed",
                        prevUuid,
                        countDocumentEntries(zipFile),
                        0,
                        null,
                        null,
                        null,
                        null,
                        null
                );
            }
        } catch (IOException e) {
            return null;
        }
    }

    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\": \"";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end);
    }

    private int extractJsonInt(String json, String key) {
        String pattern = "\"" + key + "\": ";
        int start = json.indexOf(pattern);
        if (start == -1) return 0;
        start += pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String determineStatus(List<IntegrityIssue> issues) {
        boolean hasError = issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
        boolean hasWarning = issues.stream().anyMatch(i -> i.severity() == Severity.WARNING);

        if (hasError) return "FAILED";
        if (hasWarning) return "DEGRADED";
        return "OK";
    }
}
