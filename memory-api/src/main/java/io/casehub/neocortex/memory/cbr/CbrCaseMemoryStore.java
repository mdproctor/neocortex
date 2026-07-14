package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import java.util.List;

public interface CbrCaseMemoryStore {

    void registerSchema(CbrFeatureSchema schema);

    String store(CbrCase cbrCase, String caseType, String entityId, MemoryDomain domain,
                 String tenantId, String caseId);

    <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery query, Class<C> caseType);

    Integer erase(EraseRequest request);

    Integer eraseEntity(String entityId, String tenantId);

    void recordOutcome(String caseId, String tenantId, CbrOutcome outcome);

    Integer purge(CbrRetentionPolicy policy);


}
