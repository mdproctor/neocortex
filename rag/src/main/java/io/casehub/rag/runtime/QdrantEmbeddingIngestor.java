package io.casehub.rag.runtime;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.EmbeddingIngestor;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

public class QdrantEmbeddingIngestor implements EmbeddingIngestor {

    private static final Logger LOG = Logger.getLogger(QdrantEmbeddingIngestor.class.getName());

    private final QdrantClient client;
    private final EmbeddingModel embeddingModel;
    private final SparseEmbedder sparseEmbedder;
    private final TenancyStrategy tenancyStrategy;
    private final String denseVectorName;
    private final String sparseVectorName;
    private final TenantGuard tenantGuard;
    private final int batchSize;

    private final Set<String> knownCollections = ConcurrentHashMap.newKeySet();

    QdrantEmbeddingIngestor(
            QdrantClient client,
            EmbeddingModel embeddingModel,
            SparseEmbedder sparseEmbedder,
            TenancyStrategy tenancyStrategy,
            String denseVectorName,
            String sparseVectorName,
            TenantGuard tenantGuard,
            int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive, got: " + batchSize);
        }
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.sparseEmbedder = sparseEmbedder;
        this.tenancyStrategy = tenancyStrategy;
        this.denseVectorName = denseVectorName;
        this.sparseVectorName = sparseVectorName;
        this.tenantGuard = tenantGuard;
        this.batchSize = batchSize;
    }

    @Override
    public void ingest(CorpusRef corpus, List<ChunkInput> chunks) {
        tenantGuard.assertTenant(corpus.tenantId());
        if (chunks.isEmpty()) return;

        String collection = tenancyStrategy.collectionName(corpus);
        ensureCollection(collection);

        int[] chunkIndices = QdrantPointBuilder.computeChunkIndices(chunks);
        int effectiveBatchSize = Math.min(batchSize, chunks.size());
        int totalBatches = (chunks.size() + effectiveBatchSize - 1) / effectiveBatchSize;

        for (int batchNum = 0; batchNum < totalBatches; batchNum++) {
            int start = batchNum * effectiveBatchSize;
            int end = Math.min(start + effectiveBatchSize, chunks.size());
            List<ChunkInput> batch = chunks.subList(start, end);

            List<TextSegment> segments = new ArrayList<>(batch.size());
            List<String> texts = new ArrayList<>(batch.size());
            for (ChunkInput chunk : batch) {
                segments.add(TextSegment.from(chunk.content()));
                texts.add(chunk.content());
            }
            Response<List<Embedding>> denseResponse = embeddingModel.embedAll(segments);
            List<Embedding> denseEmbeddings = denseResponse.content();

            List<Map<Integer, Float>> sparseEmbeddings = sparseEmbedder != null
                ? sparseEmbedder.embedBatch(texts) : null;

            List<PointStruct> points = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                points.add(QdrantPointBuilder.buildPoint(batch.get(i), corpus,
                    denseEmbeddings.get(i),
                    sparseEmbeddings != null ? sparseEmbeddings.get(i) : null,
                    chunkIndices[start + i], denseVectorName, sparseVectorName));
            }

            try {
                client.upsertAsync(collection, points).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during upsert", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("Upsert failed", e.getCause());
            }

            final int logBatchNum = batchNum + 1;
            final int logEnd = end;
            LOG.fine(() -> "Ingested batch " + logBatchNum + "/" + totalBatches
                + " (" + logEnd + "/" + chunks.size() + " chunks)");
        }
    }

    @Override
    public void deleteDocument(CorpusRef corpus, String sourceDocumentId) {
        tenantGuard.assertTenant(corpus.tenantId());

        String collection = tenancyStrategy.collectionName(corpus);

        Filter.Builder filterBuilder = Filter.newBuilder()
            .addMust(ConditionFactory.matchKeyword("sourceDocumentId", sourceDocumentId));
        tenancyStrategy.tenantFilter(corpus)
            .ifPresent(tf -> tf.getMustList().forEach(filterBuilder::addMust));

        try {
            client.deleteAsync(collection, filterBuilder.build()).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during delete", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Delete failed", e.getCause());
        }
    }

    @Override
    public void deleteCorpus(CorpusRef corpus) {
        tenantGuard.assertTenant(corpus.tenantId());

        String collection = tenancyStrategy.collectionName(corpus);

        try {
            if (tenancyStrategy == TenancyStrategy.SEPARATE_COLLECTIONS) {
                client.deleteCollectionAsync(collection).get();
                knownCollections.remove(collection);
            } else {
                // SHARED: delete only this tenant's points
                var tenantFilter = tenancyStrategy.tenantFilter(corpus);
                if (tenantFilter.isPresent()) {
                    client.deleteAsync(collection, tenantFilter.get()).get();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during deleteCorpus", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("DeleteCorpus failed", e.getCause());
        }
    }

    @Override
    public List<String> listDocuments(CorpusRef corpus) {
        tenantGuard.assertTenant(corpus.tenantId());

        String collection = tenancyStrategy.collectionName(corpus);

        // Check if collection exists — return empty if not
        try {
            if (!client.collectionExistsAsync(collection).get()) {
                return List.of();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted checking collection existence", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to check collection existence", e.getCause());
        }

        Set<String> docIds = new LinkedHashSet<>();
        io.qdrant.client.grpc.Common.PointId offset = null;

        try {
            while (true) {
                ScrollPoints.Builder scrollBuilder = ScrollPoints.newBuilder()
                    .setCollectionName(collection)
                    .setLimit(100)
                    .setWithPayload(
                        io.qdrant.client.WithPayloadSelectorFactory.enable(true));

                tenancyStrategy.tenantFilter(corpus).ifPresent(scrollBuilder::setFilter);

                if (offset != null) {
                    scrollBuilder.setOffset(offset);
                }

                ScrollResponse response = client.scrollAsync(scrollBuilder.build()).get();

                for (RetrievedPoint point : response.getResultList()) {
                    Value docIdValue = point.getPayloadMap().get("sourceDocumentId");
                    if (docIdValue != null && docIdValue.hasStringValue()) {
                        docIds.add(docIdValue.getStringValue());
                    }
                }

                if (!response.hasNextPageOffset()) {
                    break;
                }
                offset = response.getNextPageOffset();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during listDocuments", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("ListDocuments failed", e.getCause());
        }

        return List.copyOf(docIds);
    }

    private void ensureCollection(String collection) {
        if (knownCollections.contains(collection)) {
            return;
        }

        try {
            if (client.collectionExistsAsync(collection).get()) {
                knownCollections.add(collection);
                return;
            }

            VectorParams denseParams = VectorParams.newBuilder()
                .setSize(embeddingModel.dimension())
                .setDistance(Distance.Cosine)
                .build();

            VectorParamsMap paramsMap = VectorParamsMap.newBuilder()
                .putMap(denseVectorName, denseParams)
                .build();

            CreateCollection.Builder createBuilder = CreateCollection.newBuilder()
                .setCollectionName(collection)
                .setVectorsConfig(VectorsConfig.newBuilder()
                    .setParamsMap(paramsMap)
                    .build());

            if (sparseEmbedder != null) {
                SparseVectorConfig sparseConfig = SparseVectorConfig.newBuilder()
                    .putMap(sparseVectorName, SparseVectorParams.getDefaultInstance())
                    .build();
                createBuilder.setSparseVectorsConfig(sparseConfig);
            }

            CreateCollection createRequest = createBuilder.build();

            client.createCollectionAsync(createRequest).get();
            knownCollections.add(collection);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during ensureCollection", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("ensureCollection failed", e.getCause());
        }
    }

}
