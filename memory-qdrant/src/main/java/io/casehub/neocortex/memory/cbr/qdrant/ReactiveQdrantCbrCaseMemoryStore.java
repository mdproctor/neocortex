package io.casehub.neocortex.memory.cbr.qdrant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
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
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrFeatureValidator;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.CbrSimilarityScorer;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.LocalSimilarityFunction;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.ReactiveCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.TextualCbrCase;
import io.casehub.neocortex.memory.cbr.embedding.EmbeddingTextSimilarity;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
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
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class ReactiveQdrantCbrCaseMemoryStore implements ReactiveCbrCaseMemoryStore {

    private static final Logger LOG = Logger.getLogger(ReactiveQdrantCbrCaseMemoryStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<PlanTrace>> PLAN_TRACE_TYPE = new TypeReference<>() {};

    private final CbrCollectionManager collectionManager;
    private final EmbeddingModel embeddingModel;
    private final SparseEmbedder sparseEmbedder;
    private final QdrantCbrConfig config;
    private final CaseMemoryStore delegate;
    private final Map<String, CbrFeatureSchema> schemas = new ConcurrentHashMap<>();

    @Inject
    ReactiveQdrantCbrCaseMemoryStore(CbrCollectionManager collectionManager,
                                     Instance<EmbeddingModel> embeddingModelInstance,
                                     QdrantCbrConfig config,
                                     Instance<CaseMemoryStore> delegateInstance,
                                     Instance<SparseEmbedder> sparseEmbedderInstance) {
        this.collectionManager = collectionManager;
        this.embeddingModel = embeddingModelInstance.isResolvable() ? embeddingModelInstance.get() : null;
        this.config = config;
        this.delegate = delegateInstance.isResolvable() ? delegateInstance.get() : null;
        this.sparseEmbedder = sparseEmbedderInstance.isResolvable() ? sparseEmbedderInstance.get() : null;
    }

    ReactiveQdrantCbrCaseMemoryStore(CbrCollectionManager collectionManager,
                                     EmbeddingModel embeddingModel,
                                     QdrantCbrConfig config,
                                     CaseMemoryStore delegate,
                                     SparseEmbedder sparseEmbedder) {
        this.collectionManager = collectionManager;
        this.embeddingModel = embeddingModel;
        this.config = config;
        this.delegate = delegate;
        this.sparseEmbedder = sparseEmbedder;
    }

    // ── ListenableFuture → Uni adapter ───────────────────────────────────────

    private static <T> Uni<T> toUni(ListenableFuture<T> future) {
        return Uni.createFrom().<T>emitter(em ->
            Futures.addCallback(future, new FutureCallback<>() {
                @Override public void onSuccess(T result) { em.complete(result); }
                @Override public void onFailure(Throwable t) { em.fail(t); }
            }, MoreExecutors.directExecutor()));
    }

    // ── payload helpers (pure CPU) ───────────────────────────────────────────

    private static String extractString(Map<String, Value> payload, String key) {
        Value v = payload.get(key);
        if (v != null && v.hasStringValue()) return v.getStringValue();
        return null;
    }

    private static Instant extractStoredAt(Map<String, Value> payload) {
        Value v = payload.get("_stored_at");
        if (v != null && v.hasIntegerValue()) {
            long millis = v.getIntegerValue();
            return millis > 0 ? Instant.ofEpochMilli(millis) : null;
        }
        return null;
    }

    private static io.casehub.platform.api.path.Path extractScope(Map<String, Value> payload) {
        String sv = extractString(payload, "scope");
        return (sv == null || sv.isEmpty()) ? io.casehub.platform.api.path.Path.root()
                                            : io.casehub.platform.api.path.Path.parse(sv);
    }

    private static Double extractDouble(Map<String, Value> payload, String key) {
        Value v = payload.get(key);
        if (v != null && v.hasDoubleValue()) return v.getDoubleValue();
        return null;
    }

    // ── registerSchema ───────────────────────────────────────────────────────

    @Override
    public Uni<Void> registerSchema(CbrFeatureSchema schema) {
        schemas.put(schema.caseType(), schema);
        return Uni.createFrom().voidItem()
                  .invoke(() -> collectionManager.registerSchemaIndexes(schema, vectorDimension()))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());}

    // ── store ────────────────────────────────────────────────────────────────

    @Override
    public Uni<String> store(CbrCase cbrCase, String caseType, String entityId,
                             MemoryDomain domain, String tenantId, String caseId,
                             io.casehub.platform.api.path.Path scope) {
        CbrFeatureSchema schema = schemas.get(caseType);
        if (schema != null) {
            CbrFeatureValidator.validateStoreFeatures(cbrCase.features(), schema);
        }

        return Uni.createFrom().item(() -> {
                      String mid = caseId;
                      if (delegate != null) {
                          MemoryInput memoryInput = CbrMemorySerializer.serialize(
                                  cbrCase, entityId, domain, tenantId, caseId, caseType);
                          mid = delegate.store(memoryInput);
                      }

                      collectionManager.ensureCollection(caseType, vectorDimension());

                      Embedding embedding = null;
                      if (embeddingModel != null) {
                          embedding = embeddingModel.embed(TextSegment.from(cbrCase.problem())).content();
                      }

                      Map<Integer, Float> sparseEmbedding = null;
                      if (sparseEmbedder != null && config.spladeEnabled() && cbrCase.problem() != null) {
                          sparseEmbedding = sparseEmbedder.embed(cbrCase.problem());
                      }

                      String bm25Text = null;
                      if (config.bm25Enabled() && cbrCase.problem() != null) {
                          bm25Text = CamelCaseExpander.expand(cbrCase.problem());
                      }

                      PointStruct point = CbrPointBuilder.buildPoint(
                              cbrCase, caseType, entityId, domain.name(), tenantId, caseId,
                              embedding, config.denseVectorName(),
                              sparseEmbedding, config.spladeVectorName(),
                              bm25Text, config.bm25VectorName(), config.bm25Model(), scope.value());

                      String collection = collectionManager.collectionName(caseType);
                      return new StoreContext(mid, collection, point);
                  })
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                  .chain(ctx -> upsertWithRetry(ctx.collection(), List.of(ctx.point()), config.maxRetries())
                                        .replaceWith(ctx.memoryId()));}

    private Uni<Void> upsertWithRetry(String collection, List<PointStruct> points, int maxRetries) {
        return toUni(collectionManager.client().upsertAsync(collection, points))
            .replaceWithVoid()
            .onFailure().retry().atMost(maxRetries - 1);
    }

    // ── retrieveSimilar ──────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(
            CbrQuery query, Class<C> caseClass) {
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

        return toUni(collectionManager.client().collectionExistsAsync(collection))
            .chain(exists -> {
                if (!exists) return Uni.createFrom().item(List.<ScoredCbrCase<C>>of());

                Filter filter = CbrQueryTranslator.toIdentityFilter(query);
                if (!query.filters().isEmpty()) {
                    filter = CbrQueryTranslator.applyStructuralFilters(filter, query.filters(), schema);
                }
                RetrievalMode effectiveMode = resolveEffectiveMode(query);
                if (effectiveMode == null) return Uni.createFrom().item(List.<ScoredCbrCase<C>>of());

                return switch (effectiveMode) {
                    case FEATURE_ONLY -> retrieveFeatureOnlyAsync(query, caseClass, collection, filter, schema);
                    case SEMANTIC_ONLY -> retrieveSemanticOnlyAsync(query, caseClass, collection, filter);
                    case HYBRID -> retrieveHybridAsync(query, caseClass, collection, filter, schema);
                };
            });
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

    // ── FEATURE_ONLY ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveFeatureOnlyAsync(
            CbrQuery query, Class<C> caseClass, String collection, Filter filter,
            CbrFeatureSchema schema) {
        return executeFilterQueryAsync(collection, filter, Math.max(query.topK(), config.overFetchLimit()))
            .map(scoredPoints -> {
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
                        candidates.add(new ScoredCbrCase<>(rc.cbrCase(), rc.caseId(), score, false, Map.of(), rc.storedAt(), rc.scope()));
                    }
                }
                candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
                return trimToTopK(candidates, query.topK());
            });
    }

    // ── SEMANTIC_ONLY ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSemanticOnlyAsync(
            CbrQuery query, Class<C> caseClass, String collection, Filter filter) {
        return Uni.createFrom().item(() -> embeddingModel.embed(TextSegment.from(query.problem())).content())
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .chain(queryEmbedding -> {
                SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
                    .setCollectionName(collection)
                    .addAllVector(queryEmbedding.vectorAsList())
                    .setVectorName(config.denseVectorName())
                    .setFilter(filter)
                    .setLimit(query.topK() * config.oversampleFactor())
                    .setWithPayload(WithPayloadSelectorFactory.enable(true));
                return toUni(collectionManager.client().searchAsync(searchBuilder.build()));
            })
            .map(scoredPoints -> {
                List<ScoredCbrCase<C>> candidates = new ArrayList<>(scoredPoints.size());
                for (ScoredPoint point : scoredPoints) {
                    try {
                        C cbrCase = (C) reconstructCase(point.getPayloadMap(), caseClass);
                        if (cbrCase != null && point.getScore() >= query.minSimilarity()) {
                            String caseId = extractString(point.getPayloadMap(), "caseId");
                            Instant storedAt = extractStoredAt(point.getPayloadMap());
                            candidates.add(new ScoredCbrCase<>(cbrCase, caseId, point.getScore(), false, Map.of(), storedAt, extractScope(point.getPayloadMap())));
                        }
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Failed to reconstruct case from point", e);
                    }
                }
                candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
                return trimToTopK(candidates, query.topK());
            });
    }

    // ── HYBRID (parallel search legs) ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveHybridAsync(
            CbrQuery query, Class<C> caseClass, String collection, Filter filter,
            CbrFeatureSchema schema) {

        Uni<Embedding> denseUni = embeddingModel != null
            ? Uni.createFrom().item(() -> embeddingModel.embed(TextSegment.from(query.problem())).content())
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            : Uni.createFrom().nullItem();

        Uni<Map<Integer, Float>> sparseUni = (sparseEmbedder != null && config.spladeEnabled() && query.problem() != null)
            ? Uni.createFrom().item(() -> sparseEmbedder.embed(query.problem()))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            : Uni.createFrom().nullItem();

        return Uni.combine().all().unis(denseUni, sparseUni).asTuple()
            .chain(embeddings -> {
                Embedding denseEmbedding = embeddings.getItem1();
                Map<Integer, Float> sparseEmbedding = embeddings.getItem2();

                Uni<List<ScoredPoint>> denseSearchUni = (denseEmbedding != null)
                    ? executeDenseSearchAsync(collection, filter, query, denseEmbedding)
                        .onFailure().recoverWithItem(e -> { LOG.log(Level.WARNING, "Dense search failed — skipping", (Throwable) e); return List.of(); })
                    : Uni.createFrom().item(List.of());

                Uni<List<ScoredPoint>> spladeSearchUni = (sparseEmbedding != null)
                    ? executeSpladeSearchAsync(collection, filter, query, sparseEmbedding)
                        .onFailure().recoverWithItem(e -> { LOG.log(Level.WARNING, "SPLADE search failed — skipping", (Throwable) e); return List.of(); })
                    : Uni.createFrom().item(List.of());

                Uni<List<ScoredPoint>> bm25SearchUni = (config.bm25Enabled() && query.problem() != null)
                    ? executeBm25SearchAsync(collection, filter, query)
                        .onFailure().recoverWithItem(e -> { LOG.log(Level.WARNING, "BM25 search failed — skipping", (Throwable) e); return List.of(); })
                    : Uni.createFrom().item(List.of());

                Uni<List<ScoredPoint>> filterSearchUni = executeFilterQueryAsync(collection, filter,
                    Math.max(query.topK(), config.overFetchLimit()))
                    .onFailure().recoverWithItem(e -> { LOG.log(Level.WARNING, "Filter query failed — skipping", (Throwable) e); return List.of(); });

                return Uni.combine().all().unis(denseSearchUni, spladeSearchUni, bm25SearchUni, filterSearchUni)
                    .with((densePoints, spladePoints, bm25Points, filterPoints) -> {
                        Map<String, ReconstructedCandidate<C>> candidateMap = new LinkedHashMap<>();
                        mergePoints(densePoints, candidateMap, caseClass, true);
                        mergePoints(spladePoints, candidateMap, caseClass, false);
                        mergePoints(bm25Points, candidateMap, caseClass, false);
                        mergePoints(filterPoints, candidateMap, caseClass, false);

                        List<ReconstructedCandidate<C>> allCandidates = new ArrayList<>(candidateMap.values());

                        EmbeddingTextSimilarity textSim = (schema != null && embeddingModel != null)
                            ? new EmbeddingTextSimilarity(embeddingModel) : null;
                        Map<String, LocalSimilarityFunction> overrides = schema != null
                            ? buildTextOverrides(schema, textSim) : Map.of();
                        if (textSim != null && !overrides.isEmpty()) {
                            textSim.precompute(collectSemanticTextValues(query, allCandidates, overrides.keySet()));
                        }

                        return fuseAndScore(query, allCandidates, overrides, schema,
                            densePoints, spladePoints, bm25Points);
                    });
            });
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> List<ScoredCbrCase<C>> fuseAndScore(
            CbrQuery query, List<ReconstructedCandidate<C>> allCandidates,
            Map<String, LocalSimilarityFunction> overrides, CbrFeatureSchema schema,
            List<ScoredPoint> densePoints, List<ScoredPoint> spladePoints,
            List<ScoredPoint> bm25Points) {

        record FusionEntry<C extends CbrCase>(String pointId, C cbrCase, double score,
                                              String caseId, Instant storedAt,
                                              io.casehub.platform.api.path.Path scope) {}

        List<FusionEntry<C>> featureLeg = new ArrayList<>();
        for (var rc : allCandidates) {
            double featureScore = CbrSimilarityScorer.score(
                query.features(), rc.cbrCase().features(), query.weights(), schema, overrides);
            featureLeg.add(new FusionEntry<>(rc.pointId(), rc.cbrCase(), featureScore, rc.caseId(), rc.storedAt(), rc.scope()));
        }

        List<FusionEntry<C>> denseLeg = new ArrayList<>();
        for (var rc : allCandidates) {
            if (rc.vectorScore() > 0) {
                denseLeg.add(new FusionEntry<>(rc.pointId(), rc.cbrCase(), rc.vectorScore(), rc.caseId(), rc.storedAt(), rc.scope()));
            }
        }

        Map<String, Float> spladeScores = new HashMap<>();
        for (var sp : spladePoints) spladeScores.put(sp.getId().getUuid(), sp.getScore());
        List<FusionEntry<C>> spladeLeg = new ArrayList<>();
        for (var rc : allCandidates) {
            Float score = spladeScores.get(rc.pointId());
            if (score != null && score > 0) {
                spladeLeg.add(new FusionEntry<>(rc.pointId(), rc.cbrCase(), score, rc.caseId(), rc.storedAt(), rc.scope()));
            }
        }

        Map<String, Float> bm25Scores = new HashMap<>();
        for (var sp : bm25Points) bm25Scores.put(sp.getId().getUuid(), sp.getScore());
        List<FusionEntry<C>> bm25Leg = new ArrayList<>();
        for (var rc : allCandidates) {
            Float score = bm25Scores.get(rc.pointId());
            if (score != null && score > 0) {
                bm25Leg.add(new FusionEntry<>(rc.pointId(), rc.cbrCase(), score, rc.caseId(), rc.storedAt(), rc.scope()));
            }
        }

        java.util.function.Function<FusionEntry<C>, String> idExtractor = FusionEntry::pointId;
        double featureWeight = 1.0 - query.vectorWeight();
        var featureScoredLeg = new ScoreFusion.ScoredLeg<>(featureLeg, FusionEntry::score, featureWeight);

        double rawDense = (!denseLeg.isEmpty()) ? config.ccWeights().dense() : 0.0;
        double rawSparse = (!spladeLeg.isEmpty()) ? config.ccWeights().sparse() : 0.0;
        double rawBm25 = (!bm25Leg.isEmpty()) ? config.ccWeights().bm25() : 0.0;
        double semanticTotal = rawDense + rawSparse + rawBm25;

        List<ScoreFusion.ScoredLeg<FusionEntry<C>>> legs = new ArrayList<>();
        legs.add(featureScoredLeg);
        if (!denseLeg.isEmpty() && semanticTotal > 0) {
            legs.add(new ScoreFusion.ScoredLeg<>(denseLeg, FusionEntry::score, query.vectorWeight() * rawDense / semanticTotal));
        }
        if (!spladeLeg.isEmpty() && semanticTotal > 0) {
            legs.add(new ScoreFusion.ScoredLeg<>(spladeLeg, FusionEntry::score, query.vectorWeight() * rawSparse / semanticTotal));
        }
        if (!bm25Leg.isEmpty() && semanticTotal > 0) {
            legs.add(new ScoreFusion.ScoredLeg<>(bm25Leg, FusionEntry::score, query.vectorWeight() * rawBm25 / semanticTotal));
        }

        List<ScoreFusion.FusedResult<FusionEntry<C>>> fused = switch (query.fusionStrategy()) {
            case RRF -> ScoreFusion.rrf(legs, idExtractor, query.topK(), 60);
            case CC -> ScoreFusion.convexCombination(legs, idExtractor, query.topK());
            case DBSF -> throw new UnsupportedOperationException("DBSF is not supported for CBR — use RRF or CC");
        };

        List<ScoredCbrCase<C>> results = new ArrayList<>(fused.size());
        for (var f : fused) {
            double score = Math.max(-1.0, Math.min(1.0, f.score()));
            if (query.fusionStrategy() == FusionStrategy.RRF || score >= query.minSimilarity()) {
                results.add(new ScoredCbrCase<>(f.item().cbrCase(), f.item().caseId(), score, false, Map.of(), f.item().storedAt(), f.item().scope()));
            }
        }
        return Collections.unmodifiableList(results);
    }

    // ── async search execution ───────────────────────────────────────────────

    private Uni<List<ScoredPoint>> executeDenseSearchAsync(String collection, Filter filter,
                                                          CbrQuery query, Embedding queryEmbedding) {
        SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
            .setCollectionName(collection)
            .addAllVector(queryEmbedding.vectorAsList())
            .setVectorName(config.denseVectorName())
            .setFilter(filter)
            .setLimit(query.topK() * config.oversampleFactor())
            .setWithPayload(WithPayloadSelectorFactory.enable(true));
        return toUni(collectionManager.client().searchAsync(searchBuilder.build()));
    }

    private Uni<List<ScoredPoint>> executeSpladeSearchAsync(String collection, Filter filter,
                                                            CbrQuery query,
                                                            Map<Integer, Float> sparseEmbedding) {
        List<Float> sparseValues = new ArrayList<>(sparseEmbedding.size());
        List<Integer> sparseIndices = new ArrayList<>(sparseEmbedding.size());
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
            .setWithPayload(WithPayloadSelectorFactory.enable(true))
            .build();
        return toUni(collectionManager.client().queryAsync(queryPoints));
    }

    private Uni<List<ScoredPoint>> executeBm25SearchAsync(String collection, Filter filter,
                                                          CbrQuery query) {
        String expandedQuery = CamelCaseExpander.expand(query.problem());
        int limit = config.bm25TopK() > 0 ? config.bm25TopK() : query.topK();
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
            .setWithPayload(WithPayloadSelectorFactory.enable(true))
            .build();
        return toUni(collectionManager.client().queryAsync(queryPoints));
    }

    private Uni<List<ScoredPoint>> executeFilterQueryAsync(String collection, Filter filter, int limit) {
        var scrollBuilder = io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
            .setCollectionName(collection)
            .setFilter(filter)
            .setLimit(limit)
            .setWithPayload(WithPayloadSelectorFactory.enable(true));

        return toUni(collectionManager.client().scrollAsync(scrollBuilder.build()))
            .map(response -> {
                List<ScoredPoint> results = new ArrayList<>(response.getResultCount());
                for (var retrieved : response.getResultList()) {
                    results.add(ScoredPoint.newBuilder()
                        .setId(retrieved.getId())
                        .putAllPayload(retrieved.getPayloadMap())
                        .setScore(1.0f)
                        .build());
                }
                return results;
            });
    }

    // ── erase ────────────────────────────────────────────────────────────────

    @Override
    public Uni<Integer> erase(EraseRequest request) {
        Uni<Integer> delegateUni = (delegate != null)
            ? Uni.createFrom().item(() -> delegate.erase(request))
            : Uni.createFrom().item(0);

        Filter.Builder builder = Filter.newBuilder()
            .addMust(ConditionFactory.matchKeyword("entityId", request.entityId()))
            .addMust(ConditionFactory.matchKeyword("domain", request.domain().name()))
            .addMust(ConditionFactory.matchKeyword("tenantId", request.tenantId()));
        if (request.caseId() != null) {
            builder.addMust(ConditionFactory.matchKeyword("caseId", request.caseId()));
        }
        Filter filter = builder.build();

        return delegateUni.chain(delegateCount ->
            eraseFromAllCollections(filter).map(qdrantCount ->
                delegate != null ? delegateCount : qdrantCount));
    }

    @Override
    public Uni<Integer> eraseEntity(String entityId, String tenantId) {
        Uni<Integer> delegateUni = (delegate != null)
            ? Uni.createFrom().item(() -> delegate.eraseEntity(entityId, tenantId))
            : Uni.createFrom().item(0);

        Filter filter = Filter.newBuilder()
            .addMust(ConditionFactory.matchKeyword("entityId", entityId))
            .addMust(ConditionFactory.matchKeyword("tenantId", tenantId))
            .build();

        return delegateUni.chain(delegateCount ->
            eraseFromAllCollections(filter).map(qdrantCount ->
                delegate != null ? delegateCount : qdrantCount));
    }

    private Uni<Integer> eraseFromAllCollections(Filter filter) {
        if (schemas.isEmpty()) {return Uni.createFrom().item(0);}
        return Multi.createFrom().iterable(schemas.keySet())
                    .onItem().transformToUniAndConcatenate(caseType -> {
                    String collection = collectionManager.collectionName(caseType);
                    return toUni(collectionManager.client().collectionExistsAsync(collection))
                                   .chain(exists -> {
                                       if (!exists) {return Uni.createFrom().item(0);}
                                       return Uni.createFrom().item(() -> collectionManager.deleteByFilter(collection, filter))
                                                 .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
                                   });
                })
                    .collect().asList()
                    .map(counts -> counts.stream().mapToInt(Integer::intValue).sum());}

    @Override
    public Uni<Integer> eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {
        java.util.Objects.requireNonNull(scope, "scope required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");

        return Multi.createFrom().iterable(schemas.keySet())
            .onItem().transformToUniAndConcatenate(caseType -> {
                String collection = collectionManager.collectionName(caseType);
                return toUni(collectionManager.client().collectionExistsAsync(collection))
                    .chain(exists -> {
                        if (!exists) return Uni.createFrom().item(0);

                        Filter tenantFilter = Filter.newBuilder()
                            .addMust(ConditionFactory.matchKeyword("tenantId", tenantId))
                            .build();

                        return toUni(collectionManager.client().scrollAsync(
                            io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                                .setCollectionName(collection)
                                .setFilter(tenantFilter)
                                .setLimit(100000)
                                .setWithPayload(WithPayloadSelectorFactory.include(
                                    List.of("scope", "entityId", "domain", "caseId")))
                                .build()))
                            .chain(scrollResult -> {
                                List<PointId> toDelete = new ArrayList<>();
                                for (var point : scrollResult.getResultList()) {
                                    Map<String, Value> payload = point.getPayloadMap();
                                    io.casehub.platform.api.path.Path storedScope = extractScope(payload);
                                    if (scope.segments().isEmpty()
                                        || storedScope.equals(scope)
                                        || scope.isAncestorOf(storedScope)) {
                                        toDelete.add(point.getId());
                                        if (delegate != null) {
                                            String eid = extractString(payload, "entityId");
                                            String dom = extractString(payload, "domain");
                                            String cid = extractString(payload, "caseId");
                                            if (eid != null && dom != null && cid != null) {
                                                delegate.erase(new EraseRequest(eid, new MemoryDomain(dom), tenantId, cid));
                                            }
                                        }
                                    }
                                }

                                if (toDelete.isEmpty()) return Uni.createFrom().item(0);
                                return toUni(collectionManager.client().deleteAsync(collection, toDelete))
                                    .replaceWith(toDelete.size());
                            });
                    });
            })
            .collect().asList()
            .map(counts -> counts.stream().mapToInt(Integer::intValue).sum());
    }

    // ── recordOutcome ────────────────────────────────────────────────────────

    @Override
    public Uni<Void> recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
        return Multi.createFrom().iterable(schemas.keySet())
            .onItem().transformToUniAndConcatenate(caseType -> {
                String collection = collectionManager.collectionName(caseType);
                return toUni(collectionManager.client().collectionExistsAsync(collection))
                    .chain(exists -> {
                        if (!exists) return Uni.createFrom().item(false);
                        UUID pointUuid = CbrPointBuilder.pointId(tenantId, caseType, caseId);
                        var pointId = PointIdFactory.id(pointUuid);
                        return toUni(collectionManager.client().retrieveAsync(collection, List.of(pointId), true, false, null))
                            .chain(points -> {
                                if (points.isEmpty()) return Uni.createFrom().item(false);
                                var payload = points.getFirst().getPayloadMap();

                                String existingLastOutcome = extractString(payload, "last_outcome_at");
                                if (existingLastOutcome != null) {
                                    Instant existingInstant = Instant.parse(existingLastOutcome);
                                    if (!outcome.observedAt().isAfter(existingInstant)) {
                                        return Uni.createFrom().item(true);
                                    }
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

                                return toUni(collectionManager.client().setPayloadAsync(
                                    collection, updates, (PointId) pointId, null, null, null))
                                    .replaceWith(true);
                            });
                    });
            })
            .collect().asList()
            .replaceWithVoid();
    }

    // ── supersede / reinstate ────────────────────────────────────────────────

    @Override
    public Uni<Void> supersede(String caseId, String tenantId, String supersedingCaseId, String reason) {
        java.util.Objects.requireNonNull(caseId, "caseId required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        return Multi.createFrom().iterable(schemas.keySet())
            .onItem().transformToUniAndConcatenate(caseType -> {
                String collection = collectionManager.collectionName(caseType);
                return toUni(collectionManager.client().collectionExistsAsync(collection))
                    .chain(exists -> {
                        if (!exists) return Uni.createFrom().item(false);
                        UUID pointUuid = CbrPointBuilder.pointId(tenantId, caseType, caseId);
                        var pointId = PointIdFactory.id(pointUuid);
                        return toUni(collectionManager.client().retrieveAsync(collection, List.of(pointId), true, false, null))
                            .chain(points -> {
                                if (points.isEmpty()) return Uni.createFrom().item(false);
                                var payload = points.getFirst().getPayloadMap();
                                Map<String, Value> updates = new HashMap<>();

                                Value existing = payload.get("_superseded_at");
                                if (existing != null && existing.hasIntegerValue() && existing.getIntegerValue() > 0) {
                                    if (supersedingCaseId != null) updates.put("_superseding_case_id", ValueFactory.value(supersedingCaseId));
                                    if (reason != null) updates.put("_supersession_reason", ValueFactory.value(reason));
                                } else {
                                    updates.put("_superseded_at", ValueFactory.value(Instant.now().toEpochMilli()));
                                    if (supersedingCaseId != null) updates.put("_superseding_case_id", ValueFactory.value(supersedingCaseId));
                                    if (reason != null) updates.put("_supersession_reason", ValueFactory.value(reason));
                                }

                                if (updates.isEmpty()) return Uni.createFrom().item(true);
                                return toUni(collectionManager.client().setPayloadAsync(
                                    collection, updates, (PointId) pointId, null, null, null))
                                    .replaceWith(true);
                            });
                    });
            })
            .collect().asList()
            .replaceWithVoid();
    }

    @Override
    public Uni<Void> reinstate(String caseId, String tenantId) {
        java.util.Objects.requireNonNull(caseId, "caseId required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        return Multi.createFrom().iterable(schemas.keySet())
            .onItem().transformToUniAndConcatenate(caseType -> {
                String collection = collectionManager.collectionName(caseType);
                return toUni(collectionManager.client().collectionExistsAsync(collection))
                    .chain(exists -> {
                        if (!exists) return Uni.createFrom().item(false);
                        UUID pointUuid = CbrPointBuilder.pointId(tenantId, caseType, caseId);
                        var pointId = PointIdFactory.id(pointUuid);
                        return toUni(collectionManager.client().retrieveAsync(collection, List.of(pointId), true, false, null))
                            .chain(points -> {
                                if (points.isEmpty()) return Uni.createFrom().item(false);
                                Map<String, Value> updates = new HashMap<>();
                                updates.put("_superseded_at", ValueFactory.value(0L));
                                return toUni(collectionManager.client().setPayloadAsync(
                                    collection, updates, (PointId) pointId, null, null, null))
                                    .chain(() -> toUni(collectionManager.client().deletePayloadAsync(
                                        collection,
                                        List.of("_superseded_at", "_superseding_case_id", "_supersession_reason"),
                                        (PointId) pointId, null, null, null)))
                                    .replaceWith(true);
                            });
                    });
            })
            .collect().asList()
            .replaceWithVoid();
    }

    // ── purge ────────────────────────────────────────────────────────────────

    @Override
    public Uni<Integer> purge(CbrRetentionPolicy policy) {
        List<String> targetTypes = policy.caseType() != null
            ? List.of(policy.caseType())
            : List.copyOf(schemas.keySet());

        return Multi.createFrom().iterable(targetTypes)
            .onItem().transformToUniAndConcatenate(caseType -> {
                String collection = collectionManager.collectionName(caseType);
                return toUni(collectionManager.client().collectionExistsAsync(collection))
                    .chain(exists -> {
                        if (!exists) return Uni.createFrom().item(0);
                        return purgeCollection(collection, policy);
                    });
            })
            .collect().asList()
            .map(counts -> counts.stream().mapToInt(Integer::intValue).sum());
    }

    private Uni<Integer> purgeCollection(String collection, CbrRetentionPolicy policy) {
        final Uni<Integer> ageUni;
        if (policy.maxAgeDays() != null) {
            long cutoffMillis = Instant.now().minus(java.time.Duration.ofDays(policy.maxAgeDays())).toEpochMilli();
            Filter ageFilter = Filter.newBuilder()
                                     .addMust(ConditionFactory.matchKeyword("tenantId", policy.tenantId()))
                                     .addMust(ConditionFactory.matchKeyword("domain", policy.domain().name()))
                                     .addMust(ConditionFactory.range("_stored_at",
                                                                     io.qdrant.client.grpc.Common.Range.newBuilder().setLt(cutoffMillis).build()))
                                     .build();
            ageUni = Uni.createFrom().item(() -> collectionManager.deleteByFilter(collection, ageFilter));
        } else {
            ageUni = Uni.createFrom().item(0);
        }

        final Uni<Integer> countUni;
        if (policy.maxCasesPerType() != null) {
            Filter scopeFilter = Filter.newBuilder()
                                       .addMust(ConditionFactory.matchKeyword("tenantId", policy.tenantId()))
                                       .addMust(ConditionFactory.matchKeyword("domain", policy.domain().name()))
                                       .build();
            countUni = toUni(collectionManager.client().scrollAsync(
                    io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                                                             .setCollectionName(collection)
                                                             .setFilter(scopeFilter)
                                                             .setLimit(100000)
                                                             .setWithPayload(WithPayloadSelectorFactory.include(List.of("_stored_at")))
                                                             .build()))
                               .chain(scrollResult -> {
                                   var points = new ArrayList<>(scrollResult.getResultList());
                                   if (points.size() <= policy.maxCasesPerType()) {return Uni.createFrom().item(0);}
                                   points.sort((a, b) -> {
                                       long aTime = a.getPayloadOrDefault("_stored_at", ValueFactory.value(0L)).getIntegerValue();
                                       long bTime = b.getPayloadOrDefault("_stored_at", ValueFactory.value(0L)).getIntegerValue();
                                       return Long.compare(bTime, aTime);
                                   });
                                   List<PointId> toDelete = new ArrayList<>();
                                   for (int i = policy.maxCasesPerType(); i < points.size(); i++) {
                                       toDelete.add(points.get(i).getId());
                                   }
                                   return toUni(collectionManager.client().deleteAsync(collection, toDelete))
                                                  .replaceWith(toDelete.size());
                               });
        } else {
            countUni = Uni.createFrom().item(0);
        }

        return ageUni.chain(ageCount -> countUni.map(countCount -> ageCount + countCount));}

    // ── shared helpers (pure CPU) ────────────────────────────────────────────

    private int vectorDimension() {
        return embeddingModel != null ? embeddingModel.dimension() : 0;
    }

    private Map<String, LocalSimilarityFunction> buildTextOverrides(
            CbrFeatureSchema schema, EmbeddingTextSimilarity textSim) {
        if (textSim == null) return Map.of();
        Map<String, LocalSimilarityFunction> overrides = new HashMap<>();
        for (FeatureField field : schema.fields()) {
            if (field instanceof FeatureField.Text t && t.semantic()) {
                overrides.put(field.name(), textSim);
            }
        }
        return overrides.isEmpty() ? Map.of() : Collections.unmodifiableMap(overrides);
    }

    private <C extends CbrCase> List<String> collectSemanticTextValues(
            CbrQuery query, List<ReconstructedCandidate<C>> candidates, Set<String> fieldNames) {
        List<String> texts = new ArrayList<>();
        for (String fieldName : fieldNames) {
            if (query.features().get(fieldName) instanceof FeatureValue.StringVal s) texts.add(s.value());
            for (var rc : candidates) {
                if (rc.cbrCase().features().get(fieldName) instanceof FeatureValue.StringVal s) texts.add(s.value());
            }
        }
        return texts;
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> void mergePoints(List<ScoredPoint> points,
                                                 Map<String, ReconstructedCandidate<C>> map,
                                                 Class<C> caseClass, boolean useDenseScore) {
        for (ScoredPoint point : points) {
            String pointId = point.getId().getUuid();
            if (map.containsKey(pointId)) continue;
            try {
                C cbrCase = (C) reconstructCase(point.getPayloadMap(), caseClass);
                if (cbrCase != null) {
                    String caseId = extractString(point.getPayloadMap(), "caseId");
                    Instant storedAt = extractStoredAt(point.getPayloadMap());
                    map.put(pointId, new ReconstructedCandidate<>(pointId, cbrCase,
                        useDenseScore ? point.getScore() : 0f, caseId, storedAt,
                        extractScope(point.getPayloadMap())));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to reconstruct case from point", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> List<ReconstructedCandidate<C>> reconstructAll(
            List<ScoredPoint> scoredPoints, Class<C> caseClass) {
        List<ReconstructedCandidate<C>> result = new ArrayList<>(scoredPoints.size());
        for (ScoredPoint point : scoredPoints) {
            try {
                C cbrCase = (C) reconstructCase(point.getPayloadMap(), caseClass);
                if (cbrCase != null) {
                    result.add(new ReconstructedCandidate<>(
                        point.getId().getUuid(), cbrCase, point.getScore(),
                        extractString(point.getPayloadMap(), "caseId"),
                        extractStoredAt(point.getPayloadMap()),
                        extractScope(point.getPayloadMap())));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to reconstruct case from point", e);
            }
        }
        return result;
    }

    private <C extends CbrCase> List<ScoredCbrCase<C>> trimToTopK(
            List<ScoredCbrCase<C>> candidates, int topK) {
        if (candidates.size() <= topK) return Collections.unmodifiableList(candidates);
        return Collections.unmodifiableList(candidates.subList(0, topK));
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
        Map<String, FeatureValue> features = Map.of();
        String featuresJson = extractString(payload, "_features_json");
        if (featuresJson != null) {
            try {
                features = CbrPointBuilder.fromRawMap(MAPPER.readValue(featuresJson, MAP_TYPE));
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
            var features = CbrPointBuilder.fromRawMap(MAPPER.readValue(featuresJson, MAP_TYPE));
            return new FeatureVectorCbrCase(problem, solution, outcome, confidence, features);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Corrupted _features_json in CBR point", e);
        }
    }

    private record ReconstructedCandidate<C extends CbrCase>(
        String pointId, C cbrCase, float vectorScore,
        String caseId, Instant storedAt,
        io.casehub.platform.api.path.Path scope) {}

    private record StoreContext(String memoryId, String collection, PointStruct point) {}
}
