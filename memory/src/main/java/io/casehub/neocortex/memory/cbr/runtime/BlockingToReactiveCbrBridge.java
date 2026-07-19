package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.BridgedCbrStore;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.SupersessionStatus;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.ReactiveCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.SupersessionStatus;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@DefaultBean
@ApplicationScoped
public class BlockingToReactiveCbrBridge implements ReactiveCbrCaseMemoryStore, BridgedCbrStore {

    @Inject
    CbrCaseMemoryStore delegate;

    @Override
    public Uni<Void> registerSchema(CbrFeatureSchema schema) {
        return Uni.createFrom().voidItem()
                  .invoke(() -> delegate.registerSchema(schema))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<String> store(CbrCase cbrCase, String caseType, String entityId, MemoryDomain domain,
                             String tenantId, String caseId, io.casehub.platform.api.path.Path scope) {
        return Uni.createFrom().item(() -> delegate.store(cbrCase, caseType, entityId, domain, tenantId, caseId, scope))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(CbrQuery query, Class<C> caseClass) {
        return Uni.createFrom().item(() -> delegate.retrieveSimilar(query, caseClass))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Integer> erase(EraseRequest request) {
        return Uni.createFrom().item(() -> delegate.erase(request))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Integer> eraseEntity(String entityId, String tenantId) {
        return Uni.createFrom().item(() -> delegate.eraseEntity(entityId, tenantId))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Integer> eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {
        return Uni.createFrom().item(() -> delegate.eraseByScope(scope, tenantId))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }


    @Override
    public Uni<Void> recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
        return Uni.createFrom().voidItem()
                  .invoke(() -> delegate.recordOutcome(caseId, tenantId, outcome))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Integer> purge(CbrRetentionPolicy policy) {
        return Uni.createFrom().item(() -> delegate.purge(policy))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());}

    @Override
    public Uni<Void> supersede(String caseId, String tenantId, String supersedingCaseId, String reason) {
        return Uni.createFrom().voidItem()
                  .invoke(() -> delegate.supersede(caseId, tenantId, supersedingCaseId, reason))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> reinstate(String caseId, String tenantId) {
        return Uni.createFrom().voidItem()
                  .invoke(() -> delegate.reinstate(caseId, tenantId))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }


    @Override
    public io.smallrye.mutiny.Uni<SupersessionStatus> getSupersessionStatus(String caseId, String tenantId) {
        return io.smallrye.mutiny.Uni.createFrom().item(() -> delegate.getSupersessionStatus(caseId, tenantId))
                .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public io.smallrye.mutiny.Uni<java.util.List<SupersessionStatus>> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) {
        return io.smallrye.mutiny.Uni.createFrom().item(() -> delegate.findSupersededCases(tenantId, domain))
                .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

}
