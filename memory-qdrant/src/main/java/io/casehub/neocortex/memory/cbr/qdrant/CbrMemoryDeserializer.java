package io.casehub.neocortex.memory.cbr.qdrant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryAttributeKeys;
import io.casehub.neocortex.memory.cbr.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Deserializes {@link Memory} instances back to {@link CbrCase} instances.
 * <p>
 * Inverse of {@link CbrMemorySerializer}.
 * <p>
 * Returns {@link Optional#empty()} on any deserialization failure (unknown cbrType,
 * malformed JSON, missing required attributes). Never throws.
 */
final class CbrMemoryDeserializer {
    private static final Logger LOG = Logger.getLogger(CbrMemoryDeserializer.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<PlanTrace>> PLAN_TRACE_TYPE = new TypeReference<>() {};

    private CbrMemoryDeserializer() {}

    static Optional<CbrCase> deserialize(Memory memory) {
        try {
            String problem = memory.text();
            Map<String, String> attrs = memory.attributes();

            String solution = attrs.get(MemoryAttributeKeys.SOLUTION);
            if (solution == null) {
                LOG.warning("Missing solution attribute in memory " + memory.memoryId());
                return Optional.empty();
            }

            String outcome = attrs.get(MemoryAttributeKeys.OUTCOME);
            Double confidence = attrs.containsKey(MemoryAttributeKeys.CONFIDENCE)
                ? MemoryAttributeKeys.parseConfidence(attrs.get(MemoryAttributeKeys.CONFIDENCE))
                : null;

            String cbrType = attrs.get(CbrAttributeKeys.CBR_TYPE);
            if (cbrType == null) {
                LOG.warning("Missing cbr.type attribute in memory " + memory.memoryId());
                return Optional.empty();
            }

            CbrCase result = switch (cbrType) {
                case FeatureVectorCbrCase.CBR_TYPE -> {
                    Map<String, Object> features = parseFeatures(attrs);
                    yield new FeatureVectorCbrCase(problem, solution, outcome, confidence, features);
                }
                case PlanCbrCase.CBR_TYPE -> {
                    Map<String, Object> features = parseFeatures(attrs);
                    List<PlanTrace> planTrace = parsePlanTrace(attrs);
                    yield new PlanCbrCase(problem, solution, outcome, confidence, features, planTrace);
                }
                case TextualCbrCase.CBR_TYPE ->
                    new TextualCbrCase(problem, solution, outcome, confidence);
                default -> {
                    LOG.warning("Unknown cbr.type '" + cbrType + "' in memory " + memory.memoryId());
                    yield null;
                }
            };

            if (result == null) {
                return Optional.empty();
            }
            return Optional.of(result);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to deserialize memory " + memory.memoryId(), e);
            return Optional.empty();
        }
    }

    private static Map<String, Object> parseFeatures(Map<String, String> attrs) {
        String json = attrs.get(CbrAttributeKeys.CBR_FEATURES);
        if (json == null) return Map.of();
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Malformed cbr.features JSON", e);
        }
    }

    private static List<PlanTrace> parsePlanTrace(Map<String, String> attrs) {
        String json = attrs.get(CbrAttributeKeys.CBR_PLAN_TRACE);
        if (json == null) return List.of();
        try {
            return MAPPER.readValue(json, PLAN_TRACE_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Malformed cbr.planTrace JSON", e);
        }
    }
}
