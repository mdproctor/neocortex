package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.VersionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipCorpusStoreTest {

    @TempDir
    Path tempDir;

    private ZipCorpusStore createStore() {
        return new ZipCorpusStore(new CorpusConfig("test-corpus", tempDir));
    }

    // --- append and read ---

    @Test
    void appendAndRead() {
        var store = createStore();
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        store.append("docs/readme.md", content);

        Optional<byte[]> result = store.read("docs/readme.md");
        assertThat(result).isPresent();
        assertThat(new String(result.get(), StandardCharsets.UTF_8)).isEqualTo("hello world");
    }

    // --- append overwrites create new version ---

    @Test
    void appendOverwriteCreatesNewVersion() {
        var store = createStore();
        byte[] v1 = "version one".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "version two".getBytes(StandardCharsets.UTF_8);

        store.append("docs/readme.md", v1);
        store.append("docs/readme.md", v2);

        // read() returns latest
        Optional<byte[]> latest = store.read("docs/readme.md");
        assertThat(latest).isPresent();
        assertThat(new String(latest.get(), StandardCharsets.UTF_8)).isEqualTo("version two");

        // both versions accessible
        Optional<byte[]> readV1 = store.readVersion("docs/readme.md", 1);
        assertThat(readV1).isPresent();
        assertThat(new String(readV1.get(), StandardCharsets.UTF_8)).isEqualTo("version one");

        Optional<byte[]> readV2 = store.readVersion("docs/readme.md", 2);
        assertThat(readV2).isPresent();
        assertThat(new String(readV2.get(), StandardCharsets.UTF_8)).isEqualTo("version two");
    }

    // --- read non-existent ---

    @Test
    void readNonExistent() {
        var store = createStore();
        assertThat(store.read("no/such/path")).isEmpty();
    }

    // --- readStream ---

    @Test
    void readStreamReturnsContent() throws Exception {
        var store = createStore();
        byte[] content = "stream content".getBytes(StandardCharsets.UTF_8);
        store.append("docs/stream.md", content);

        Optional<InputStream> result = store.readStream("docs/stream.md");
        assertThat(result).isPresent();
        try (InputStream is = result.get()) {
            assertThat(new String(is.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("stream content");
        }
    }

    // --- delete marks as tombstone ---

    @Test
    void deleteMarksAsTombstone() {
        var store = createStore();
        store.append("docs/readme.md", "content".getBytes(StandardCharsets.UTF_8));

        store.delete("docs/readme.md");

        assertThat(store.exists("docs/readme.md")).isFalse();
        assertThat(store.list()).doesNotContain("docs/readme.md");
        assertThat(store.read("docs/readme.md")).isEmpty();
    }

    // --- delete and re-append ---

    @Test
    void deleteAndReAppend() {
        var store = createStore();
        store.append("docs/readme.md", "original".getBytes(StandardCharsets.UTF_8));
        store.delete("docs/readme.md");

        store.append("docs/readme.md", "revived".getBytes(StandardCharsets.UTF_8));

        assertThat(store.exists("docs/readme.md")).isTrue();
        Optional<byte[]> result = store.read("docs/readme.md");
        assertThat(result).isPresent();
        assertThat(new String(result.get(), StandardCharsets.UTF_8)).isEqualTo("revived");

        // Should be version 2 (original was v1, revived is v2)
        List<VersionInfo> versions = store.versions("docs/readme.md");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(1).version()).isEqualTo(2);
    }

    // --- list returns all paths ---

    @Test
    void listReturnsAllPaths() {
        var store = createStore();
        store.append("a/doc.md", "a".getBytes(StandardCharsets.UTF_8));
        store.append("b/doc.md", "b".getBytes(StandardCharsets.UTF_8));
        store.append("c/doc.md", "c".getBytes(StandardCharsets.UTF_8));

        List<String> paths = store.list();
        assertThat(paths).containsExactly("a/doc.md", "b/doc.md", "c/doc.md");
    }

    // --- list with prefix ---

    @Test
    void listWithPrefix() {
        var store = createStore();
        store.append("tools/maven.md", "m".getBytes(StandardCharsets.UTF_8));
        store.append("tools/gradle.md", "g".getBytes(StandardCharsets.UTF_8));
        store.append("jvm/gc.md", "gc".getBytes(StandardCharsets.UTF_8));

        assertThat(store.list("tools/")).containsExactlyInAnyOrder(
                "tools/maven.md", "tools/gradle.md");
        assertThat(store.list("jvm/")).containsExactly("jvm/gc.md");
        assertThat(store.list("nonexistent/")).isEmpty();
    }

    // --- versions returns history ---

    @Test
    void versionsReturnsHistory() {
        var store = createStore();
        store.append("docs/readme.md", "v1".getBytes(StandardCharsets.UTF_8));
        store.append("docs/readme.md", "v2".getBytes(StandardCharsets.UTF_8));
        store.append("docs/readme.md", "v3".getBytes(StandardCharsets.UTF_8));

        List<VersionInfo> versions = store.versions("docs/readme.md");
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).version()).isEqualTo(1);
        assertThat(versions.get(1).version()).isEqualTo(2);
        assertThat(versions.get(2).version()).isEqualTo(3);
    }

    // --- readVersion returns specific content ---

    @Test
    void readVersionReturnsSpecificContent() {
        var store = createStore();
        store.append("docs/readme.md", "first".getBytes(StandardCharsets.UTF_8));
        store.append("docs/readme.md", "second".getBytes(StandardCharsets.UTF_8));
        store.append("docs/readme.md", "third".getBytes(StandardCharsets.UTF_8));

        assertThat(asString(store.readVersion("docs/readme.md", 1))).isEqualTo("first");
        assertThat(asString(store.readVersion("docs/readme.md", 2))).isEqualTo("second");
        assertThat(asString(store.readVersion("docs/readme.md", 3))).isEqualTo("third");
    }

    // --- path validation ---

    @Test
    void pathValidationRejectsReservedPrefix() {
        var store = createStore();
        assertThatThrownBy(() -> store.append("_chain/foo", "data".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void pathValidationRejectsUnderscorePrefix() {
        var store = createStore();
        assertThatThrownBy(() -> store.append("_anything", "data".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    // --- exists ---

    @Test
    void existsReturnsTrueForExistingPath() {
        var store = createStore();
        store.append("docs/readme.md", "content".getBytes(StandardCharsets.UTF_8));
        assertThat(store.exists("docs/readme.md")).isTrue();
    }

    @Test
    void existsReturnsFalseForAbsentPath() {
        var store = createStore();
        assertThat(store.exists("no/such/path")).isFalse();
    }

    // --- startup rebuild ---

    @Test
    void startupRebuildsIndex() {
        // Create first store and populate it
        var store1 = new ZipCorpusStore(new CorpusConfig("test-corpus", tempDir));
        store1.append("docs/readme.md", "hello".getBytes(StandardCharsets.UTF_8));
        store1.append("tools/maven.md", "maven guide".getBytes(StandardCharsets.UTF_8));
        store1.append("docs/readme.md", "hello v2".getBytes(StandardCharsets.UTF_8));

        // Create a second store pointing at the same directory — should rebuild
        var store2 = new ZipCorpusStore(new CorpusConfig("test-corpus", tempDir));

        // All entries should be accessible
        assertThat(store2.exists("docs/readme.md")).isTrue();
        assertThat(store2.exists("tools/maven.md")).isTrue();
        assertThat(store2.list()).containsExactly("docs/readme.md", "tools/maven.md");

        // Latest version of docs/readme.md should be v2
        assertThat(asString(store2.read("docs/readme.md"))).isEqualTo("hello v2");

        // Version history should be preserved
        List<VersionInfo> versions = store2.versions("docs/readme.md");
        assertThat(versions).hasSize(2);
        assertThat(asString(store2.readVersion("docs/readme.md", 1))).isEqualTo("hello");
        assertThat(asString(store2.readVersion("docs/readme.md", 2))).isEqualTo("hello v2");
    }

    // --- append via InputStream ---

    @Test
    void appendFromInputStream() {
        var store = createStore();
        InputStream is = new ByteArrayInputStream("from stream".getBytes(StandardCharsets.UTF_8));
        store.append("docs/from-stream.md", is);

        assertThat(asString(store.read("docs/from-stream.md"))).isEqualTo("from stream");
    }

    // --- append via Path ---

    @Test
    void appendFromFile() throws Exception {
        var store = createStore();
        Path file = tempDir.resolve("input.txt");
        Files.writeString(file, "from file");

        store.append("docs/from-file.md", file);

        assertThat(asString(store.read("docs/from-file.md"))).isEqualTo("from file");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static String asString(Optional<byte[]> bytes) {
        return bytes.map(b -> new String(b, StandardCharsets.UTF_8)).orElse(null);
    }
}
