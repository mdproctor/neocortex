package io.casehub.neocortex.memory.cbr.qdrant;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.neocortex.fusion.CamelCaseExpander;
import io.casehub.neocortex.inference.splade.SparseEmbedder;
import io.casehub.neocortex.memory.*;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScrollPoints;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class CbrReconciliationService {

    private static final Logger LOG = Logger.getLogger(CbrReconciliationService.class.getName());
    private static final int DEFAULT_PAGE_SIZE = 100;

    private final CbrCollectionManager collectionManager;
    private final EmbeddingModel embeddingModel;
    private final SparseEmbedder sparseEmbedder;
    private final QdrantCbrConfig config;
    private final CaseMemoryStore delegate;
    private final MeterRegistry meterRegistry;

    @Inject
    CbrReconciliationService(CbrCollectionManager collectionManager,
                              Instance<EmbeddingModel> embeddingModelInstance,
                              QdrantCbrConfig config,
                              Instance<CaseMemoryStore> delegateInstance,
                              Instance<MeterRegistry> meterRegistryInstance,
                              Instance<SparseEmbedder> sparseEmbedderInstance) {
        this.collectionManager = collectionManager;
        this.embeddingModel = embeddingModelInstance.isResolvable() ? embeddingModelInstance.get() : null;
        this.config = config;
        this.delegate = delegateInstance.isResolvable() ? delegateInstance.get() : null;
        this.meterRegistry = meterRegistryInstance.isResolvable() ? meterRegistryInstance.get() : null;
        this.sparseEmbedder = sparseEmbedderInstance.isResolvable() ? sparseEmbedderInstance.get() : null;
    }

    CbrReconciliationService(CbrCollectionManager collectionManager,
                              EmbeddingModel embeddingModel,
                              QdrantCbrConfig config,
                              CaseMemoryStore delegate,
                              MeterRegistry meterRegistry) {
        this(collectionManager, embeddingModel, config, delegate, meterRegistry, null);
    }

    CbrReconciliationService(CbrCollectionManager collectionManager,
                              EmbeddingModel embeddingModel,
                              QdrantCbrConfig config,
                              CaseMemoryStore delegate,
                              MeterRegistry meterRegistry,
                              SparseEmbedder sparseEmbedder) {
        this.collectionManager = collectionManager;
        this.embeddingModel = embeddingModel;
        this.config = config;
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        this.sparseEmbedder = sparseEmbedder;
    }

    @Timed(value = "casehub.cbr.reconciliation", histogram = true,
           extraTags = {"operation", "reconcile"})
    public ReconciliationResult reconcile(String caseType, String tenantId) {
        if (delegate == null) {
            return new ReconciliationResult(caseType, tenantId, 0, 0, 0, 0);
        }
        if (!delegate.capabilities().contains(MemoryCapability.SCAN)) {
            LOG.info("Reconciliation skipped — delegate does not support SCAN");
            return new ReconciliationResult(caseType, tenantId, 0, 0, 0, 0);
        }

        // Phase 1: Build delegate index
        Map<UUID, Memory> delegateIndex = buildDelegateIndex(caseType, tenantId);

        // Phase 2a: Qdrant intersection — find orphans and consistent entries
        int     orphansRemoved = 0;
        String  collection     = collectionManager.collectionName(caseType);
        boolean collectionExists;
        try {
            collectionExists = collectionManager.client().collectionExistsAsync(collection).get();
            if (collectionExists) {
                orphansRemoved = intersectWithQdrant(collection, tenantId, delegateIndex);
            } else {
                collectionManager.invalidateCollection(caseType);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during reconciliation", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Reconciliation failed", e.getCause());
        }

        // Phase 2b: Reindex remaining (missing from Qdrant)
        int reindexed = 0;
        int errors    = 0;
        if (!delegateIndex.isEmpty()) {
            int vectorDim = embeddingModel != null ? embeddingModel.dimension() : 0;
            collectionManager.ensureCollection(caseType, vectorDim);

            List<PointStruct> batch = new ArrayList<>();
            for (var entry : delegateIndex.entrySet()) {
                try {
                    Memory            memory       = entry.getValue();
                    Optional<CbrCase> maybeCbrCase = CbrMemoryDeserializer.deserialize(memory);
                    if (maybeCbrCase.isEmpty()) {
                        LOG.warning("Skipping undeserializable memory " + memory.memoryId());
                        errors++;
                        continue;
                    }
                    CbrCase cbrCase = maybeCbrCase.get();

                    Embedding embedding = null;
                    if (embeddingModel != null) {
                        embedding = embeddingModel.embed(TextSegment.from(cbrCase.problem())).content();
                    }

                    Map<Integer, Float> sparseEmbedding = null;
                    if (sparseEmbedder != null && config.spladeEnabled()) {
                        sparseEmbedding = sparseEmbedder.embed(cbrCase.problem());
                    }
                    String bm25Text = config.bm25Enabled() ? CamelCaseExpander.expand(cbrCase.problem()) : null;

                    PointStruct point = CbrPointBuilder.buildPoint(
                            cbrCase, caseType, memory.entityId(), memory.domain().name(),
                            memory.tenantId(), memory.caseId(), embedding, config.denseVectorName(),
                            sparseEmbedding, config.spladeVectorName(),
                            bm25Text, config.bm25VectorName(), config.bm25Model());
                    batch.add(point);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to reindex memory " + entry.getValue().memoryId(), e);
                    errors++;
                }
            }
            if (!batch.isEmpty()) {
                try {
                    for (int i = 0; i < batch.size(); i += DEFAULT_PAGE_SIZE) {
                        List<PointStruct> chunk = batch.subList(i, Math.min(i + DEFAULT_PAGE_SIZE, batch.size()));
                        collectionManager.client().upsertAsync(collection, chunk).get();
                        reindexed += chunk.size();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during batch upsert", e);
                } catch (ExecutionException e) {
                    throw new RuntimeException("Batch upsert failed", e.getCause());
                }
            }
        }

        // Phase 3: Vector enrichment — backfill SPLADE/BM25 vectors on existing points
        int enriched = enrichSparseVectors(collection, tenantId, caseType);

        // Record metrics
        if (meterRegistry != null) {
            meterRegistry.counter("casehub.cbr.reconciliation.orphans", "caseType", caseType).increment(orphansRemoved);
            meterRegistry.counter("casehub.cbr.reconciliation.reindexed", "caseType", caseType).increment(reindexed);
            meterRegistry.counter("casehub.cbr.reconciliation.enriched", "caseType", caseType).increment(enriched);
            meterRegistry.counter("casehub.cbr.reconciliation.errors", "caseType", caseType).increment(errors);
        }

        return new ReconciliationResult(caseType, tenantId, orphansRemoved, reindexed, enriched, errors);
    }

    public Set<String> discoverTenants(String caseType) {
        if (delegate == null) {
            throw new IllegalStateException("No delegate configured — tenant discovery unavailable");
        }
        delegate.requireCapability(MemoryCapability.DISCOVER_TENANTS);
        return delegate.discoverTenants(CbrAttributeKeys.CBR_CASE_TYPE, caseType);
    }

    @Timed(value = "casehub.cbr.reconciliation", histogram = true,
           extraTags = {"operation", "reconcileAll"})
    public List<ReconciliationResult> reconcileAll(String caseType, Set<String> tenantIds) {
        List<ReconciliationResult> results = new ArrayList<>();
        for (String tenantId : tenantIds) {
            try {
                results.add(reconcile(caseType, tenantId));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Reconciliation failed for tenant " + tenantId, e);
                results.add(new ReconciliationResult(caseType, tenantId, 0, 0, 0, 1));
            }
        }
        return results;
    }

    public List<ReconciliationResult> reconcileAll(String caseType) {
        Set<String> tenants = discoverTenants(caseType);
        if (tenants.isEmpty()) {
            LOG.info("No tenants discovered for caseType=" + caseType);
            return List.of();
        }
        return reconcileAll(caseType, tenants);
    }

    private Map<UUID, Memory> buildDelegateIndex(String caseType, String tenantId) {
        Map<UUID, Memory> index = new LinkedHashMap<>();
        String cursor = null;
        while (true) {
            var request = new MemoryScanRequest(tenantId, null,
                CbrAttributeKeys.CBR_CASE_TYPE, caseType, DEFAULT_PAGE_SIZE, cursor);
            List<Memory> page = delegate.scan(request);
            for (Memory m : page) {
                if (m.caseId() != null) {
                    UUID pointId = CbrPointBuilder.pointId(tenantId, caseType, m.caseId());
                    index.put(pointId, m);
                }
            }
            if (page.size() < DEFAULT_PAGE_SIZE) break;
            cursor = page.getLast().memoryId();
        }
        return index;
    }

    private int intersectWithQdrant(String collection, String tenantId,
                                     Map<UUID, Memory> delegateIndex)
            throws InterruptedException, ExecutionException {
        int orphansRemoved = 0;
        var filter = Filter.newBuilder()
            .addMust(ConditionFactory.matchKeyword("tenantId", tenantId))
            .build();

        PointId nextPageOffset = null;
        boolean hasMore = true;

        while (hasMore) {
            var scrollBuilder = ScrollPoints.newBuilder()
                .setCollectionName(collection)
                .setFilter(filter)
                .setLimit(DEFAULT_PAGE_SIZE)
                .setWithPayload(WithPayloadSelectorFactory.enable(false));

            if (nextPageOffset != null) {
                scrollBuilder.setOffset(nextPageOffset);
            }

            var response = collectionManager.client().scrollAsync(scrollBuilder.build()).get();
            List<UUID> orphanIds = new ArrayList<>();

            for (var point : response.getResultList()) {
                UUID pointUuid = UUID.fromString(point.getId().getUuid());
                if (delegateIndex.containsKey(pointUuid)) {
                    delegateIndex.remove(pointUuid);
                } else {
                    orphanIds.add(pointUuid);
                }
            }

            if (!orphanIds.isEmpty()) {
                var pointIds = orphanIds.stream()
                    .map(PointIdFactory::id)
                    .toList();
                collectionManager.client().deleteAsync(collection, pointIds).get();
                orphansRemoved += orphanIds.size();
            }

            hasMore = response.hasNextPageOffset();
            if (hasMore) {
                nextPageOffset = response.getNextPageOffset();
            }
        }
        return orphansRemoved;
    }

    private int enrichSparseVectors(String collection, String tenantId, String caseType) {
        boolean needsSplade = config.spladeEnabled() && sparseEmbedder != null;
        boolean needsBm25 = config.bm25Enabled();
        if (!needsSplade && !needsBm25) {
            return 0;
        }

        try {
            if (!collectionManager.client().collectionExistsAsync(collection).get()) {
                return 0;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during enrichment check", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Enrichment check failed", e.getCause());
        }

        var filter = Filter.newBuilder()
            .addMust(ConditionFactory.matchKeyword("tenantId", tenantId))
            .build();

        int enriched = 0;
        PointId nextPageOffset = null;
        boolean hasMore = true;

        try {
            while (hasMore) {
                var scrollBuilder = ScrollPoints.newBuilder()
                    .setCollectionName(collection)
                    .setFilter(filter)
                    .setLimit(DEFAULT_PAGE_SIZE)
                    .setWithPayload(WithPayloadSelectorFactory.enable(true))
                    .setWithVectors(io.qdrant.client.grpc.Points.WithVectorsSelector.newBuilder()
                        .setEnable(true).build());

                if (nextPageOffset != null) {
                    scrollBuilder.setOffset(nextPageOffset);
                }

                var response = collectionManager.client().scrollAsync(scrollBuilder.build()).get();
                List<io.qdrant.client.grpc.Points.PointVectors> enrichBatch = new ArrayList<>();

                for (var point : response.getResultList()) {
                    var existingVectorNames = point.getVectors().getVectors().getVectorsMap().keySet();
                    String problem = point.getPayloadMap().containsKey("problem")
                        ? point.getPayloadMap().get("problem").getStringValue() : null;
                    if (problem == null || problem.isEmpty()) continue;

                    Map<String, io.qdrant.client.grpc.Points.Vector> newVectors = new HashMap<>();

                    if (needsSplade && !existingVectorNames.contains(config.spladeVectorName())) {
                        Map<Integer, Float> sparse = sparseEmbedder.embed(problem);
                        List<Float> vals = new ArrayList<>(sparse.size());
                        List<Integer> idxs = new ArrayList<>(sparse.size());
                        for (var entry : sparse.entrySet()) {
                            idxs.add(entry.getKey());
                            vals.add(entry.getValue());
                        }
                        newVectors.put(config.spladeVectorName(),
                            io.qdrant.client.VectorFactory.vector(vals, idxs));
                    }

                    if (needsBm25 && !existingVectorNames.contains(config.bm25VectorName())) {
                        String expanded = CamelCaseExpander.expand(problem);
                        newVectors.put(config.bm25VectorName(),
                            io.qdrant.client.VectorFactory.vector(
                                io.qdrant.client.grpc.Points.Document.newBuilder()
                                    .setText(expanded)
                                    .setModel(config.bm25Model())
                                    .build()));
                    }

                    if (!newVectors.isEmpty()) {
                        enrichBatch.add(io.qdrant.client.grpc.Points.PointVectors.newBuilder()
                            .setId(point.getId())
                            .setVectors(io.qdrant.client.VectorsFactory.namedVectors(newVectors))
                            .build());
                    }
                }

                if (!enrichBatch.isEmpty()) {
                    for (int i = 0; i < enrichBatch.size(); i += DEFAULT_PAGE_SIZE) {
                        var chunk = enrichBatch.subList(i,
                            Math.min(i + DEFAULT_PAGE_SIZE, enrichBatch.size()));
                        collectionManager.client().updateVectorsAsync(collection, chunk).get();
                        enriched += chunk.size();
                    }
                }

                hasMore = response.hasNextPageOffset();
                if (hasMore) {
                    nextPageOffset = response.getNextPageOffset();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during vector enrichment", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Vector enrichment failed", e.getCause());
        }

        if (enriched > 0) {
            LOG.info("Enriched " + enriched + " points with sparse vectors in "
                + collection + " for tenant " + tenantId);
        }
        return enriched;
    }
}
