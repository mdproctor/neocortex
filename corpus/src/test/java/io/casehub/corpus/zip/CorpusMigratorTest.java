package io.casehub.corpus.zip;

import io.casehub.corpus.CorpusReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorpusMigratorTest {

    @Test
    void migrateEmptyDirectoryReturnsZero(@TempDir Path sourceDir, @TempDir Path targetDir) {
        ZipCorpusStore target = new ZipCorpusStore(
                new CorpusConfig("test", targetDir, 10 * 1024 * 1024));

        int count = CorpusMigrator.migrate(sourceDir, target);

        assertThat(count).isZero();
    }

    @Test
    void migratePreservesDirectoryStructure(@TempDir Path sourceDir, @TempDir Path targetDir) throws IOException {
        // Create directory structure
        Files.createDirectories(sourceDir.resolve("tools"));
        Files.createDirectories(sourceDir.resolve("jvm"));
        Files.writeString(sourceDir.resolve("tools/a.md"), "content a");
        Files.writeString(sourceDir.resolve("jvm/b.md"), "content b");

        ZipCorpusStore target = new ZipCorpusStore(
                new CorpusConfig("test", targetDir, 10 * 1024 * 1024));

        int count = CorpusMigrator.migrate(sourceDir, target);

        assertThat(count).isEqualTo(2);

        CorpusReader reader = target;
        assertThat(reader.exists("tools/a.md")).isTrue();
        assertThat(reader.exists("jvm/b.md")).isTrue();
        assertThat(reader.read("tools/a.md"))
                .isPresent()
                .get()
                .satisfies(bytes -> assertThat(new String(bytes)).isEqualTo("content a"));
        assertThat(reader.read("jvm/b.md"))
                .isPresent()
                .get()
                .satisfies(bytes -> assertThat(new String(bytes)).isEqualTo("content b"));
    }

    @Test
    void migrateSkipsUnderscorePrefixedFiles(@TempDir Path sourceDir, @TempDir Path targetDir) throws IOException {
        Files.writeString(sourceDir.resolve("_internal.md"), "internal");
        Files.writeString(sourceDir.resolve("visible.md"), "visible");

        ZipCorpusStore target = new ZipCorpusStore(
                new CorpusConfig("test", targetDir, 10 * 1024 * 1024));

        int count = CorpusMigrator.migrate(sourceDir, target);

        assertThat(count).isEqualTo(1);

        CorpusReader reader = target;
        assertThat(reader.exists("_internal.md")).isFalse();
        assertThat(reader.exists("visible.md")).isTrue();
    }

    @Test
    void migrateReturnsFileCount(@TempDir Path sourceDir, @TempDir Path targetDir) throws IOException {
        Files.writeString(sourceDir.resolve("file1.md"), "one");
        Files.writeString(sourceDir.resolve("file2.md"), "two");
        Files.writeString(sourceDir.resolve("file3.md"), "three");
        Files.writeString(sourceDir.resolve("file4.md"), "four");
        Files.writeString(sourceDir.resolve("file5.md"), "five");

        ZipCorpusStore target = new ZipCorpusStore(
                new CorpusConfig("test", targetDir, 10 * 1024 * 1024));

        int count = CorpusMigrator.migrate(sourceDir, target);

        assertThat(count).isEqualTo(5);
    }

    @Test
    void migrateNonExistentDirectoryThrows(@TempDir Path targetDir) {
        Path nonExistent = Path.of("/non/existent/path");

        ZipCorpusStore target = new ZipCorpusStore(
                new CorpusConfig("test", targetDir, 10 * 1024 * 1024));

        assertThatThrownBy(() -> CorpusMigrator.migrate(nonExistent, target))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceDir must be an existing directory");
    }

    @Test
    void migrateAllFileTypes(@TempDir Path sourceDir, @TempDir Path targetDir) throws IOException {
        Files.writeString(sourceDir.resolve("readme.md"), "markdown");
        Files.writeString(sourceDir.resolve("notes.txt"), "text");
        Files.writeString(sourceDir.resolve("config.json"), "{\"key\": \"value\"}");

        ZipCorpusStore target = new ZipCorpusStore(
                new CorpusConfig("test", targetDir, 10 * 1024 * 1024));

        int count = CorpusMigrator.migrate(sourceDir, target);

        assertThat(count).isEqualTo(3);

        CorpusReader reader = target;
        assertThat(reader.exists("readme.md")).isTrue();
        assertThat(reader.exists("notes.txt")).isTrue();
        assertThat(reader.exists("config.json")).isTrue();
    }
}
