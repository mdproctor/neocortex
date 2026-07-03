package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.cbr.*;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
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
}
