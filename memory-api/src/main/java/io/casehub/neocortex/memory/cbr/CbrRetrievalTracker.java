package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.MemoryDomain;

import java.time.Instant;
import java.util.List;

public interface CbrRetrievalTracker {
    String record(CbrQuery query, List<ScoredCbrCase<?>> results);

    List<CbrRetrievalTrace> findTraces(String caseType, String tenantId,
                                        MemoryDomain domain,
                                        Instant since, Instant until);

    int purgeOlderThan(Instant cutoff);
}
