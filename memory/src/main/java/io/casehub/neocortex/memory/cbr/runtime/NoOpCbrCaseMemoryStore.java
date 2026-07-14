package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@DefaultBean
@ApplicationScoped
public class NoOpCbrCaseMemoryStore implements CbrCaseMemoryStore {

    @Override
    public void registerSchema(CbrFeatureSchema schema) {}

    @Override
    public String store(CbrCase cbrCase, String caseType, String entityId, MemoryDomain domain,
                        String tenantId, String caseId) {
        return "";
    }

    @Override
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery query, Class<C> caseClass) {
        return List.of();
    }

    @Override
    public Integer erase(EraseRequest request) {
        return 0;
    }

    @Override
    public Integer eraseEntity(String entityId, String tenantId) {
        return 0;
    }

    @Override
    public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {}

    @Override
    public Integer purge(CbrRetentionPolicy policy) {
        return 0;
    }

}
