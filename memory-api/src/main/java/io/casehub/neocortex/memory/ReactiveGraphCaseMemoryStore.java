package io.casehub.neocortex.memory;

import io.smallrye.mutiny.Uni;
import java.util.List;

public interface ReactiveGraphCaseMemoryStore extends ReactiveCaseMemoryStore {

    default Uni<List<Memory>> graphQuery(GraphMemoryQuery query) {
        return Uni.createFrom().item(List.of());
    }
}
