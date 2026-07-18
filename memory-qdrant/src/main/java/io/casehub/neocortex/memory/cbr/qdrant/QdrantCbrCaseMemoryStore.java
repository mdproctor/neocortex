package io.casehub.neocortex.memory.cbr.qdrant;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class QdrantCbrCaseMemoryStore implements CbrCaseMemoryStore {

    @Inject
    ReactiveQdrantCbrCaseMemoryStore delegate;

    @Override
    public void registerSchema(CbrFeatureSchema schema) {
        delegate.registerSchema(schema).await().indefinitely();
    }

    @Override
    public String store(CbrCase cbrCase, String caseType, String entityId,
                        MemoryDomain domain, String tenantId, String caseId,
                        io.casehub.platform.api.path.Path scope) {
        return delegate.store(cbrCase, caseType, entityId, domain, tenantId, caseId, scope)
                       .await().indefinitely();
    }

    @Override
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
            CbrQuery query, Class<C> caseType) {
        return delegate.retrieveSimilar(query, caseType).await().indefinitely();
    }

    @Override
    public Integer erase(EraseRequest request) {
        return delegate.erase(request).await().indefinitely();
    }

    @Override
    public Integer eraseEntity(String entityId, String tenantId) {
        return delegate.eraseEntity(entityId, tenantId).await().indefinitely();
    }

    @Override
    public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {
        return delegate.eraseByScope(scope, tenantId).await().indefinitely();
    }

    @Override
    public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
        delegate.recordOutcome(caseId, tenantId, outcome).await().indefinitely();
    }

    @Override
    public Integer purge(CbrRetentionPolicy policy) {
        return delegate.purge(policy).await().indefinitely();
    }

    @Override
    public void supersede(String caseId, String tenantId, String supersedingCaseId, String reason) {
        delegate.supersede(caseId, tenantId, supersedingCaseId, reason).await().indefinitely();
    }

    @Override
    public void reinstate(String caseId, String tenantId) {
        delegate.reinstate(caseId, tenantId).await().indefinitely();
    }
}
