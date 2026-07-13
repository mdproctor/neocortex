package io.casehub.neocortex.memory.cbr.testing;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTrace;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTracker;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryCbrRetrievalTracker implements CbrRetrievalTracker {

    private final List<CbrRetrievalTrace> traces = new CopyOnWriteArrayList<>();

    @Override
    public String record(CbrQuery query, List<ScoredCbrCase<?>> results) {
        String traceId = UUID.randomUUID().toString();
        List<CbrRetrievalTrace.TracedCase> traced = results.stream()
                .map(s -> new CbrRetrievalTrace.TracedCase(
                        s.caseId(), s.score(), s.reranked(),
                        s.featureSimilarities(),
                        s.cbrCase().confidence()))
                .toList();
        traces.add(new CbrRetrievalTrace(traceId, query, traced, Instant.now()));
        return traceId;
    }

    @Override
    public List<CbrRetrievalTrace> findTraces(String caseType, String tenantId,
                                               MemoryDomain domain,
                                               Instant since, Instant until) {
        return traces.stream()
                .filter(t -> t.query().caseType().equals(caseType))
                .filter(t -> t.query().tenantId().equals(tenantId))
                .filter(t -> t.query().domain().equals(domain))
                .filter(t -> !t.timestamp().isBefore(since) && t.timestamp().isBefore(until))
                .sorted(Comparator.comparing(CbrRetrievalTrace::timestamp))
                .toList();
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
        int before = traces.size();
        traces.removeIf(t -> t.timestamp().isBefore(cutoff));
        return before - traces.size();
    }
}
