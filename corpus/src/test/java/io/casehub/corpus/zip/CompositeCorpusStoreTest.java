package io.casehub.corpus.zip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeCorpusStoreTest {

    @TempDir
    Path tempDir;

    private Path zipDir;
    private Path flatDir;
    private ZipCorpusStore zipStore;
    private FlatCorpusStore flatStore;
    private CompositeCorpusStore composite;

    @BeforeEach
    void setUp() {
        zipDir = tempDir.resolve("zips");
        flatDir = tempDir.resolve("flat");
        zipStore = new ZipCorpusStore(new CorpusConfig("test-corpus", zipDir));
        flatStore = new FlatCorpusStore(flatDir);
        composite = new CompositeCorpusStore(zipStore, flatStore);
    }

    // --- append writes to both stores ---

    @Test
    void appendWritesToBothStores() {
        byte[] content = "hello composite".getBytes(StandardCharsets.UTF_8);

        composite.append("docs/readme.md", content);

        // Readable from ZIP
        assertThat(asString(zipStore.read("docs/readme.md"))).isEqualTo("hello composite");
        // Readable from flat
        assertThat(asString(flatStore.read("docs/readme.md"))).isEqualTo("hello composite");
    }

    // --- append with InputStream writes to both ---

    @Test
    void appendInputStreamWritesToBothStores() {
        byte[] content = "stream content".getBytes(StandardCharsets.UTF_8);
        InputStream is = new ByteArrayInputStream(content);

        composite.append("docs/stream.md", is);

        assertThat(asString(zipStore.read("docs/stream.md"))).isEqualTo("stream content");
        assertThat(asString(flatStore.read("docs/stream.md"))).isEqualTo("stream content");
    }

    // --- append with Path writes to both ---

    @Test
    void appendPathWritesToBothStores() throws Exception {
        Path file = tempDir.resolve("input.txt");
        Files.writeString(file, "from file");

        composite.append("docs/from-file.md", file);

        assertThat(asString(zipStore.read("docs/from-file.md"))).isEqualTo("from file");
        assertThat(asString(flatStore.read("docs/from-file.md"))).isEqualTo("from file");
    }

    // --- read delegates to ZIP (authoritative) ---

    @Test
    void readDelegatesToZipStore() {
        byte[] content = "zip content".getBytes(StandardCharsets.UTF_8);
        composite.append("docs/readme.md", content);

        // Read via composite — should come from ZIP
        assertThat(asString(composite.read("docs/readme.md"))).isEqualTo("zip content");
    }

    @Test
    void readDoesNotSeeFileOnlyInFlat() {
        // Write directly to flat, bypassing composite
        flatStore.append("docs/flat-only.md", "flat only".getBytes(StandardCharsets.UTF_8));

        // Composite reads from ZIP — flat-only file not visible
        assertThat(composite.read("docs/flat-only.md")).isEmpty();
        assertThat(composite.exists("docs/flat-only.md")).isFalse();
    }

    // --- delete deletes from both ---

    @Test
    void deleteDeletesFromBoth() {
        composite.append("docs/readme.md", "content".getBytes(StandardCharsets.UTF_8));

        composite.delete("docs/readme.md");

        // Gone from ZIP (tombstoned)
        assertThat(zipStore.exists("docs/readme.md")).isFalse();
        assertThat(zipStore.read("docs/readme.md")).isEmpty();
        // Gone from flat
        assertThat(flatStore.exists("docs/readme.md")).isFalse();
    }

    // --- list delegates to ZIP ---

    @Test
    void listDelegatesToZipStore() {
        composite.append("a.md", "a".getBytes(StandardCharsets.UTF_8));
        composite.append("b.md", "b".getBytes(StandardCharsets.UTF_8));

        assertThat(composite.list()).containsExactly("a.md", "b.md");
    }

    @Test
    void listWithPrefixDelegatesToZipStore() {
        composite.append("docs/a.md", "a".getBytes(StandardCharsets.UTF_8));
        composite.append("docs/b.md", "b".getBytes(StandardCharsets.UTF_8));
        composite.append("other/c.md", "c".getBytes(StandardCharsets.UTF_8));

        assertThat(composite.list("docs/")).containsExactly("docs/a.md", "docs/b.md");
    }

    // --- exists delegates to ZIP ---

    @Test
    void existsDelegatesToZipStore() {
        composite.append("docs/readme.md", "content".getBytes(StandardCharsets.UTF_8));

        assertThat(composite.exists("docs/readme.md")).isTrue();
        assertThat(composite.exists("no/such/path")).isFalse();
    }

    // --- versions delegates to ZIP ---

    @Test
    void versionsDelegatesToZipStore() {
        composite.append("docs/readme.md", "v1".getBytes(StandardCharsets.UTF_8));
        composite.append("docs/readme.md", "v2".getBytes(StandardCharsets.UTF_8));

        var versions = composite.versions("docs/readme.md");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).version()).isEqualTo(1);
        assertThat(versions.get(1).version()).isEqualTo(2);
    }

    // --- readVersion delegates to ZIP ---

    @Test
    void readVersionDelegatesToZipStore() {
        composite.append("docs/readme.md", "first".getBytes(StandardCharsets.UTF_8));
        composite.append("docs/readme.md", "second".getBytes(StandardCharsets.UTF_8));

        assertThat(asString(composite.readVersion("docs/readme.md", 1))).isEqualTo("first");
        assertThat(asString(composite.readVersion("docs/readme.md", 2))).isEqualTo("second");
    }

    // --- readStream delegates to ZIP ---

    @Test
    void readStreamDelegatesToZipStore() throws Exception {
        composite.append("docs/readme.md", "stream data".getBytes(StandardCharsets.UTF_8));

        Optional<InputStream> result = composite.readStream("docs/readme.md");
        assertThat(result).isPresent();
        try (InputStream is = result.get()) {
            assertThat(new String(is.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("stream data");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static String asString(Optional<byte[]> bytes) {
        return bytes.map(b -> new String(b, StandardCharsets.UTF_8)).orElse(null);
    }
}
