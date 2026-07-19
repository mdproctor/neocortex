package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import java.util.List;

public interface CbrCaseMemoryStore {

    void registerSchema(CbrFeatureSchema schema);

    String store(CbrCase cbrCase, String caseType, String entityId, MemoryDomain domain,
                 String tenantId, String caseId, io.casehub.platform.api.path.Path scope);

    <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery query, Class<C> caseType);

    Integer erase(EraseRequest request);

    Integer eraseEntity(String entityId, String tenantId);

    Integer eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId);


    void recordOutcome(String caseId, String tenantId, CbrOutcome outcome);

    Integer purge(CbrRetentionPolicy policy);

    void supersede(String caseId, String tenantId, String supersedingCaseId, String reason);

    void reinstate(String caseId, String tenantId);

    SupersessionStatus getSupersessionStatus(String caseId, String tenantId);

    List<SupersessionStatus> findSupersededCases(String tenantId, MemoryDomain domain);

}
