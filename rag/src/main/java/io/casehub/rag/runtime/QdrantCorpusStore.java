package io.casehub.rag.runtime;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.memory.MemoryPermissions;
import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.CorpusStore;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
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
import io.qdrant.client.grpc.Points.Vector;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public class QdrantCorpusStore implements CorpusStore {

    private final QdrantClient client;
    private final EmbeddingModel embeddingModel;
    private final SparseEmbedder sparseEmbedder;
    private final TenancyStrategy tenancyStrategy;
    private final String denseVectorName;
    private final String sparseVectorName;
    private final CurrentPrincipal currentPrincipal;

    private final Set<String> knownCollections = ConcurrentHashMap.newKeySet();

    public QdrantCorpusStore(
            QdrantClient client,
            EmbeddingModel embeddingModel,
            SparseEmbedder sparseEmbedder,
            TenancyStrategy tenancyStrategy,
            String denseVectorName,
            String sparseVectorName,
            CurrentPrincipal currentPrincipal) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.sparseEmbedder = sparseEmbedder;
        this.tenancyStrategy = tenancyStrategy;
        this.denseVectorName = denseVectorName;
        this.sparseVectorName = sparseVectorName;
        this.currentPrincipal = currentPrincipal;
    }

    @Override
    public void ingest(CorpusRef corpus, List<ChunkInput> chunks) {
        MemoryPermissions.assertTenant(corpus.tenantId(), currentPrincipal);

        String collection = tenancyStrategy.collectionName(corpus);
        ensureCollection(collection);

        // Batch embed dense
        List<TextSegment> segments = new ArrayList<>(chunks.size());
        List<String> texts = new ArrayList<>(chunks.size());
        for (ChunkInput chunk : chunks) {
            segments.add(TextSegment.from(chunk.content()));
            texts.add(chunk.content());
        }
        Response<List<Embedding>> denseResponse = embeddingModel.embedAll(segments);
        List<Embedding> denseEmbeddings = denseResponse.content();

        // Batch embed sparse
        List<Map<Integer, Float>> sparseEmbeddings = sparseEmbedder.embedBatch(texts);

        // Build points
        List<PointStruct> points = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            points.add(buildPoint(
                chunks.get(i), corpus,
                denseEmbeddings.get(i), sparseEmbeddings.get(i)));
        }

        // Upsert
        try {
            client.upsertAsync(collection, points).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during upsert", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Upsert failed", e.getCause());
        }
    }

    @Override
    public void deleteDocument(CorpusRef corpus, String sourceDocumentId) {
        MemoryPermissions.assertTenant(corpus.tenantId(), currentPrincipal);

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
        MemoryPermissions.assertTenant(corpus.tenantId(), currentPrincipal);

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
        MemoryPermissions.assertTenant(corpus.tenantId(), currentPrincipal);

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

            SparseVectorConfig sparseConfig = SparseVectorConfig.newBuilder()
                .putMap(sparseVectorName, SparseVectorParams.getDefaultInstance())
                .build();

            CreateCollection createRequest = CreateCollection.newBuilder()
                .setCollectionName(collection)
                .setVectorsConfig(VectorsConfig.newBuilder()
                    .setParamsMap(paramsMap)
                    .build())
                .setSparseVectorsConfig(sparseConfig)
                .build();

            client.createCollectionAsync(createRequest).get();
            knownCollections.add(collection);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during ensureCollection", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("ensureCollection failed", e.getCause());
        }
    }

    private PointStruct buildPoint(
            ChunkInput chunk, CorpusRef corpus,
            Embedding denseEmbedding, Map<Integer, Float> sparseMap) {

        // Dense vector
        Vector denseVector = VectorFactory.vector(denseEmbedding.vectorAsList());

        // Sparse vector: convert Map<Integer,Float> to indices + values lists
        List<Float> sparseValues = new ArrayList<>(sparseMap.size());
        List<Integer> sparseIndices = new ArrayList<>(sparseMap.size());
        for (Map.Entry<Integer, Float> entry : sparseMap.entrySet()) {
            sparseIndices.add(entry.getKey());
            sparseValues.add(entry.getValue());
        }
        Vector sparseVector = VectorFactory.vector(sparseValues, sparseIndices);

        // Named vectors
        Map<String, Vector> namedVectors = Map.of(
            denseVectorName, denseVector,
            sparseVectorName, sparseVector
        );

        // Payload
        Map<String, Value> payload = new java.util.HashMap<>();
        payload.put("content", ValueFactory.value(chunk.content()));
        payload.put("sourceDocumentId", ValueFactory.value(chunk.sourceDocumentId()));
        payload.put("tenantId", ValueFactory.value(corpus.tenantId()));
        for (Map.Entry<String, String> meta : chunk.metadata().entrySet()) {
            payload.put(meta.getKey(), ValueFactory.value(meta.getValue()));
        }

        return PointStruct.newBuilder()
            .setId(PointIdFactory.id(UUID.randomUUID()))
            .setVectors(VectorsFactory.namedVectors(namedVectors))
            .putAllPayload(payload)
            .build();
    }
}
