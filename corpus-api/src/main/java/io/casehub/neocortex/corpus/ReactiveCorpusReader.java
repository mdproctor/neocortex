package io.casehub.neocortex.corpus;

import io.smallrye.mutiny.Uni;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

public interface ReactiveCorpusReader {
    Uni<Optional<byte[]>> read(String path);
    Uni<Optional<InputStream>> readStream(String path);
    Uni<Optional<byte[]>> readVersion(String path, int version);
    Uni<List<VersionInfo>> versions(String path);
    Uni<List<String>> list();
    Uni<List<String>> list(String prefix);
    Uni<Boolean> exists(String path);
}
