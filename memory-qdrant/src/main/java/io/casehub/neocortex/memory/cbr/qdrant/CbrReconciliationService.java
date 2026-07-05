package io.casehub.neocortex.memory.cbr.qdrant;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
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
    private final QdrantCbrConfig config;
    private final CaseMemoryStore delegate;
    private final MeterRegistry meterRegistry;

    @Inject
    CbrReconciliationService(CbrCollectionManager collectionManager,
                              Instance<EmbeddingModel> embeddingModelInstance,
                              QdrantCbrConfig config,
                              Instance<CaseMemoryStore> delegateInstance,
                              Instance<MeterRegistry> meterRegistryInstance) {
        this.collectionManager = collectionManager;
        this.embeddingModel = embeddingModelInstance.isResolvable() ? embeddingModelInstance.get() : null;
        this.config = config;
        this.delegate = delegateInstance.isResolvable() ? delegateInstance.get() : null;
        this.meterRegistry = meterRegistryInstance.isResolvable() ? meterRegistryInstance.get() : null;
    }

    CbrReconciliationService(CbrCollectionManager collectionManager,
                              EmbeddingModel embeddingModel,
                              QdrantCbrConfig config,
                              CaseMemoryStore delegate,
                              MeterRegistry meterRegistry) {
        this.collectionManager = collectionManager;
        this.embeddingModel = embeddingModel;
        this.config = config;
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
    }

    @Timed(value = "casehub.cbr.reconciliation", histogram = true,
           extraTags = {"operation", "reconcile"})
    public ReconciliationResult reconcile(String caseType, String tenantId) {
        if (delegate == null) {
            return new ReconciliationResult(caseType, tenantId, 0, 0, 0);
        }
        if (!delegate.capabilities().contains(MemoryCapability.SCAN)) {
            LOG.info("Reconciliation skipped — delegate does not support SCAN");
            return new ReconciliationResult(caseType, tenantId, 0, 0, 0);
        }

        // Step 1: Build delegate index
        Map<UUID, Memory> delegateIndex = buildDelegateIndex(caseType, tenantId);

        // Step 2: Qdrant intersection — find orphans and consistent entries
        int orphansRemoved = 0;
        String collection = collectionManager.collectionName(caseType);
        boolean collectionExists;
        try {
            collectionExists = collectionManager.client().collectionExistsAsync(collection).get();
            if (collectionExists) {
                orphansRemoved = intersectWithQdrant(collection, tenantId, delegateIndex);
            } else {
                // Collection missing — invalidate cache so ensureCollection recreates it
                collectionManager.invalidateCollection(caseType);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during reconciliation", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Reconciliation failed", e.getCause());
        }

        // Step 3: Reindex remaining (missing from Qdrant)
        int reindexed = 0;
        int errors = 0;
        if (!delegateIndex.isEmpty()) {
            int vectorDim = embeddingModel != null ? embeddingModel.dimension() : 0;
            collectionManager.ensureCollection(caseType, vectorDim);

            List<PointStruct> batch = new ArrayList<>();
            for (var entry : delegateIndex.entrySet()) {
                try {
                    Memory memory = entry.getValue();
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

                    PointStruct point = CbrPointBuilder.buildPoint(
                        cbrCase, caseType, memory.entityId(), memory.domain().name(),
                        memory.tenantId(), memory.caseId(), embedding, config.denseVectorName());
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

        // Record metrics
        if (meterRegistry != null) {
            meterRegistry.counter("casehub.cbr.reconciliation.orphans", "caseType", caseType).increment(orphansRemoved);
            meterRegistry.counter("casehub.cbr.reconciliation.reindexed", "caseType", caseType).increment(reindexed);
            meterRegistry.counter("casehub.cbr.reconciliation.errors", "caseType", caseType).increment(errors);
        }

        return new ReconciliationResult(caseType, tenantId, orphansRemoved, reindexed, errors);
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
                results.add(new ReconciliationResult(caseType, tenantId, 0, 0, 1));
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
}
