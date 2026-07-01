package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.CorpusStore;
import io.casehub.neocortex.corpus.ReactiveCorpusStore;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Bridges blocking {@link CorpusStore} to reactive {@link ReactiveCorpusStore} by offloading
 * to worker threads. Used when only a blocking implementation is available but reactive API is needed.
 */
public final class BlockingToReactiveCorpusStoreBridge implements ReactiveCorpusStore {

    private final CorpusStore delegate;

    public BlockingToReactiveCorpusStoreBridge(CorpusStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public Uni<Void> append(String path, byte[] content) {
        return Uni.createFrom().<Void>item(() -> {
            delegate.append(path, content);
            return null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> append(String path, InputStream content) {
        return Uni.createFrom().<Void>item(() -> {
            delegate.append(path, content);
            return null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> append(String path, Path file) {
        return Uni.createFrom().<Void>item(() -> {
            delegate.append(path, file);
            return null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> delete(String path) {
        return Uni.createFrom().<Void>item(() -> {
            delegate.delete(path);
            return null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
