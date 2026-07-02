package io.casehub.neocortex.memory.cbr.qdrant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.neocortex.memory.cbr.*;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryAttributeKeys;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Qdrant-backed {@link CbrCaseMemoryStore}.
 *
 * <p>Approach 3: categorical features become keyword payload indexes,
 * numeric features become float payload indexes, problem() text becomes
 * a dense vector (when an {@link EmbeddingModel} is available).
 *
 * <p>The {@link EmbeddingModel} is optional. Without it, the store operates
 * in payload-filter-only mode — no dense vector search, but categorical
 * and numeric filtering still works.
 *
 * <p>Delegates durable memory storage to an injected {@link CaseMemoryStore}
 * when present. When absent, operates in Qdrant-only mode (for tests without platform).
 */
public class QdrantCbrCaseMemoryStore implements CbrCaseMemoryStore {

    private static final Logger LOG = Logger.getLogger(QdrantCbrCaseMemoryStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<PlanTrace>> PLAN_TRACE_TYPE = new TypeReference<>() {};

    private final CbrCollectionManager collectionManager;
    private final EmbeddingModel embeddingModel; // nullable
    private final QdrantCbrConfig config;
    private final CaseMemoryStore delegate; // nullable — when present, delegate durable storage
    private final Map<String, CbrFeatureSchema> schemas = new ConcurrentHashMap<>();

    QdrantCbrCaseMemoryStore(CbrCollectionManager collectionManager,
                              EmbeddingModel embeddingModel,
                              QdrantCbrConfig config,
                              CaseMemoryStore delegate) {
        this.collectionManager = collectionManager;
        this.embeddingModel = embeddingModel;
        this.config = config;
        this.delegate = delegate;
    }

    @Override
    public void registerSchema(CbrFeatureSchema schema) {
        schemas.put(schema.caseType(), schema);
        collectionManager.registerSchemaIndexes(schema, vectorDimension());
    }

    @Override
    public String store(CbrCase cbrCase, String caseType, String entityId,
                        MemoryDomain domain, String tenantId, String caseId) {
        // Delegate durable storage to CaseMemoryStore if present
        String memoryId = caseId;
        if (delegate != null) {
            MemoryInput memoryInput = serializeToMemoryInput(cbrCase, entityId, domain, tenantId, caseId, caseType);
            memoryId = delegate.store(memoryInput);
        }

        collectionManager.ensureCollection(caseType, vectorDimension());

        // Embed problem() text if embedding model is available
        Embedding embedding = null;
        if (embeddingModel != null) {
            embedding = embeddingModel.embed(TextSegment.from(cbrCase.problem())).content();
        }

        PointStruct point = CbrPointBuilder.buildPoint(
            cbrCase, caseType, entityId, domain.name(), tenantId, caseId,
            embedding, config.denseVectorName());

        String collection = collectionManager.collectionName(caseType);

        // Retry upsert up to maxRetries times
        int maxRetries = config.maxRetries();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                collectionManager.client().upsertAsync(collection, List.of(point)).get();
                return memoryId;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during upsert", e);
            } catch (ExecutionException e) {
                if (attempt == maxRetries) {
                    throw new RuntimeException("Upsert failed after " + maxRetries + " attempts", e.getCause());
                }
                LOG.log(Level.WARNING, "Upsert attempt " + attempt + " failed, retrying", e.getCause());
            }
        }
        // unreachable
        throw new IllegalStateException("Upsert loop exited without result");
    }

    /**
     * Serialize CbrCase to MemoryInput for delegation to CaseMemoryStore.
     * Mapping per spec §3.4:
     * - problem() → text
     * - solution() → attributes["solution"]
     * - outcome() → attributes["outcome"]
     * - confidence() → attributes["confidence"] (formatted)
     * - features → attributes["cbr.features"] (JSON)
     * - discriminator → attributes["cbr.type"]
     */
    private MemoryInput serializeToMemoryInput(CbrCase cbrCase, String entityId,
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

        attributes.put("cbr.type", cbrCase.cbrType());
        Map<String, Object> features = cbrCase.features();
        if (!features.isEmpty()) {
            try {
                attributes.put("cbr.features", MAPPER.writeValueAsString(features));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize features to JSON", e);
            }
        }
        if (cbrCase instanceof PlanCbrCase plan) {
            try {
                attributes.put("cbr.planTrace", MAPPER.writeValueAsString(plan.planTrace()));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize plan trace to JSON", e);
            }
        }

        // Add caseType to attributes for reconstruction
        attributes.put("cbr.caseType", caseType);

        return new MemoryInput(entityId, domain, tenantId, caseId, cbrCase.problem(), attributes);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrCase> List<C> retrieveSimilar(CbrQuery query, Class<C> caseClass) {
        CbrFeatureSchema schema = schemas.get(query.caseType());
        if (schema != null) {
            CbrQueryTranslator.validateQueryFeatures(query.features(), schema);
        }

        String collection = collectionManager.collectionName(query.caseType());

        // Check collection exists
        try {
            if (!collectionManager.client().collectionExistsAsync(collection).get()) {
                return List.of();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted checking collection existence", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to check collection existence", e.getCause());
        }

        Filter filter = CbrQueryTranslator.toFilter(query, schema);

        // CBR retrieval is payload-filter-based; dense vector search is future work
        List<ScoredPoint> scoredPoints = executeFilterQuery(collection, filter, query.topK());

        List<C> results = new ArrayList<>(scoredPoints.size());
        for (ScoredPoint point : scoredPoints) {
            try {
                C cbrCase = (C) reconstructCase(point.getPayloadMap(), caseClass);
                if (cbrCase != null) {
                    results.add(cbrCase);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to reconstruct case from point", e);
            }
        }

        return Collections.unmodifiableList(results);
    }

    @Override
    public Integer erase(EraseRequest request) {
        // Delegate erasure to CaseMemoryStore first (durable storage)
        int delegateCount = 0;
        if (delegate != null) {
            delegateCount = delegate.erase(request);
        }

        // Build filter matching the erase request
        Filter.Builder builder = Filter.newBuilder()
            .addMust(ConditionFactory.matchKeyword("entityId", request.entityId()))
            .addMust(ConditionFactory.matchKeyword("domain", request.domain().name()))
            .addMust(ConditionFactory.matchKeyword("tenantId", request.tenantId()));
        if (request.caseId() != null) {
            builder.addMust(ConditionFactory.matchKeyword("caseId", request.caseId()));
        }

        // Delete across all known collections for this tenant
        int totalErased = 0;
        for (String caseType : schemas.keySet()) {
            String collection = collectionManager.collectionName(caseType);
            try {
                if (collectionManager.client().collectionExistsAsync(collection).get()) {
                    totalErased += collectionManager.deleteByFilter(collection, builder.build());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during erase", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("Erase failed", e.getCause());
            }
        }
        // Return delegate count if available, otherwise Qdrant count
        return delegate != null ? delegateCount : totalErased;
    }

    @Override
    public Integer eraseEntity(String entityId, String tenantId) {
        // Delegate erasure to CaseMemoryStore first (durable storage)
        int delegateCount = 0;
        if (delegate != null) {
            delegateCount = delegate.eraseEntity(entityId, tenantId);
        }

        Filter filter = Filter.newBuilder()
            .addMust(ConditionFactory.matchKeyword("entityId", entityId))
            .addMust(ConditionFactory.matchKeyword("tenantId", tenantId))
            .build();

        int totalErased = 0;
        for (String caseType : schemas.keySet()) {
            String collection = collectionManager.collectionName(caseType);
            try {
                if (collectionManager.client().collectionExistsAsync(collection).get()) {
                    totalErased += collectionManager.deleteByFilter(collection, filter);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during eraseEntity", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("EraseEntity failed", e.getCause());
            }
        }
        // Return delegate count if available, otherwise Qdrant count
        return delegate != null ? delegateCount : totalErased;
    }

    private int vectorDimension() {
        return embeddingModel != null ? embeddingModel.dimension() : 0;
    }

    private List<ScoredPoint> executeFilterQuery(String collection, Filter filter, int limit) {
        try {
            // Use scroll with filter for payload-filter-only retrieval
            var scrollBuilder = io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                .setCollectionName(collection)
                .setFilter(filter)
                .setLimit(limit)
                .setWithPayload(WithPayloadSelectorFactory.enable(true));

            var response = collectionManager.client().scrollAsync(scrollBuilder.build()).get();

            // Convert RetrievedPoints to ScoredPoints for uniform processing
            List<ScoredPoint> results = new ArrayList<>(response.getResultCount());
            for (var retrieved : response.getResultList()) {
                results.add(ScoredPoint.newBuilder()
                    .setId(retrieved.getId())
                    .putAllPayload(retrieved.getPayloadMap())
                    .setScore(1.0f) // all matches equal in filter-only mode
                    .build());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during filter query", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Filter query failed", e.getCause());
        }
    }

    private <C extends CbrCase> C reconstructCase(Map<String, Value> payload, Class<C> caseClass) {
        String problem = extractString(payload, "problem");
        String solution = extractString(payload, "solution");
        if (problem == null || solution == null) return null;

        String outcome = extractString(payload, "outcome");
        Double confidence = extractDouble(payload, "confidence");
        String cbrType = extractString(payload, "_cbr_type");

        CbrCase reconstructed = switch (cbrType) {
            case FeatureVectorCbrCase.CBR_TYPE -> reconstructFeatureVector(payload, problem, solution, outcome, confidence);
            case PlanCbrCase.CBR_TYPE -> reconstructPlanCase(payload, problem, solution, outcome, confidence);
            case TextualCbrCase.CBR_TYPE -> new TextualCbrCase(problem, solution, outcome, confidence);
            case null -> throw new IllegalStateException("Missing _cbr_type in CBR point");
            default -> throw new IllegalArgumentException("Unknown CBR type: " + cbrType);
        };

        if (!caseClass.isInstance(reconstructed)) return null;
        return caseClass.cast(reconstructed);
    }

    private CbrCase reconstructPlanCase(Map<String, Value> payload,
                                          String problem, String solution,
                                          String outcome, Double confidence) {
        Map<String, Object> features = Map.of();
        String featuresJson = extractString(payload, "_features_json");
        if (featuresJson != null) {
            try {
                features = MAPPER.readValue(featuresJson, MAP_TYPE);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Corrupted _features_json in CBR point", e);
            }
        }

        List<PlanTrace> planTrace = List.of();
        String planTraceJson = extractString(payload, "_plan_trace_json");
        if (planTraceJson != null) {
            try {
                planTrace = MAPPER.readValue(planTraceJson, PLAN_TRACE_TYPE);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Corrupted _plan_trace_json in CBR point", e);
            }
        }

        return new PlanCbrCase(problem, solution, outcome, confidence, features, planTrace);
    }

    private CbrCase reconstructFeatureVector(Map<String, Value> payload,
                                              String problem, String solution,
                                              String outcome, Double confidence) {
        String featuresJson = extractString(payload, "_features_json");
        if (featuresJson == null) {
            return new FeatureVectorCbrCase(problem, solution, outcome, confidence, Map.of());
        }
        try {
            Map<String, Object> features = MAPPER.readValue(featuresJson, MAP_TYPE);
            return new FeatureVectorCbrCase(problem, solution, outcome, confidence, features);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Corrupted _features_json in CBR point", e);
        }
    }

    private static String extractString(Map<String, Value> payload, String key) {
        Value v = payload.get(key);
        if (v != null && v.hasStringValue()) {
            return v.getStringValue();
        }
        return null;
    }

    private static Double extractDouble(Map<String, Value> payload, String key) {
        Value v = payload.get(key);
        if (v != null && v.hasDoubleValue()) {
            return v.getDoubleValue();
        }
        return null;
    }
}
