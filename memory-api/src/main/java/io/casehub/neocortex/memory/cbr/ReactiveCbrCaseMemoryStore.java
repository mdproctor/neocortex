package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.smallrye.mutiny.Uni;
import java.util.List;

public interface ReactiveCbrCaseMemoryStore {

    Uni<Void> registerSchema(CbrFeatureSchema schema);

    Uni<String> store(CbrCase cbrCase, String caseType, String entityId, MemoryDomain domain,
                      String tenantId, String caseId, io.casehub.platform.api.path.Path scope);

    <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(CbrQuery query, Class<C> caseType);

    Uni<Integer> erase(EraseRequest request);

    Uni<Integer> eraseEntity(String entityId, String tenantId);

    Uni<Integer> eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId);


    Uni<Void> recordOutcome(String caseId, String tenantId, CbrOutcome outcome);

    Uni<Integer> purge(CbrRetentionPolicy policy);

    Uni<Void> supersede(String caseId, String tenantId, String supersedingCaseId, String reason);

    Uni<Void> reinstate(String caseId, String tenantId);
}
