package io.casehub.neocortex.knowledge;

import java.util.List;
import java.util.Objects;

public record PipelinePage<T>(List<T> items, String nextCursor, boolean hasMore) {
    public PipelinePage {
        Objects.requireNonNull(items);
        items = List.copyOf(items);
    }
}
