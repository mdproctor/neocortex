package io.casehub.neocortex.memory.cbr.tracking;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.BridgedCbrStore;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.CbrRetrievalRecorded;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTrace;
import io.casehub.neocortex.memory.cbr.ReactiveCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.ReactiveCbrRetrievalTracker;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

@Decorator
@Priority(50)
@IfBuildProperty(name = "casehub.cbr.tracking.enabled", stringValue = "true")
public class ReactiveTrackingCbrCaseMemoryStore implements ReactiveCbrCaseMemoryStore {

    private static final Logger LOG = Logger.getLogger(ReactiveTrackingCbrCaseMemoryStore.class);

    private final ReactiveCbrCaseMemoryStore delegate;
    private final ReactiveCbrRetrievalTracker tracker;
    private final Event<CbrRetrievalRecorded> recordedEvent;
    private final boolean bridgeActive;

    @Inject
    ReactiveTrackingCbrCaseMemoryStore(@Delegate @Any ReactiveCbrCaseMemoryStore delegate,
                                       ReactiveCbrRetrievalTracker tracker,
                                       Event<CbrRetrievalRecorded> recordedEvent) {
        this.delegate = delegate;
        this.tracker = tracker;
        this.recordedEvent = recordedEvent;
        this.bridgeActive = delegate instanceof BridgedCbrStore;
    }

    @Override
    public Uni<Void> registerSchema(CbrFeatureSchema schema) {
        return delegate.registerSchema(schema);
    }

    @Override
    public Uni<String> store(CbrCase cbrCase, String caseType, String entityId,
                             MemoryDomain domain, String tenantId, String caseId, io.casehub.platform.api.path.Path scope) {
        return delegate.store(cbrCase, caseType, entityId, domain, tenantId, caseId, scope);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(
            CbrQuery query, Class<C> caseClass) {
        if (bridgeActive) {
            return delegate.retrieveSimilar(query, caseClass);
        }
        return delegate.retrieveSimilar(query, caseClass)
                .chain(results -> tracker.record(query, (List<ScoredCbrCase<?>>) (List<?>) results)
                        .onItem().invoke(traceId -> {
                            var traced = results.stream()
                                    .map(s -> new CbrRetrievalTrace.TracedCase(
                                            s.caseId(), s.score(), s.reranked(),
                                            s.featureSimilarities(), s.cbrCase().confidence()))
                                    .toList();
                            recordedEvent.fireAsync(new CbrRetrievalRecorded(traceId, query, traced));
                        })
                        .onFailure().recoverWithItem(e -> {
                            LOG.warn("CBR retrieval tracking failed — returning results unchanged", e);
                            return null;
                        })
                        .replaceWith(results));
    }

    @Override
    public Uni<Integer> erase(EraseRequest request) {
        return delegate.erase(request);
    }

    @Override
    public Uni<Integer> eraseEntity(String entityId, String tenantId) {
        return delegate.eraseEntity(entityId, tenantId);
    }

    @Override
    public Uni<Integer> eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {return delegate.eraseByScope(scope, tenantId);}


    @Override
    public Uni<Void> recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
        return delegate.recordOutcome(caseId, tenantId, outcome);
    }

    @Override
    public Uni<Integer> purge(CbrRetentionPolicy policy) {
        return delegate.purge(policy);
    }

    @Override
    public Uni<Void> supersede(String caseId, String tenantId, String supersedingCaseId, String reason) {
        return delegate.supersede(caseId, tenantId, supersedingCaseId, reason);
    }

    @Override
    public Uni<Void> reinstate(String caseId, String tenantId) {
        return delegate.reinstate(caseId, tenantId);
    }


    @Override
    public io.smallrye.mutiny.Uni<io.casehub.neocortex.memory.cbr.SupersessionStatus> getSupersessionStatus(String caseId, String tenantId) {
        return delegate.getSupersessionStatus(caseId, tenantId);
    }

    @Override
    public io.smallrye.mutiny.Uni<java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus>> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) {
        return delegate.findSupersededCases(tenantId, domain);
    }

}
