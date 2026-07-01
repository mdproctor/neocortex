package io.casehub.neocortex.corpus;

import io.smallrye.mutiny.Uni;

import java.io.InputStream;
import java.nio.file.Path;

public interface ReactiveCorpusStore {
    Uni<Void> append(String path, byte[] content);
    Uni<Void> append(String path, InputStream content);
    Uni<Void> append(String path, Path file);
    Uni<Void> delete(String path);
}
