package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.CorpusReader;
import io.casehub.neocortex.corpus.CorpusStore;
import io.casehub.neocortex.corpus.VersionInfo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filesystem-based implementation of {@link CorpusStore} and {@link CorpusReader}.
 *
 * <p>Maps document paths directly to filesystem paths under a root directory.
 * Simpler than ZIP-backed storage — no versioning, no tombstones.
 * Each path has at most one version (the current file content).
 */
public final class FlatCorpusStore implements CorpusStore, CorpusReader {

    private final Path rootDir;

    public FlatCorpusStore(Path rootDir) {
        this.rootDir = rootDir;
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create root directory: " + rootDir, e);
        }
    }

    @Override
    public void append(String path, byte[] content) {
        validatePath(path);
        Path target = rootDir.resolve(path);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write: " + path, e);
        }
    }

    @Override
    public void append(String path, InputStream content) {
        try {
            append(path, content.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read input stream for: " + path, e);
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
        validatePath(path);
        Path target = rootDir.resolve(path);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete: " + path, e);
        }
    }

    @Override
    public Optional<byte[]> read(String path) {
        Path target = rootDir.resolve(path);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read: " + path, e);
        }
    }

    @Override
    public Optional<InputStream> readStream(String path) {
        return read(path).map(ByteArrayInputStream::new);
    }

    @Override
    public Optional<byte[]> readVersion(String path, int version) {
        if (version != 1) {
            return Optional.empty();
        }
        return read(path);
    }

    @Override
    public List<VersionInfo> versions(String path) {
        Path target = rootDir.resolve(path);
        if (!Files.exists(target)) {
            return List.of();
        }
        try {
            Instant timestamp = Files.getLastModifiedTime(target).toInstant();
            return List.of(new VersionInfo(1, timestamp, null));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to get last modified time for: " + path, e);
        }
    }

    @Override
    public List<String> list() {
        try (Stream<Path> walk = Files.walk(rootDir)) {
            return walk
                .filter(Files::isRegularFile)
                .map(rootDir::relativize)
                .map(Path::toString)
                .filter(p -> !p.startsWith("_"))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list files in: " + rootDir, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        return list().stream()
            .filter(p -> p.startsWith(prefix))
            .toList();
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(rootDir.resolve(path));
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
}
