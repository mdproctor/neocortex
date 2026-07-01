package io.casehub.neocortex.corpus;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

public interface CorpusReader {
    Optional<byte[]> read(String path);
    Optional<InputStream> readStream(String path);
    Optional<byte[]> readVersion(String path, int version);
    List<VersionInfo> versions(String path);
    List<String> list();
    List<String> list(String prefix);
    boolean exists(String path);
}
