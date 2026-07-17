package io.casehub.neocortex.memory.cbr.tracking;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.CbrRetrievalRecorded;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTrace;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTracker;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.function.Consumer;

@Decorator
@Priority(50)
@IfBuildProperty(name = "casehub.cbr.tracking.enabled", stringValue = "true")
public class TrackingCbrCaseMemoryStore implements CbrCaseMemoryStore {

    private static final Logger LOG = Logger.getLogger(TrackingCbrCaseMemoryStore.class);

    private final CbrCaseMemoryStore delegate;
    private final CbrRetrievalTracker tracker;
    private final Consumer<CbrRetrievalRecorded> eventSink;

    @Inject
    TrackingCbrCaseMemoryStore(@Delegate @Any CbrCaseMemoryStore delegate,
                                CbrRetrievalTracker tracker,
                                Event<CbrRetrievalRecorded> recordedEvent) {
        this(delegate, tracker, recordedEvent::fire);
    }

    TrackingCbrCaseMemoryStore(CbrCaseMemoryStore delegate,
                                CbrRetrievalTracker tracker,
                                Consumer<CbrRetrievalRecorded> eventSink) {
        this.delegate = delegate;
        this.tracker = tracker;
        this.eventSink = eventSink;
    }

    @Override
    public void registerSchema(CbrFeatureSchema schema) {
        delegate.registerSchema(schema);
    }

    @Override
    public String store(CbrCase cbrCase, String caseType, String entityId,
                        MemoryDomain domain, String tenantId, String caseId, io.casehub.platform.api.path.Path scope) {
        return delegate.store(cbrCase, caseType, entityId, domain, tenantId, caseId, scope);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
            CbrQuery query, Class<C> caseClass) {
        List<ScoredCbrCase<C>> results = delegate.retrieveSimilar(query, caseClass);
        try {
            String traceId = tracker.record(query, (List<ScoredCbrCase<?>>) (List<?>) results);
            var traced = results.stream()
                    .map(s -> new CbrRetrievalTrace.TracedCase(
                            s.caseId(), s.score(), s.reranked(),
                            s.featureSimilarities(), s.cbrCase().confidence()))
                    .toList();
            eventSink.accept(new CbrRetrievalRecorded(traceId, query, traced));
        } catch (Exception e) {
            LOG.warn("CBR retrieval tracking failed — returning results unchanged", e);
        }
        return results;
    }

    @Override
    public Integer erase(EraseRequest request) {
        return delegate.erase(request);
    }

    @Override
    public Integer eraseEntity(String entityId, String tenantId) {
        return delegate.eraseEntity(entityId, tenantId);
    }

    @Override
    public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {return delegate.eraseByScope(scope, tenantId);}


    @Override
    public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
        delegate.recordOutcome(caseId, tenantId, outcome);
    }

    @Override
    public Integer purge(CbrRetentionPolicy policy) {
        return delegate.purge(policy);
    }

    @Override
    public void supersede(String caseId, String tenantId, String supersedingCaseId, String reason) {
        delegate.supersede(caseId, tenantId, supersedingCaseId, reason);
    }

    @Override
    public void reinstate(String caseId, String tenantId) {
        delegate.reinstate(caseId, tenantId);
    }

}
