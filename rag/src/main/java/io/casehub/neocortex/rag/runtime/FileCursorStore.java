package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.rag.CursorStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@DefaultBean
@ApplicationScoped
public class FileCursorStore implements CursorStore {

    private final Path baseDir;

    @Inject
    FileCursorStore(IngestionConfig config) {
        this(config.cursorDir());
    }

    FileCursorStore(String cursorDir) {
        this.baseDir = Path.of(cursorDir);
    }

    @Override
    public Optional<String> load(String corpusName) {
        Path file = baseDir.resolve(corpusName + ".cursor");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file).trim();
            return content.isEmpty() ? Optional.empty() : Optional.of(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read cursor for " + corpusName, e);
        }
    }

    @Override
    public void save(String corpusName, String cursor) {
        try {
            Files.createDirectories(baseDir);
            Path file = baseDir.resolve(corpusName + ".cursor");
            Path tmp = baseDir.resolve(corpusName + ".cursor.tmp");
            Files.writeString(tmp, cursor);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save cursor for " + corpusName, e);
        }
    }

    @Override
    public void delete(String corpusName) {
        Path file = baseDir.resolve(corpusName + ".cursor");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete cursor for " + corpusName, e);
        }
    }
}
