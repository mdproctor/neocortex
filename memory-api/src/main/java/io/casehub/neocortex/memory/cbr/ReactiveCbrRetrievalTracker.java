package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import io.smallrye.mutiny.Uni;

import java.time.Instant;
import java.util.List;

public interface ReactiveCbrRetrievalTracker {
    Uni<String> record(CbrQuery query, List<ScoredCbrCase<?>> results);

    Uni<List<CbrRetrievalTrace>> findTraces(String caseType, String tenantId,
                                             MemoryDomain domain,
                                             Instant since, Instant until);

    Uni<Integer> purgeOlderThan(Instant cutoff);
}
