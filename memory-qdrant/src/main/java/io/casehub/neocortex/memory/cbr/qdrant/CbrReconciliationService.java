package io.casehub.neocortex.memory.cbr.qdrant;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.neocortex.memory.*;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScrollPoints;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CbrReconciliationService {

    private static final Logger LOG = Logger.getLogger(CbrReconciliationService.class.getName());
    private static final int DEFAULT_PAGE_SIZE = 100;

    private final CbrCollectionManager collectionManager;
    private final EmbeddingModel embeddingModel;
    private final QdrantCbrConfig config;
    private final CaseMemoryStore delegate;

    CbrReconciliationService(CbrCollectionManager collectionManager,
                              EmbeddingModel embeddingModel,
                              QdrantCbrConfig config,
                              CaseMemoryStore delegate) {
        this.collectionManager = collectionManager;
        this.embeddingModel = embeddingModel;
        this.config = config;
        this.delegate = delegate;
    }

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
                    reindexed++;
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to reindex memory " + entry.getValue().memoryId(), e);
                    errors++;
                }
            }
            if (!batch.isEmpty()) {
                try {
                    collectionManager.client().upsertAsync(collection, batch).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during batch upsert", e);
                } catch (ExecutionException e) {
                    throw new RuntimeException("Batch upsert failed", e.getCause());
                }
            }
        }

        return new ReconciliationResult(caseType, tenantId, orphansRemoved, reindexed, errors);
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
        var filter = io.qdrant.client.grpc.Common.Filter.newBuilder()
            .addMust(ConditionFactory.matchKeyword("tenantId", tenantId))
            .build();

        io.qdrant.client.grpc.Common.PointId nextPageOffset = null;
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
