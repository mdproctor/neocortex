package io.casehub.neocortex.corpus;

import io.smallrye.mutiny.Uni;

public interface ReactiveChangeSource {
    Uni<ChangeSet> changesSince(String cursor);
    Uni<ChangeSet> fullScan();
}
