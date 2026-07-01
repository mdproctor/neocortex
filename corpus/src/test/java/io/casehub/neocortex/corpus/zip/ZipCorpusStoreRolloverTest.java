package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.VersionInfo;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ZipCorpusStoreRolloverTest {

    @TempDir
    Path tempDir;

    /**
     * Creates a store with a very small maxZipSize to trigger rollover easily.
     */
    private ZipCorpusStore createSmallStore(long maxZipSize) {
        return new ZipCorpusStore(new CorpusConfig("test-corpus", tempDir, maxZipSize));
    }

    /**
     * Generates a byte array of the given size filled with incompressible
     * random data (deflate cannot shrink it much).
     */
    private byte[] payload(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    // --- rollover creates new ZIP when size exceeded ---

    @Test
    void rolloverCreatesNewZipWhenSizeExceeded() throws IOException {
        var store = createSmallStore(1024);

        // First append — small, won't trigger rollover
        store.append("doc/a.txt", "hello".getBytes(StandardCharsets.UTF_8));
        assertThat(zipFiles()).hasSize(1);

        // Append enough incompressible data to exceed 1024 bytes and trigger rollover
        store.append("doc/b.txt", payload(4000));

        // Verify chain.json shows a closed entry and a new active entry
        Path chainJson = tempDir.resolve("chain.json");
        String chain = Files.readString(chainJson);
        assertThat(chain).contains("\"status\": \"closed\"");
        assertThat(chain).contains("\"status\": \"active\"");

        // Append to the new active zip — this materialises the second ZIP on disk
        store.append("doc/c.txt", "after-rollover".getBytes(StandardCharsets.UTF_8));
        assertThat(zipFiles()).hasSizeGreaterThanOrEqualTo(2);
    }

    // --- read works across multiple ZIPs ---

    @Test
    void readWorksAcrossMultipleZips() {
        var store = createSmallStore(1024);

        byte[] contentA = "content-A".getBytes(StandardCharsets.UTF_8);
        store.append("doc/a.txt", contentA);

        // Force rollover with large incompressible payload
        store.append("doc/b.txt", payload(2000));

        // This should be in the second ZIP
        byte[] contentC = "content-C".getBytes(StandardCharsets.UTF_8);
        store.append("doc/c.txt", contentC);

        // All entries should be readable regardless of which ZIP they're in
        assertThat(asString(store.read("doc/a.txt"))).isEqualTo("content-A");
        assertThat(store.read("doc/b.txt")).isPresent();
        assertThat(asString(store.read("doc/c.txt"))).isEqualTo("content-C");
    }

    // --- versions span multiple ZIPs ---

    @Test
    void versionsSpanMultipleZips() {
        var store = createSmallStore(1024);

        // v1 in ZIP-1
        store.append("doc/readme.md", "version-1".getBytes(StandardCharsets.UTF_8));

        // Force rollover
        store.append("doc/filler.txt", payload(2000));

        // v2 in ZIP-2
        store.append("doc/readme.md", "version-2".getBytes(StandardCharsets.UTF_8));

        List<VersionInfo> versions = store.versions("doc/readme.md");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).version()).isEqualTo(1);
        assertThat(versions.get(1).version()).isEqualTo(2);

        // Different ZIP files for each version
        assertThat(versions.get(0).zipFile()).isNotEqualTo(versions.get(1).zipFile());

        // Content is correct for each version
        assertThat(asString(store.readVersion("doc/readme.md", 1))).isEqualTo("version-1");
        assertThat(asString(store.readVersion("doc/readme.md", 2))).isEqualTo("version-2");
    }

    // --- chain.json reflects rollover ---

    @Test
    void chainJsonReflectsRollover() throws IOException {
        var store = createSmallStore(1024);

        store.append("doc/a.txt", "hello".getBytes(StandardCharsets.UTF_8));
        store.append("doc/b.txt", payload(2000));

        // Verify chain.json exists and has expected content
        Path chainJson = tempDir.resolve("chain.json");
        assertThat(chainJson).exists();

        String chainContent = Files.readString(chainJson);

        // Should have at least two chain entries
        // One closed (with contentHash) and one active
        assertThat(chainContent).contains("\"status\": \"closed\"");
        assertThat(chainContent).contains("\"status\": \"active\"");
        assertThat(chainContent).contains("\"contentHash\": \"sha256:");
    }

    // --- closed ZIP contains internal meta ---

    @Test
    void closedZipContainsInternalMeta() throws IOException {
        var store = createSmallStore(1024);

        store.append("doc/a.txt", "hello".getBytes(StandardCharsets.UTF_8));
        store.append("doc/b.txt", payload(2000));

        // Find the first ZIP (the one that was closed)
        Path firstZip = tempDir.resolve("test-corpus-1.zip");
        assertThat(firstZip).exists();

        // Verify it contains _chain/meta.json
        ZipFile zipFile = new ZipFile(firstZip.toFile());
        FileHeader metaHeader = zipFile.getFileHeader("_chain/meta.json");
        assertThat(metaHeader).as("_chain/meta.json should exist in closed ZIP").isNotNull();

        // Read and verify meta.json content
        String metaContent;
        try (var is = zipFile.getInputStream(metaHeader)) {
            metaContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(metaContent).contains("\"status\": \"active\"");
        assertThat(metaContent).contains("\"file\": \"test-corpus-1.zip\"");
    }

    // --- startup rebuilds across multiple ZIPs ---

    @Test
    void startupRebuildsAcrossMultipleZips() {
        // Phase 1: create store, populate, and trigger rollover
        var store1 = createSmallStore(1024);
        byte[] contentA = "data-A".getBytes(StandardCharsets.UTF_8);
        store1.append("doc/a.txt", contentA);
        store1.append("doc/filler.txt", payload(2000)); // trigger rollover
        byte[] contentB = "data-B".getBytes(StandardCharsets.UTF_8);
        store1.append("doc/b.txt", contentB);

        // Verify multiple ZIPs exist
        assertThat(zipFiles()).hasSizeGreaterThanOrEqualTo(2);

        // Phase 2: create a new store against the same directory
        var store2 = createSmallStore(1024);

        // All data should be accessible
        assertThat(asString(store2.read("doc/a.txt"))).isEqualTo("data-A");
        assertThat(store2.read("doc/filler.txt")).isPresent();
        assertThat(asString(store2.read("doc/b.txt"))).isEqualTo("data-B");

        // Listing should show all paths
        assertThat(store2.list()).containsExactlyInAnyOrder(
                "doc/a.txt", "doc/filler.txt", "doc/b.txt");

        // Should still be able to append
        store2.append("doc/c.txt", "data-C".getBytes(StandardCharsets.UTF_8));
        assertThat(asString(store2.read("doc/c.txt"))).isEqualTo("data-C");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private List<Path> zipFiles() {
        try (var stream = Files.list(tempDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".zip"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String asString(Optional<byte[]> bytes) {
        return bytes.map(b -> new String(b, StandardCharsets.UTF_8)).orElse(null);
    }
}
