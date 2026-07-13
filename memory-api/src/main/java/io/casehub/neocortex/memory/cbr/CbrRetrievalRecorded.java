package io.casehub.neocortex.memory.cbr;

import java.util.List;
import java.util.Objects;

public record CbrRetrievalRecorded(
    String traceId,
    CbrQuery query,
    List<CbrRetrievalTrace.TracedCase> results
) {
    public CbrRetrievalRecorded {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(results, "results");
        results = List.copyOf(results);
    }
}
