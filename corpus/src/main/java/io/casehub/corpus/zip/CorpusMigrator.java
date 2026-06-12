package io.casehub.corpus.zip;

import io.casehub.corpus.CorpusStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Utility for migrating a directory of files into a {@link CorpusStore}.
 *
 * <p>Preserves directory structure as path prefixes (e.g., {@code tools/GE-123.md}).
 * Files starting with {@code _} are skipped (reserved prefix).
 */
public final class CorpusMigrator {

    private CorpusMigrator() {}

    /**
     * Migrates all regular files from {@code sourceDir} into {@code target}.
     *
     * @param sourceDir the directory to migrate from (must exist and be a directory)
     * @param target the corpus store to append files to
     * @return the count of files migrated
     * @throws IllegalArgumentException if {@code sourceDir} is not an existing directory
     * @throws UncheckedIOException if I/O errors occur during directory walking
     */
    public static int migrate(Path sourceDir, CorpusStore target) {
        if (!Files.isDirectory(sourceDir)) {
            throw new IllegalArgumentException("sourceDir must be an existing directory: " + sourceDir);
        }
        int[] count = {0};
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().startsWith("_"))
                .forEach(file -> {
                    String relativePath = sourceDir.relativize(file).toString();
                    target.append(relativePath, file);
                    count[0]++;
                });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk sourceDir: " + sourceDir, e);
        }
        return count[0];
    }
}
