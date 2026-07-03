package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.smallrye.mutiny.Uni;
import java.util.List;

public interface ReactiveCbrCaseMemoryStore {

    Uni<Void> registerSchema(CbrFeatureSchema schema);

    Uni<String> store(CbrCase cbrCase, String caseType, String entityId, MemoryDomain domain,
                      String tenantId, String caseId);

    <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(CbrQuery query, Class<C> caseType);

    Uni<Integer> erase(EraseRequest request);

    Uni<Integer> eraseEntity(String entityId, String tenantId);
}
