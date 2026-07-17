package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrCasesErased;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.platform.api.path.Path;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Decorator
@Priority(45)
public class ErasureNotificationCbrCaseMemoryStore implements CbrCaseMemoryStore {

    private final CbrCaseMemoryStore delegate;
    private final Event<CbrCasesErased.ByRequest> byRequestEvent;
    private final Event<CbrCasesErased.ByEntity> byEntityEvent;
    private final Event<CbrCasesErased.ByScope> byScopeEvent;
    private final Clock clock;

    @Inject
    public ErasureNotificationCbrCaseMemoryStore(
            @Delegate @Any CbrCaseMemoryStore delegate,
            Event<CbrCasesErased.ByRequest> byRequestEvent,
            Event<CbrCasesErased.ByEntity> byEntityEvent,
            Event<CbrCasesErased.ByScope> byScopeEvent) {
        this(delegate, byRequestEvent, byEntityEvent, byScopeEvent, Clock.systemUTC());
    }

    ErasureNotificationCbrCaseMemoryStore(
            CbrCaseMemoryStore delegate,
            Event<CbrCasesErased.ByRequest> byRequestEvent,
            Event<CbrCasesErased.ByEntity> byEntityEvent,
            Event<CbrCasesErased.ByScope> byScopeEvent,
            Clock clock) {
        this.delegate = delegate;
        this.byRequestEvent = byRequestEvent;
        this.byEntityEvent = byEntityEvent;
        this.byScopeEvent = byScopeEvent;
        this.clock = clock;
    }

    @Override
    public Integer erase(EraseRequest request) {
        int count = delegate.erase(request);
        if (count > 0) {
            byRequestEvent.fire(new CbrCasesErased.ByRequest(
                    request.tenantId(), count, request.entityId(),
                    request.domain(), request.caseId(),
                    Instant.now(clock)));
        }
        return count;
    }

    @Override
    public Integer eraseEntity(String entityId, String tenantId) {
        int count = delegate.eraseEntity(entityId, tenantId);
        if (count > 0) {
            byEntityEvent.fire(new CbrCasesErased.ByEntity(
                    tenantId, count, entityId, Instant.now(clock)));
        }
        return count;
    }

    @Override
    public Integer eraseByScope(Path scope, String tenantId) {
        int count = delegate.eraseByScope(scope, tenantId);
        if (count > 0) {
            byScopeEvent.fire(new CbrCasesErased.ByScope(
                    tenantId, count, scope, Instant.now(clock)));
        }
        return count;
    }

    @Override public void registerSchema(CbrFeatureSchema schema) { delegate.registerSchema(schema); }
    @Override public String store(CbrCase c, String ct, String e, MemoryDomain d, String t, String ci, Path scope) { return delegate.store(c, ct, e, d, t, ci, scope); }
    @Override public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery q, Class<C> ct) { return delegate.retrieveSimilar(q, ct); }
    @Override public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) { delegate.recordOutcome(caseId, tenantId, outcome); }
    @Override public Integer purge(CbrRetentionPolicy policy) { return delegate.purge(policy); }
    @Override public void supersede(String caseId, String tenantId, String supersedingCaseId, String reason) { delegate.supersede(caseId, tenantId, supersedingCaseId, reason); }
    @Override public void reinstate(String caseId, String tenantId) { delegate.reinstate(caseId, tenantId); }
}
