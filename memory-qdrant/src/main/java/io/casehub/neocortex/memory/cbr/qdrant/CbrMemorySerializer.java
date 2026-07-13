package io.casehub.neocortex.memory.cbr.qdrant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.neocortex.memory.MemoryAttributeKeys;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import java.util.HashMap;
import java.util.Map;

/**
 * Serializes {@link CbrCase} instances to {@link MemoryInput} for storage
 * in {@link io.casehub.neocortex.memory.CaseMemoryStore}.
 * <p>
 * Inverse of {@link CbrMemoryDeserializer}.
 */
final class CbrMemorySerializer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private CbrMemorySerializer() {}

    static MemoryInput serialize(CbrCase cbrCase, String entityId,
                                  MemoryDomain domain, String tenantId,
                                  String caseId, String caseType) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put(MemoryAttributeKeys.SOLUTION, cbrCase.solution());
        if (cbrCase.outcome() != null) {
            attributes.put(MemoryAttributeKeys.OUTCOME, cbrCase.outcome());
        }
        if (cbrCase.confidence() != null) {
            attributes.put(MemoryAttributeKeys.CONFIDENCE,
                MemoryAttributeKeys.formatConfidence(cbrCase.confidence()));
        }

        attributes.put(CbrAttributeKeys.CBR_TYPE, cbrCase.cbrType());
        Map<String, Object> features = CbrPointBuilder.toRawMap(cbrCase.features());
        if (!features.isEmpty()) {
            try {
                attributes.put(CbrAttributeKeys.CBR_FEATURES, MAPPER.writeValueAsString(features));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize features to JSON", e);
            }
        }
        if (cbrCase instanceof PlanCbrCase plan) {
            try {
                attributes.put(CbrAttributeKeys.CBR_PLAN_TRACE, MAPPER.writeValueAsString(plan.planTrace()));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize plan trace to JSON", e);
            }
        }
        attributes.put(CbrAttributeKeys.CBR_CASE_TYPE, caseType);

        return new MemoryInput(entityId, domain, tenantId, caseId, cbrCase.problem(), attributes);
    }
}
