package io.casehub.corpus.zip;

import io.casehub.corpus.CorpusReader;
import io.casehub.corpus.ReactiveCorpusReader;
import io.casehub.corpus.VersionInfo;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Bridges blocking {@link CorpusReader} to reactive {@link ReactiveCorpusReader} by offloading
 * to worker threads. Used when only a blocking implementation is available but reactive API is needed.
 */
public final class BlockingToReactiveCorpusReaderBridge implements ReactiveCorpusReader {

    private final CorpusReader delegate;

    public BlockingToReactiveCorpusReaderBridge(CorpusReader delegate) {
        this.delegate = delegate;
    }

    @Override
    public Uni<Optional<byte[]>> read(String path) {
        return Uni.createFrom().item(() -> delegate.read(path))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Optional<InputStream>> readStream(String path) {
        return Uni.createFrom().item(() -> delegate.readStream(path))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Optional<byte[]>> readVersion(String path, int version) {
        return Uni.createFrom().item(() -> delegate.readVersion(path, version))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<List<VersionInfo>> versions(String path) {
        return Uni.createFrom().item(() -> delegate.versions(path))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<List<String>> list() {
        return Uni.createFrom().item(() -> delegate.list())
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<List<String>> list(String prefix) {
        return Uni.createFrom().item(() -> delegate.list(prefix))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Boolean> exists(String path) {
        return Uni.createFrom().item(() -> delegate.exists(path))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
