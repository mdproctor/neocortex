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
import java.time.Instant;
import java.util.ArrayList;
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

    static PointStruct buildPoint(CbrCase cbrCase, String caseType,
                                  String entityId, String domainName,
                                  String tenantId, String caseId,
                                  Embedding embedding, String denseVectorName) {
        return buildPoint(cbrCase, caseType, entityId, domainName, tenantId, caseId,
                          embedding, denseVectorName, null, null, null, null, null);
    }

    static PointStruct buildPoint(CbrCase cbrCase, String caseType,
                                  String entityId, String domainName,
                                  String tenantId, String caseId,
                                  Embedding embedding, String denseVectorName,
                                  Map<Integer, Float> sparseEmbedding, String sparseVectorName,
                                  String bm25Text, String bm25VectorName, String bm25Model) {

        // Deterministic UUID from tenantId + caseType + caseId
        String idInput = tenantId + "#" + caseType + "#" + caseId;
        UUID   pointId = UUID.nameUUIDFromBytes(idInput.getBytes(StandardCharsets.UTF_8));

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

        Map<String, Object> features = toRawMap(cbrCase.features());
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
            } else if (val instanceof List<?> list) {
                payload.put(key, toListValue(list));
            } else if (val instanceof Map<?,?> map) {
                payload.put(key, toStructValue(map));
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
        payload.put("_stored_at", ValueFactory.value(Instant.now().toEpochMilli()));

        // Build named vectors
        Map<String, Vector> namedVectors = new HashMap<>();
        if (embedding != null) {
            namedVectors.put(denseVectorName, VectorFactory.vector(embedding.vectorAsList()));
        } else {
            namedVectors.put(denseVectorName, VectorFactory.vector(List.of(0.0f)));
        }

        if (sparseEmbedding != null && sparseVectorName != null) {
            List<Float>   sparseValues  = new ArrayList<>(sparseEmbedding.size());
            List<Integer> sparseIndices = new ArrayList<>(sparseEmbedding.size());
            for (Map.Entry<Integer, Float> entry : sparseEmbedding.entrySet()) {
                sparseIndices.add(entry.getKey());
                sparseValues.add(entry.getValue());
            }
            namedVectors.put(sparseVectorName, VectorFactory.vector(sparseValues, sparseIndices));
        }

        if (bm25Text != null && bm25VectorName != null) {
            namedVectors.put(bm25VectorName, VectorFactory.vector(
                    io.qdrant.client.grpc.Points.Document.newBuilder()
                                                         .setText(bm25Text)
                                                         .setModel(bm25Model)
                                                         .build()));
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


    static Map<String, Object> toRawMap(Map<String, io.casehub.neocortex.memory.cbr.FeatureValue> features) {
        var raw = new java.util.HashMap<String, Object>();
        for (var entry : features.entrySet()) {
            raw.put(entry.getKey(), toRawValue(entry.getValue()));
        }
        return raw;
    }

    static Map<String, io.casehub.neocortex.memory.cbr.FeatureValue> fromRawMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {return Map.of();}
        var typed = new java.util.HashMap<String, io.casehub.neocortex.memory.cbr.FeatureValue>();
        for (var entry : raw.entrySet()) {
            typed.put(entry.getKey(), fromRawValue(entry.getValue()));
        }
        return Map.copyOf(typed);
    }

    @SuppressWarnings("unchecked")
    private static io.casehub.neocortex.memory.cbr.FeatureValue fromRawValue(Object raw) {
        if (raw instanceof String s) {return io.casehub.neocortex.memory.cbr.FeatureValue.string(s);}
        if (raw instanceof Number n) {return io.casehub.neocortex.memory.cbr.FeatureValue.number(n.doubleValue());}
        if (raw instanceof List<?> list) {
            if (list.isEmpty()) {return io.casehub.neocortex.memory.cbr.FeatureValue.stringList();}
            Object first = list.getFirst();
            if (first instanceof String) {
                return io.casehub.neocortex.memory.cbr.FeatureValue.stringList(list.stream().map(e -> (String) e).toList());
            } else if (first instanceof Number) {
                return io.casehub.neocortex.memory.cbr.FeatureValue.numberList(list.stream().map(e -> ((Number) e).doubleValue()).toList());
            } else if (first instanceof Map<?, ?>) {
                var items = list.stream().map(e -> fromRawMap((Map<String, Object>) e)).toList();
                return io.casehub.neocortex.memory.cbr.FeatureValue.structList(items);
            }
            throw new IllegalArgumentException("Unsupported list element: " + first.getClass());
        }
        if (raw instanceof Map<?, ?> map) {
            var fields = new java.util.HashMap<String, io.casehub.neocortex.memory.cbr.FeatureValue>();
            map.forEach((k, v) -> fields.put(String.valueOf(k), fromRawValue(v)));
            return io.casehub.neocortex.memory.cbr.FeatureValue.struct(fields);
        }
        throw new IllegalArgumentException("Unsupported raw value: " + raw.getClass());
    }


    private static Object toRawValue(io.casehub.neocortex.memory.cbr.FeatureValue fv) {
        return switch (fv) {
            case io.casehub.neocortex.memory.cbr.FeatureValue.StringVal s -> s.value();
            case io.casehub.neocortex.memory.cbr.FeatureValue.NumberVal n -> n.value();
            case io.casehub.neocortex.memory.cbr.FeatureValue.RangeVal r -> Map.of("min", r.min(), "max", r.max());
            case io.casehub.neocortex.memory.cbr.FeatureValue.StringListVal sl -> sl.values();
            case io.casehub.neocortex.memory.cbr.FeatureValue.NumberListVal nl -> nl.values();
            case io.casehub.neocortex.memory.cbr.FeatureValue.StructVal sv -> {
                var m = new java.util.HashMap<String, Object>();
                sv.fields().forEach((k, v) -> m.put(k, toRawValue(v)));
                yield m;
            }
            case io.casehub.neocortex.memory.cbr.FeatureValue.StructListVal sl -> sl.items().stream()
                                                                                    .map(item -> {
                                                                                        var m = new java.util.HashMap<String, Object>();
                                                                                        item.forEach((k, v) -> m.put(k, toRawValue(v)));
                                                                                        return (Object) m;
                                                                                    }).toList();
        };
    }

    private static Value toListValue(List<?> list) {
        List<Value> values = new ArrayList<>(list.size());
        for (Object elem : list) {
            if (elem instanceof String s) {values.add(ValueFactory.value(s));} else if (elem instanceof Number n) {
                values.add(ValueFactory.value(n.doubleValue()));
            } else if (elem instanceof Map<?, ?> map) {values.add(toStructValue(map));} else {
                throw new IllegalArgumentException("Unsupported list element type: " + elem.getClass());
            }
        }
        return ValueFactory.list(values);
    }

    private static Value toStructValue(Map<?, ?> map) {
        Map<String, Value> struct = new HashMap<>();
        for (var entry : map.entrySet()) {
            String k = String.valueOf(entry.getKey());
            Object v = entry.getValue();
            if (v instanceof String s) {struct.put(k, ValueFactory.value(s));} else if (v instanceof Number n) {
                struct.put(k, ValueFactory.value(n.doubleValue()));
            } else if (v instanceof List<?> list) {
                struct.put(k, toListValue(list));
            } else if (v instanceof Map<?, ?> m) {
                struct.put(k, toStructValue(m));
            } else {
                throw new IllegalArgumentException("Unsupported value type: " + v.getClass());
            }
        }
        return ValueFactory.value(struct);
    }

}
