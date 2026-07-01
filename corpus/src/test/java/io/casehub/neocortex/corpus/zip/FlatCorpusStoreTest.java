package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.VersionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlatCorpusStoreTest {

    @Test
    void appendAndRead(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);
        byte[] content = "test content".getBytes();

        store.append("docs/readme.md", content);

        var read = store.read("docs/readme.md");
        assertTrue(read.isPresent());
        assertArrayEquals(content, read.get());
    }

    @Test
    void appendOverwritesExistingFile(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);

        store.append("file.txt", "first".getBytes());
        store.append("file.txt", "second".getBytes());

        var read = store.read("file.txt");
        assertTrue(read.isPresent());
        assertArrayEquals("second".getBytes(), read.get());
    }

    @Test
    void readNonExistent(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);

        var read = store.read("nonexistent.md");

        assertTrue(read.isEmpty());
    }

    @Test
    void deleteRemovesFile(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);

        store.append("file.txt", "content".getBytes());
        assertTrue(store.exists("file.txt"));

        store.delete("file.txt");

        assertFalse(store.exists("file.txt"));
    }

    @Test
    void listReturnsAllFiles(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);

        store.append("a.txt", "a".getBytes());
        store.append("b.txt", "b".getBytes());
        store.append("sub/c.txt", "c".getBytes());

        var files = store.list();

        assertEquals(3, files.size());
        assertTrue(files.contains("a.txt"));
        assertTrue(files.contains("b.txt"));
        assertTrue(files.contains("sub/c.txt"));
    }

    @Test
    void listWithPrefix(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);

        store.append("docs/a.md", "a".getBytes());
        store.append("docs/b.md", "b".getBytes());
        store.append("other/c.md", "c".getBytes());

        var files = store.list("docs/");

        assertEquals(2, files.size());
        assertTrue(files.contains("docs/a.md"));
        assertTrue(files.contains("docs/b.md"));
    }

    @Test
    void existsWorks(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);

        store.append("file.txt", "content".getBytes());

        assertTrue(store.exists("file.txt"));
        assertFalse(store.exists("nonexistent.txt"));
    }

    @Test
    void versionsReturnsSingleEntry(@TempDir Path tempDir) throws IOException {
        var store = new FlatCorpusStore(tempDir);

        store.append("file.txt", "content".getBytes());

        var versions = store.versions("file.txt");

        assertEquals(1, versions.size());
        VersionInfo v = versions.get(0);
        assertEquals(1, v.version());
        assertNotNull(v.timestamp());
        assertNull(v.zipFile());
    }

    @Test
    void versionsReturnsEmptyForNonExistent(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);

        var versions = store.versions("nonexistent.txt");

        assertTrue(versions.isEmpty());
    }

    @Test
    void readVersionOnlySupportsVersionOne(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);
        byte[] content = "content".getBytes();

        store.append("file.txt", content);

        var v1 = store.readVersion("file.txt", 1);
        assertTrue(v1.isPresent());
        assertArrayEquals(content, v1.get());

        var v2 = store.readVersion("file.txt", 2);
        assertTrue(v2.isEmpty());
    }

    @Test
    void pathValidation(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);

        var ex = assertThrows(IllegalArgumentException.class,
            () -> store.append("_foo", "content".getBytes()));

        assertTrue(ex.getMessage().contains("reserved"));
    }

    @Test
    void appendCreatesParentDirectories(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);

        store.append("deep/nested/file.md", "content".getBytes());

        assertTrue(store.exists("deep/nested/file.md"));
        var read = store.read("deep/nested/file.md");
        assertTrue(read.isPresent());
        assertArrayEquals("content".getBytes(), read.get());
    }

    @Test
    void appendWithInputStream(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);
        byte[] content = "stream content".getBytes();

        store.append("file.txt", new ByteArrayInputStream(content));

        var read = store.read("file.txt");
        assertTrue(read.isPresent());
        assertArrayEquals(content, read.get());
    }

    @Test
    void appendWithPath(@TempDir Path tempDir) throws IOException {
        var store = new FlatCorpusStore(tempDir);
        byte[] content = "file content".getBytes();
        Path srcFile = tempDir.resolve("source.txt");
        Files.write(srcFile, content);

        store.append("dest.txt", srcFile);

        var read = store.read("dest.txt");
        assertTrue(read.isPresent());
        assertArrayEquals(content, read.get());
    }

    @Test
    void readStream(@TempDir Path tempDir) throws IOException {
        var store = new FlatCorpusStore(tempDir);
        byte[] content = "stream test".getBytes();

        store.append("file.txt", content);

        var stream = store.readStream("file.txt");
        assertTrue(stream.isPresent());
        assertArrayEquals(content, stream.get().readAllBytes());
    }

    @Test
    void readStreamNonExistent(@TempDir Path tempDir) {
        var store = new FlatCorpusStore(tempDir);

        var stream = store.readStream("nonexistent.txt");

        assertTrue(stream.isEmpty());
    }
}
