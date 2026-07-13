package io.casehub.neocortex.memory.cbr.tracking;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTrace;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTracker;
import io.casehub.neocortex.memory.cbr.ReactiveCbrRetrievalTracker;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;

@DefaultBean
@ApplicationScoped
public class BlockingToReactiveCbrRetrievalTracker implements ReactiveCbrRetrievalTracker {

    @Inject CbrRetrievalTracker delegate;

    @Override
    public Uni<String> record(CbrQuery query, List<ScoredCbrCase<?>> results) {
        return Uni.createFrom().item(() -> delegate.record(query, results))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<List<CbrRetrievalTrace>> findTraces(String caseType, String tenantId,
                                                    MemoryDomain domain,
                                                    Instant since, Instant until) {
        return Uni.createFrom().item(() -> delegate.findTraces(caseType, tenantId, domain, since, until))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Integer> purgeOlderThan(Instant cutoff) {
        return Uni.createFrom().item(() -> delegate.purgeOlderThan(cutoff))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
