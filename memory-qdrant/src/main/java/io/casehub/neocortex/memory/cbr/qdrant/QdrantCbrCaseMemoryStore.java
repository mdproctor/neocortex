package io.casehub.neocortex.memory.cbr.qdrant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.neocortex.fusion.CamelCaseExpander;
import io.casehub.neocortex.fusion.FusionStrategy;
import io.casehub.neocortex.fusion.ScoreFusion;
import io.casehub.neocortex.inference.splade.SparseEmbedder;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrFeatureValidator;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrSimilarityScorer;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.LocalSimilarityFunction;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.TextualCbrCase;
import io.casehub.neocortex.memory.cbr.embedding.EmbeddingTextSimilarity;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
@ApplicationScoped
public class QdrantCbrCaseMemoryStore implements CbrCaseMemoryStore {

    private static final Logger                             LOG             = Logger.getLogger(QdrantCbrCaseMemoryStore.class.getName());
    private static final ObjectMapper                       MAPPER          = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE        = new TypeReference<>() {};
    private static final TypeReference<List<PlanTrace>>     PLAN_TRACE_TYPE = new TypeReference<>() {};
    private final CbrCollectionManager          collectionManager;
    private final EmbeddingModel                embeddingModel; // nullable
    private final SparseEmbedder                sparseEmbedder; // nullable — optional SPLADE embedder
    private final QdrantCbrConfig               config;
    private final CaseMemoryStore               delegate; // nullable — when present, delegate durable storage
    private final Map<String, CbrFeatureSchema> schemas = new ConcurrentHashMap<>();
    @Inject
    QdrantCbrCaseMemoryStore(CbrCollectionManager collectionManager,
                             Instance<EmbeddingModel> embeddingModelInstance,
                             QdrantCbrConfig config,
                             Instance<CaseMemoryStore> delegateInstance,
                             Instance<SparseEmbedder> sparseEmbedderInstance) {
        this.collectionManager = collectionManager;
        this.embeddingModel    = embeddingModelInstance.isResolvable() ? embeddingModelInstance.get() : null;
        this.config            = config;
        this.delegate          = delegateInstance.isResolvable() ? delegateInstance.get() : null;
        this.sparseEmbedder    = sparseEmbedderInstance.isResolvable() ? sparseEmbedderInstance.get() : null;
    }

    QdrantCbrCaseMemoryStore(CbrCollectionManager collectionManager,
                             EmbeddingModel embeddingModel,
                             QdrantCbrConfig config,
                             CaseMemoryStore delegate) {
        this(collectionManager, embeddingModel, config, delegate, null);
    }

    QdrantCbrCaseMemoryStore(CbrCollectionManager collectionManager,
                             EmbeddingModel embeddingModel,
                             QdrantCbrConfig config,
                             CaseMemoryStore delegate,
                             SparseEmbedder sparseEmbedder) {
        this.collectionManager = collectionManager;
        this.embeddingModel    = embeddingModel;
        this.config            = config;
        this.delegate          = delegate;
        this.sparseEmbedder    = sparseEmbedder;
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

    @Override
    public void registerSchema(CbrFeatureSchema schema) {
        schemas.put(schema.caseType(), schema);
        collectionManager.registerSchemaIndexes(schema, vectorDimension());
    }

    @Override
    public String store(CbrCase cbrCase, String caseType, String entityId,
                        MemoryDomain domain, String tenantId, String caseId) {
        CbrFeatureSchema schema = schemas.get(caseType);
        if (schema != null) {
            CbrFeatureValidator.validateStoreFeatures(cbrCase.features(), schema);
        }

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

        // SPLADE sparse embedding
        Map<Integer, Float> sparseEmbedding = null;
        if (sparseEmbedder != null && config.spladeEnabled() && cbrCase.problem() != null) {
            sparseEmbedding = sparseEmbedder.embed(cbrCase.problem());
        }

        // BM25 text (camel-case expanded)
        String bm25Text = null;
        if (config.bm25Enabled() && cbrCase.problem() != null) {
            bm25Text = CamelCaseExpander.expand(cbrCase.problem());
        }

        PointStruct point = CbrPointBuilder.buildPoint(
                cbrCase, caseType, entityId, domain.name(), tenantId, caseId,
                embedding, config.denseVectorName(),
                sparseEmbedding, config.spladeVectorName(),
                bm25Text, config.bm25VectorName(), config.bm25Model());

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
     * Delegates to {@link CbrMemorySerializer}.
     */
    private MemoryInput serializeToMemoryInput(CbrCase cbrCase, String entityId,
                                               MemoryDomain domain, String tenantId,
                                               String caseId, String caseType) {
        return CbrMemorySerializer.serialize(cbrCase, entityId, domain, tenantId, caseId, caseType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery query, Class<C> caseClass) {
        CbrFeatureSchema schema = schemas.get(query.caseType());
        if (schema != null) {
            CbrQueryTranslator.validateQueryFeatures(query.features(), schema);
        }
        if (!query.filters().isEmpty() && schema == null) {
            throw new IllegalStateException(
                    "Cannot apply structural filters: no schema registered for caseType '"
                    + query.caseType() + "'");
        }

        String collection = collectionManager.collectionName(query.caseType());

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

        Filter filter = CbrQueryTranslator.toIdentityFilter(query);
        if (!query.filters().isEmpty()) {
            filter = CbrQueryTranslator.applyStructuralFilters(filter, query.filters(), schema);
        }
        RetrievalMode effectiveMode = resolveEffectiveMode(query);
        if (effectiveMode == null) {
            return List.of();
        }

        return switch (effectiveMode) {
            case FEATURE_ONLY -> retrieveFeatureOnly(query, caseClass, collection, filter, schema);
            case SEMANTIC_ONLY -> retrieveSemanticOnly(query, caseClass, collection, filter);
            case HYBRID -> retrieveHybrid(query, caseClass, collection, filter, schema);
        };
    }

    private RetrievalMode resolveEffectiveMode(CbrQuery query) {
        return switch (query.retrievalMode()) {
            case SEMANTIC_ONLY -> {
                if (embeddingModel == null || query.problem() == null) {
                    LOG.warning("SEMANTIC_ONLY unavailable — " +
                                (embeddingModel == null ? "no EmbeddingModel" : "problem is null"));
                    yield null;
                }
                yield RetrievalMode.SEMANTIC_ONLY;
            }
            case HYBRID -> {
                if (embeddingModel == null || query.problem() == null) {
                    LOG.warning("HYBRID degraded to FEATURE_ONLY — " +
                                (embeddingModel == null ? "no EmbeddingModel" : "problem is null"));
                    yield RetrievalMode.FEATURE_ONLY;
                }
                yield RetrievalMode.HYBRID;
            }
            case FEATURE_ONLY -> RetrievalMode.FEATURE_ONLY;
        };
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> List<ScoredCbrCase<C>> retrieveFeatureOnly(
            CbrQuery query, Class<C> caseClass, String collection, Filter filter,
            CbrFeatureSchema schema) {
        List<ScoredPoint> scoredPoints = executeFilterQuery(collection, filter,
                                                            Math.max(query.topK(), config.overFetchLimit()));

        EmbeddingTextSimilarity textSim = (schema != null && embeddingModel != null)
                                          ? new EmbeddingTextSimilarity(embeddingModel) : null;
        Map<String, LocalSimilarityFunction> overrides = schema != null
                                                         ? buildTextOverrides(schema, textSim) : Map.of();

        List<ReconstructedCandidate<C>> reconstructed = reconstructAll(scoredPoints, caseClass);

        if (textSim != null && !overrides.isEmpty()) {
            textSim.precompute(collectSemanticTextValues(query, reconstructed, overrides.keySet()));
        }

        List<ScoredCbrCase<C>> candidates = new ArrayList<>(reconstructed.size());
        for (var rc : reconstructed) {
            double score = CbrSimilarityScorer.score(
                    query.features(), rc.cbrCase().features(), query.weights(), schema, overrides);
            if (score >= query.minSimilarity()) {
                candidates.add(new ScoredCbrCase<>(rc.cbrCase(), score));
            }
        }

        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
        return trimToTopK(candidates, query.topK());
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSemanticOnly(
            CbrQuery query, Class<C> caseClass, String collection, Filter filter) {
        List<ScoredPoint> scoredPoints = executeDenseSearch(collection, filter, query);

        List<ScoredCbrCase<C>> candidates = new ArrayList<>(scoredPoints.size());
        for (ScoredPoint point : scoredPoints) {
            try {
                C cbrCase = (C) reconstructCase(point.getPayloadMap(), caseClass);
                if (cbrCase != null && point.getScore() >= query.minSimilarity()) {
                    candidates.add(new ScoredCbrCase<>(cbrCase, point.getScore()));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to reconstruct case from point", e);
            }
        }

        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
        return trimToTopK(candidates, query.topK());
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> List<ScoredCbrCase<C>> retrieveHybrid(
            CbrQuery query, Class<C> caseClass, String collection, Filter filter,
            CbrFeatureSchema schema) {
        List<ScoredPoint> densePoints = executeDenseSearch(collection, filter, query);
        List<ScoredPoint> filterPoints = executeFilterQuery(collection, filter,
                                                            Math.max(query.topK(), config.overFetchLimit()));

        Map<String, ReconstructedCandidate<C>> candidateMap = new LinkedHashMap<>();
        mergePoints(densePoints, candidateMap, caseClass, true);

        // SPLADE leg — merge results into candidate map
        List<ScoredPoint> spladePoints = List.of();
        if (sparseEmbedder != null && config.spladeEnabled() && query.problem() != null) {
            try {
                spladePoints = executeSpladeSearch(collection, filter, query);
                mergePoints(spladePoints, candidateMap, caseClass, false);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "SPLADE search failed — skipping sparse leg", e);
            }
        }

        // BM25 leg — merge results into candidate map
        List<ScoredPoint> bm25Points = List.of();
        if (config.bm25Enabled() && query.problem() != null) {
            try {
                bm25Points = executeBm25Search(collection, filter, query);
                mergePoints(bm25Points, candidateMap, caseClass, false);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "BM25 search failed — skipping keyword leg", e);
            }
        }

        mergePoints(filterPoints, candidateMap, caseClass, false);
        List<ReconstructedCandidate<C>> allCandidates = new ArrayList<>(candidateMap.values());

        EmbeddingTextSimilarity textSim = (schema != null && embeddingModel != null)
                                          ? new EmbeddingTextSimilarity(embeddingModel) : null;
        Map<String, LocalSimilarityFunction> overrides = schema != null
                                                         ? buildTextOverrides(schema, textSim) : Map.of();
        if (textSim != null && !overrides.isEmpty()) {
            textSim.precompute(collectSemanticTextValues(query, allCandidates, overrides.keySet()));
        }

        record FusionEntry<C extends CbrCase>(String pointId, C cbrCase, double score) {}

        // Build feature leg (always present)
        List<FusionEntry<C>> featureLeg = new ArrayList<>();
        for (var rc : allCandidates) {
            double featureScore = CbrSimilarityScorer.score(
                    query.features(), rc.cbrCase().features(), query.weights(), schema, overrides);
            featureLeg.add(new FusionEntry<>(rc.pointId(), rc.cbrCase(), featureScore));
        }

        // Build semantic legs — dense is always present when embeddingModel exists
        List<FusionEntry<C>> denseLeg = new ArrayList<>();
        for (var rc : allCandidates) {
            if (rc.vectorScore() > 0) {
                denseLeg.add(new FusionEntry<>(rc.pointId(), rc.cbrCase(), rc.vectorScore()));
            }
        }

        // Build SPLADE and BM25 legs from their scored points
        Map<String, Float> spladeScores = new HashMap<>();
        for (var sp : spladePoints) {
            spladeScores.put(sp.getId().getUuid(), sp.getScore());
        }
        List<FusionEntry<C>> spladeLeg = new ArrayList<>();
        for (var rc : allCandidates) {
            Float score = spladeScores.get(rc.pointId());
            if (score != null && score > 0) {
                spladeLeg.add(new FusionEntry<>(rc.pointId(), rc.cbrCase(), score));
            }
        }

        Map<String, Float> bm25Scores = new HashMap<>();
        for (var sp : bm25Points) {
            bm25Scores.put(sp.getId().getUuid(), sp.getScore());
        }
        List<FusionEntry<C>> bm25Leg = new ArrayList<>();
        for (var rc : allCandidates) {
            Float score = bm25Scores.get(rc.pointId());
            if (score != null && score > 0) {
                bm25Leg.add(new FusionEntry<>(rc.pointId(), rc.cbrCase(), score));
            }
        }

        // Assemble fusion legs with CC weight renormalization
        java.util.function.Function<FusionEntry<C>, String> idExtractor      = FusionEntry::pointId;
        double                                              featureWeight    = 1.0 - query.vectorWeight();
        var                                                 featureScoredLeg = new ScoreFusion.ScoredLeg<>(featureLeg, FusionEntry::score, featureWeight);

        // Compute renormalized semantic sub-weights among active legs
        double rawDense      = (!denseLeg.isEmpty()) ? config.ccWeights().dense() : 0.0;
        double rawSparse     = (!spladeLeg.isEmpty()) ? config.ccWeights().sparse() : 0.0;
        double rawBm25       = (!bm25Leg.isEmpty()) ? config.ccWeights().bm25() : 0.0;
        double semanticTotal = rawDense + rawSparse + rawBm25;

        List<ScoreFusion.ScoredLeg<FusionEntry<C>>> legs = new ArrayList<>();
        legs.add(featureScoredLeg);
        if (!denseLeg.isEmpty() && semanticTotal > 0) {
            legs.add(new ScoreFusion.ScoredLeg<>(
                    denseLeg, FusionEntry::score, query.vectorWeight() * rawDense / semanticTotal));
        }
        if (!spladeLeg.isEmpty() && semanticTotal > 0) {
            legs.add(new ScoreFusion.ScoredLeg<>(
                    spladeLeg, FusionEntry::score, query.vectorWeight() * rawSparse / semanticTotal));
        }
        if (!bm25Leg.isEmpty() && semanticTotal > 0) {
            legs.add(new ScoreFusion.ScoredLeg<>(
                    bm25Leg, FusionEntry::score, query.vectorWeight() * rawBm25 / semanticTotal));
        }

        List<ScoreFusion.FusedResult<FusionEntry<C>>> fused = switch (query.fusionStrategy()) {
            case RRF -> ScoreFusion.rrf(legs, idExtractor, query.topK(), 60);
            case CC -> ScoreFusion.convexCombination(legs, idExtractor, query.topK());
            case DBSF -> throw new UnsupportedOperationException(
                    "DBSF is not supported for CBR — use RRF or CC");
        };

        List<ScoredCbrCase<C>> results = new ArrayList<>(fused.size());
        for (var f : fused) {
            double score = Math.max(-1.0, Math.min(1.0, f.score()));
            if (query.fusionStrategy() == FusionStrategy.RRF || score >= query.minSimilarity()) {
                results.add(new ScoredCbrCase<>(f.item().cbrCase(), score));
            }
        }
        return Collections.unmodifiableList(results);
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> void mergePoints(List<ScoredPoint> points,
                                                 Map<String, ReconstructedCandidate<C>> map, Class<C> caseClass,
                                                 boolean useDenseScore) {
        for (ScoredPoint point : points) {
            String pointId = point.getId().getUuid();
            if (map.containsKey(pointId)) {continue;}
            try {
                C cbrCase = (C) reconstructCase(point.getPayloadMap(), caseClass);
                if (cbrCase != null) {
                    map.put(pointId, new ReconstructedCandidate<>(pointId, cbrCase,
                                                                  useDenseScore ? point.getScore() : 0f));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to reconstruct case from point", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> List<ReconstructedCandidate<C>> reconstructAll(
            List<ScoredPoint> scoredPoints, Class<C> caseClass) {
        List<ReconstructedCandidate<C>> reconstructed = new ArrayList<>(scoredPoints.size());
        for (ScoredPoint point : scoredPoints) {
            try {
                C cbrCase = (C) reconstructCase(point.getPayloadMap(), caseClass);
                if (cbrCase != null) {
                    reconstructed.add(new ReconstructedCandidate<>(
                            point.getId().getUuid(), cbrCase, point.getScore()));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to reconstruct case from point", e);
            }
        }
        return reconstructed;
    }

    private <C extends CbrCase> List<ScoredCbrCase<C>> trimToTopK(
            List<ScoredCbrCase<C>> candidates, int topK) {
        List<ScoredCbrCase<C>> results = candidates.size() <= topK
                                         ? candidates : candidates.subList(0, topK);
        return Collections.unmodifiableList(new ArrayList<>(results));
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

    @Override
    public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
        for (String caseType : schemas.keySet()) {
            String collection = collectionManager.collectionName(caseType);
            try {
                if (!collectionManager.client().collectionExistsAsync(collection).get()) {
                    continue;
                }
                UUID pointUuid = CbrPointBuilder.pointId(tenantId, caseType, caseId);
                var  pointId   = PointIdFactory.id(pointUuid);

                var points = collectionManager.client()
                                              .retrieveAsync(collection, List.of(pointId), true, false, null).get();
                if (points.isEmpty()) {continue;}

                var payload = points.getFirst().getPayloadMap();

                String existingLastOutcome = extractString(payload, "last_outcome_at");
                if (existingLastOutcome != null) {
                    Instant existingInstant = Instant.parse(existingLastOutcome);
                    if (!outcome.observedAt().isAfter(existingInstant)) {return;}
                }

                Double oldConfidence = extractDouble(payload, "confidence");
                double newConfidence = CbrOutcome.adjustConfidence(
                        oldConfidence, outcome.successRate(), CbrOutcome.DEFAULT_LEARNING_RATE);

                Map<String, Value> updates = new HashMap<>();
                updates.put("outcome", ValueFactory.value(outcome.result().name()));
                updates.put("confidence", ValueFactory.value(newConfidence));
                updates.put("last_outcome_at", ValueFactory.value(outcome.observedAt().toString()));
                if (outcome.detail() != null) {
                    updates.put("outcome_detail", ValueFactory.value(outcome.detail()));
                }

                collectionManager.client()
                                 .setPayloadAsync(collection, updates, (PointId) pointId, null, null, null).get();
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during recordOutcome", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("recordOutcome failed", e.getCause());
            }
        }
    }

    private int vectorDimension() {
        return embeddingModel != null ? embeddingModel.dimension() : 0;
    }

    private Map<String, LocalSimilarityFunction> buildTextOverrides(
            CbrFeatureSchema schema, EmbeddingTextSimilarity textSim) {
        if (textSim == null) {return Map.of();}
        Map<String, LocalSimilarityFunction> overrides = new HashMap<>();
        for (FeatureField field : schema.fields()) {
            switch (field) {
                case FeatureField.Text t -> {
                    if (t.semantic()) {overrides.put(field.name(), textSim);}
                }
                case FeatureField.Categorical c -> {}
                case FeatureField.Numeric n -> {}
                case FeatureField.CategoricalList cl -> {}
                case FeatureField.NumericList nl -> {}
                case FeatureField.NestedObject no -> {}
                case FeatureField.ObjectList ol -> {}
                case FeatureField.TimeSeries ts -> {}
                case FeatureField.DiscreteSequence ds -> {}
            }
        }
        return overrides.isEmpty() ? Map.of() : Collections.unmodifiableMap(overrides);
    }

    private <C extends CbrCase> List<String> collectSemanticTextValues(
            CbrQuery query, List<ReconstructedCandidate<C>> candidates, Set<String> fieldNames) {
        List<String> texts = new ArrayList<>();
        for (String fieldName : fieldNames) {
            if (query.features().get(fieldName) instanceof FeatureValue.StringVal s) {texts.add(s.value());}
            for (var rc : candidates) {
                if (rc.cbrCase().features().get(fieldName) instanceof FeatureValue.StringVal s) {texts.add(s.value());}
            }
        }
        return texts;
    }

    private List<ScoredPoint> executeDenseSearch(String collection, Filter filter, CbrQuery query) {
        Embedding queryEmbedding = embeddingModel.embed(TextSegment.from(query.problem())).content();

        SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
                                                         .setCollectionName(collection)
                                                         .addAllVector(queryEmbedding.vectorAsList())
                                                         .setVectorName(config.denseVectorName())
                                                         .setFilter(filter)
                                                         .setLimit(query.topK() * config.oversampleFactor())
                                                         .setWithPayload(WithPayloadSelectorFactory.enable(true));

        try {
            return collectionManager.client().searchAsync(searchBuilder.build()).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during dense search", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Dense search failed", e.getCause());
        }
    }

    private List<ScoredPoint> executeSpladeSearch(String collection, Filter filter, CbrQuery query) {
        Map<Integer, Float> sparseEmbedding = sparseEmbedder.embed(query.problem());
        List<Float>         sparseValues    = new ArrayList<>(sparseEmbedding.size());
        List<Integer>       sparseIndices   = new ArrayList<>(sparseEmbedding.size());
        for (Map.Entry<Integer, Float> entry : sparseEmbedding.entrySet()) {
            sparseIndices.add(entry.getKey());
            sparseValues.add(entry.getValue());
        }

        int limit = config.spladeTopK() > 0 ? config.spladeTopK() : query.topK();
        var queryPoints = io.qdrant.client.grpc.Points.QueryPoints.newBuilder()
                                                                  .setCollectionName(collection)
                                                                  .setQuery(io.qdrant.client.QueryFactory.nearest(sparseValues, sparseIndices))
                                                                  .setUsing(config.spladeVectorName())
                                                                  .setFilter(filter)
                                                                  .setLimit(limit)
                                                                  .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true))
                                                                  .build();

        try {
            return collectionManager.client().queryAsync(queryPoints).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during SPLADE search", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("SPLADE search failed", e.getCause());
        }
    }

    private List<ScoredPoint> executeBm25Search(String collection, Filter filter, CbrQuery query) {
        String expandedQuery = CamelCaseExpander.expand(query.problem());
        int    limit         = config.bm25TopK() > 0 ? config.bm25TopK() : query.topK();
        var queryPoints = io.qdrant.client.grpc.Points.QueryPoints.newBuilder()
                                                                  .setCollectionName(collection)
                                                                  .setQuery(io.qdrant.client.QueryFactory.nearest(
                                                                          io.qdrant.client.grpc.Points.Document.newBuilder()
                                                                                                               .setText(expandedQuery)
                                                                                                               .setModel(config.bm25Model())
                                                                                                               .build()))
                                                                  .setUsing(config.bm25VectorName())
                                                                  .setFilter(filter)
                                                                  .setLimit(limit)
                                                                  .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true))
                                                                  .build();

        try {
            return collectionManager.client().queryAsync(queryPoints).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during BM25 search", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("BM25 search failed", e.getCause());
        }
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
        String problem  = extractString(payload, "problem");
        String solution = extractString(payload, "solution");
        if (problem == null || solution == null) {return null;}

        String outcome    = extractString(payload, "outcome");
        Double confidence = extractDouble(payload, "confidence");
        String cbrType    = extractString(payload, "_cbr_type");

        CbrCase reconstructed = switch (cbrType) {
            case FeatureVectorCbrCase.CBR_TYPE -> reconstructFeatureVector(payload, problem, solution, outcome, confidence);
            case PlanCbrCase.CBR_TYPE -> reconstructPlanCase(payload, problem, solution, outcome, confidence);
            case TextualCbrCase.CBR_TYPE -> new TextualCbrCase(problem, solution, outcome, confidence);
            case null -> throw new IllegalStateException("Missing _cbr_type in CBR point");
            default -> throw new IllegalArgumentException("Unknown CBR type: " + cbrType);
        };

        if (!caseClass.isInstance(reconstructed)) {return null;}
        return caseClass.cast(reconstructed);
    }

    private CbrCase reconstructPlanCase(Map<String, Value> payload,
                                        String problem, String solution,
                                        String outcome, Double confidence) {
        Map<String, FeatureValue> features     = Map.of();
        String                    featuresJson = extractString(payload, "_features_json");
        if (featuresJson != null) {
            try {
                features = CbrPointBuilder.fromRawMap(MAPPER.readValue(featuresJson, MAP_TYPE));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Corrupted _features_json in CBR point", e);
            }
        }

        List<PlanTrace> planTrace     = List.of();
        String          planTraceJson = extractString(payload, "_plan_trace_json");
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
            var features = CbrPointBuilder.fromRawMap(MAPPER.readValue(featuresJson, MAP_TYPE));
            return new FeatureVectorCbrCase(problem, solution, outcome, confidence, features);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Corrupted _features_json in CBR point", e);
        }
    }

    private record ReconstructedCandidate<C extends CbrCase>(String pointId, C cbrCase, float vectorScore) {}
}
