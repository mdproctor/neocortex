package io.casehub.neocortex.memory.cbr.qdrant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.Vector;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds Qdrant {@link PointStruct} from CBR cases.
 *
 * <p>Follows the same pattern as {@code QdrantPointBuilder} in the rag module:
 * deterministic UUID from composite key, named vectors, payload fields.
 */
final class CbrPointBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CbrPointBuilder() {}

    /**
     * Build a Qdrant point from a CBR case.
     *
     * @param cbrCase     the case to index
     * @param caseType    case type discriminator
     * @param entityId    owning entity
     * @param domainName  memory domain name
     * @param tenantId    tenant discriminator
     * @param caseId      unique case identifier
     * @param embedding   optional dense embedding of problem() text (null if no EmbeddingModel)
     * @param denseVectorName name for the dense vector in the named-vectors map
     * @return a Qdrant point ready for upsert
     */
    static PointStruct buildPoint(CbrCase cbrCase, String caseType,
                                   String entityId, String domainName,
                                   String tenantId, String caseId,
                                   Embedding embedding, String denseVectorName) {

        // Deterministic UUID from tenantId + caseType + caseId
        String idInput = tenantId + "#" + caseType + "#" + caseId;
        UUID pointId = UUID.nameUUIDFromBytes(idInput.getBytes(StandardCharsets.UTF_8));

        // Payload
        Map<String, Value> payload = new HashMap<>();
        payload.put("tenantId", ValueFactory.value(tenantId));
        payload.put("caseType", ValueFactory.value(caseType));
        payload.put("entityId", ValueFactory.value(entityId));
        payload.put("domain", ValueFactory.value(domainName));
        payload.put("caseId", ValueFactory.value(caseId));
        payload.put("problem", ValueFactory.value(cbrCase.problem()));
        payload.put("solution", ValueFactory.value(cbrCase.solution()));
        if (cbrCase.outcome() != null) {
            payload.put("outcome", ValueFactory.value(cbrCase.outcome()));
        }
        if (cbrCase.confidence() != null) {
            payload.put("confidence", ValueFactory.value(cbrCase.confidence()));
        }

        Map<String, Object> features = cbrCase.features();
        try {
            payload.put("_features_json", ValueFactory.value(MAPPER.writeValueAsString(features)));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize features", e);
        }
        for (Map.Entry<String, Object> entry : features.entrySet()) {
            String key = "f_" + entry.getKey();
            Object val = entry.getValue();
            if (val instanceof String s) {
                payload.put(key, ValueFactory.value(s));
            } else if (val instanceof Number n) {
                payload.put(key, ValueFactory.value(n.doubleValue()));
            }
        }

        if (cbrCase instanceof PlanCbrCase plan) {
            try {
                payload.put("_plan_trace_json", ValueFactory.value(MAPPER.writeValueAsString(plan.planTrace())));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize plan trace", e);
            }
        }

        payload.put("_cbr_type", ValueFactory.value(cbrCase.cbrType()));

        // Build point — always include a named vector (real or placeholder)
        Map<String, Vector> namedVectors = new HashMap<>();
        if (embedding != null) {
            namedVectors.put(denseVectorName, VectorFactory.vector(embedding.vectorAsList()));
        } else {
            // Placeholder 1-dimensional vector for payload-filter-only mode
            namedVectors.put(denseVectorName, VectorFactory.vector(List.of(0.0f)));
        }

        return PointStruct.newBuilder()
            .setId(PointIdFactory.id(pointId))
            .setVectors(VectorsFactory.namedVectors(namedVectors))
            .putAllPayload(payload)
            .build();
    }

    /**
     * Extract the deterministic point UUID for a case, used for deletion.
     */
    static UUID pointId(String tenantId, String caseType, String caseId) {
        String idInput = tenantId + "#" + caseType + "#" + caseId;
        return UUID.nameUUIDFromBytes(idInput.getBytes(StandardCharsets.UTF_8));
    }
}
