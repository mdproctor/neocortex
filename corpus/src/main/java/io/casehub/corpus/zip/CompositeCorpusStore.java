package io.casehub.corpus.zip;

import io.casehub.corpus.CorpusReader;
import io.casehub.corpus.CorpusStore;
import io.casehub.corpus.VersionInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Composite implementation wrapping a {@link ZipCorpusStore} and a
 * {@link FlatCorpusStore}.
 *
 * <p><b>Writes</b> go to both stores (ZIP is the source of truth;
 * flat provides filesystem access for external tools).
 *
 * <p><b>Reads</b> delegate exclusively to the {@link ZipCorpusStore},
 * which is the authoritative store. Files written directly to the
 * flat directory are not visible until synced into the ZIP via
 * {@link CompositeChangeSource}.
 */
public final class CompositeCorpusStore implements CorpusStore, CorpusReader {

    private final ZipCorpusStore zipStore;
    private final FlatCorpusStore flatStore;

    public CompositeCorpusStore(ZipCorpusStore zipStore, FlatCorpusStore flatStore) {
        this.zipStore = zipStore;
        this.flatStore = flatStore;
    }

    // ── CorpusStore (write to both) ────────────────────────────────────

    @Override
    public void append(String path, byte[] content) {
        zipStore.append(path, content);
        flatStore.append(path, content);
    }

    @Override
    public void append(String path, InputStream content) {
        try {
            byte[] bytes = content.readAllBytes();
            append(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read InputStream for: " + path, e);
        }
    }

    @Override
    public void append(String path, Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            append(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file: " + file, e);
        }
    }

    @Override
    public void delete(String path) {
        zipStore.delete(path);
        flatStore.delete(path);
    }

    // ── CorpusReader (read from ZIP — authoritative) ───────────────────

    @Override
    public Optional<byte[]> read(String path) {
        return zipStore.read(path);
    }

    @Override
    public Optional<InputStream> readStream(String path) {
        return zipStore.readStream(path);
    }

    @Override
    public Optional<byte[]> readVersion(String path, int version) {
        return zipStore.readVersion(path, version);
    }

    @Override
    public List<VersionInfo> versions(String path) {
        return zipStore.versions(path);
    }

    @Override
    public List<String> list() {
        return zipStore.list();
    }

    @Override
    public List<String> list(String prefix) {
        return zipStore.list(prefix);
    }

    @Override
    public boolean exists(String path) {
        return zipStore.exists(path);
    }
}
