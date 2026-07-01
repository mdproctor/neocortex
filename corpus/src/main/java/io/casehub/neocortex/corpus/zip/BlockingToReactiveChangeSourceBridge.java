package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.ChangeSet;
import io.casehub.neocortex.corpus.ChangeSource;
import io.casehub.neocortex.corpus.ReactiveChangeSource;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/**
 * Bridges blocking {@link ChangeSource} to reactive {@link ReactiveChangeSource} by offloading
 * to worker threads. Used when only a blocking implementation is available but reactive API is needed.
 */
public final class BlockingToReactiveChangeSourceBridge implements ReactiveChangeSource {

    private final ChangeSource delegate;

    public BlockingToReactiveChangeSourceBridge(ChangeSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Uni<ChangeSet> changesSince(String cursor) {
        return Uni.createFrom().item(() -> delegate.changesSince(cursor))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<ChangeSet> fullScan() {
        return Uni.createFrom().item(() -> delegate.fullScan())
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
