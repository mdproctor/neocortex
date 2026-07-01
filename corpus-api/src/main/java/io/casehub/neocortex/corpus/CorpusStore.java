package io.casehub.neocortex.corpus;

import java.io.InputStream;
import java.nio.file.Path;

public interface CorpusStore {
    void append(String path, byte[] content);
    void append(String path, InputStream content);
    void append(String path, Path file);
    void delete(String path);
}
