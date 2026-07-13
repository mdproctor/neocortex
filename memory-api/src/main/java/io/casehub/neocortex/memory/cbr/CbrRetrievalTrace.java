package io.casehub.neocortex.memory.cbr;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CbrRetrievalTrace(
    String traceId,
    CbrQuery query,
    List<TracedCase> results,
    Instant timestamp
) {
    public CbrRetrievalTrace {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(results, "results");
        results = List.copyOf(results);
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public record TracedCase(
        String caseId,
        double score,
        boolean reranked,
        Map<String, Double> featureSimilarities,
        Double confidence
    ) {
        public TracedCase {
            featureSimilarities = featureSimilarities != null
                ? Map.copyOf(featureSimilarities) : Map.of();
        }
    }
}
