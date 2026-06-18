package io.casehub.rag.runtime;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.ReactiveEmbeddingIngestor;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.Vector;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReactiveQdrantEmbeddingIngestor implements ReactiveEmbeddingIngestor {

    private final QdrantClient client;
    private final EmbeddingModel embeddingModel;
    private final SparseEmbedder sparseEmbedder;
    private final TenancyStrategy tenancyStrategy;
    private final String denseVectorName;
    private final String sparseVectorName;
    private final int denseDimension;
    private final TenantGuard tenantGuard;

    private final ConcurrentHashMap<String, Uni<Void>> ensuredCollections = new ConcurrentHashMap<>();

    ReactiveQdrantEmbeddingIngestor(
            QdrantClient client,
            EmbeddingModel embeddingModel,
            SparseEmbedder sparseEmbedder,
            TenancyStrategy tenancyStrategy,
            String denseVectorName,
            String sparseVectorName,
            int denseDimension,
            TenantGuard tenantGuard) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.sparseEmbedder = sparseEmbedder;
        this.tenancyStrategy = tenancyStrategy;
        this.denseVectorName = denseVectorName;
        this.sparseVectorName = sparseVectorName;
        this.denseDimension = denseDimension;
        this.tenantGuard = tenantGuard;
    }

    @Override
    public Uni<Void> ingest(CorpusRef corpus, List<ChunkInput> chunks) {
        return Uni.createFrom().deferred(() -> {
            tenantGuard.assertTenant(corpus.tenantId());
            String collection = tenancyStrategy.collectionName(corpus);

            return ensureCollection(collection)
            .chain(() -> Uni.createFrom().item(() -> {
                List<TextSegment> segments = new ArrayList<>(chunks.size());
                List<String> texts = new ArrayList<>(chunks.size());
                for (ChunkInput chunk : chunks) {
                    segments.add(TextSegment.from(chunk.content()));
                    texts.add(chunk.content());
                }
                Response<List<Embedding>> denseResponse = embeddingModel.embedAll(segments);
                List<Map<Integer, Float>> sparseEmbeddings = sparseEmbedder != null
                    ? sparseEmbedder.embedBatch(texts) : null;
                return new EmbeddingResult(denseResponse.content(), sparseEmbeddings);
            }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
            .chain(embeddings -> {
                Map<String, Integer> counters = new HashMap<>();
                List<PointStruct> points = new ArrayList<>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    ChunkInput chunk = chunks.get(i);
                    int chunkIndex = counters.merge(chunk.sourceDocumentId(), 0, Integer::sum);
                    counters.put(chunk.sourceDocumentId(), chunkIndex + 1);
                    points.add(buildPoint(chunk, corpus,
                        embeddings.dense.get(i),
                        embeddings.sparse != null ? embeddings.sparse.get(i) : null,
                        chunkIndex));
                }
                return QdrantFutures.toUni(client.upsertAsync(collection, points))
                    .replaceWithVoid();
            });
        });
    }

    @Override
    public Uni<Void> deleteDocument(CorpusRef corpus, String sourceDocumentId) {
        return Uni.createFrom().deferred(() -> {
            tenantGuard.assertTenant(corpus.tenantId());
            String collection = tenancyStrategy.collectionName(corpus);

            Filter.Builder filterBuilder = Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("sourceDocumentId", sourceDocumentId));
            tenancyStrategy.tenantFilter(corpus)
                .ifPresent(tf -> tf.getMustList().forEach(filterBuilder::addMust));

            return QdrantFutures.toUni(client.deleteAsync(collection, filterBuilder.build()))
                .replaceWithVoid();
        });
    }

    @Override
    public Uni<Void> deleteCorpus(CorpusRef corpus) {
        return Uni.createFrom().deferred(() -> {
            tenantGuard.assertTenant(corpus.tenantId());
            String collection = tenancyStrategy.collectionName(corpus);

            if (tenancyStrategy == TenancyStrategy.SEPARATE_COLLECTIONS) {
                return QdrantFutures.toUni(client.deleteCollectionAsync(collection))
                    .invoke(() -> ensuredCollections.remove(collection))
                    .replaceWithVoid();
            } else {
                Optional<Filter> tenantFilter = tenancyStrategy.tenantFilter(corpus);
                if (tenantFilter.isPresent()) {
                    return QdrantFutures.toUni(client.deleteAsync(collection, tenantFilter.get()))
                        .replaceWithVoid();
                }
                return Uni.createFrom().voidItem();
            }
        });
    }

    @Override
    public Uni<List<String>> listDocuments(CorpusRef corpus) {
        return Uni.createFrom().deferred(() -> {
            tenantGuard.assertTenant(corpus.tenantId());
            String collection = tenancyStrategy.collectionName(corpus);
            Optional<Filter> tenantFilter = tenancyStrategy.tenantFilter(corpus);

            return QdrantFutures.<Boolean>toUni(client.collectionExistsAsync(collection))
            .chain(exists -> {
                if (!exists) {
                    return Uni.createFrom().item(List.<String>of());
                }
                return scrollPage(collection, tenantFilter, null, new LinkedHashSet<>())
                    .map(List::copyOf);
            });
        });
    }

    private Uni<Void> ensureCollection(String collection) {
        return ensuredCollections.computeIfAbsent(collection, k ->
            QdrantFutures.<Boolean>toUni(client.collectionExistsAsync(k))
                .chain(exists -> {
                    if (exists) return Uni.createFrom().voidItem();
                    return QdrantFutures.toUni(
                        client.createCollectionAsync(buildCreateRequest(k)))
                        .replaceWithVoid();
                })
                .onFailure().invoke(() -> ensuredCollections.remove(k))
                .memoize().indefinitely()
        );
    }

    private Uni<Set<String>> scrollPage(String collection, Optional<Filter> tenantFilter,
            PointId offset, Set<String> accumulator) {
        ScrollPoints.Builder builder = ScrollPoints.newBuilder()
            .setCollectionName(collection)
            .setLimit(100)
            .setWithPayload(WithPayloadSelectorFactory.enable(true));
        tenantFilter.ifPresent(builder::setFilter);
        if (offset != null) {
            builder.setOffset(offset);
        }

        return QdrantFutures.toUni(client.scrollAsync(builder.build()))
            .flatMap(response -> {
                for (RetrievedPoint point : response.getResultList()) {
                    Value docId = point.getPayloadMap().get("sourceDocumentId");
                    if (docId != null && docId.hasStringValue()) {
                        accumulator.add(docId.getStringValue());
                    }
                }
                if (!response.hasNextPageOffset()) {
                    return Uni.createFrom().item(accumulator);
                }
                return scrollPage(collection, tenantFilter,
                    response.getNextPageOffset(), accumulator);
            });
    }

    private CreateCollection buildCreateRequest(String collection) {
        VectorParams denseParams = VectorParams.newBuilder()
            .setSize(denseDimension)
            .setDistance(Distance.Cosine)
            .build();
        VectorParamsMap paramsMap = VectorParamsMap.newBuilder()
            .putMap(denseVectorName, denseParams)
            .build();
        CreateCollection.Builder builder = CreateCollection.newBuilder()
            .setCollectionName(collection)
            .setVectorsConfig(VectorsConfig.newBuilder().setParamsMap(paramsMap).build());
        if (sparseEmbedder != null) {
            SparseVectorConfig sparseConfig = SparseVectorConfig.newBuilder()
                .putMap(sparseVectorName, SparseVectorParams.getDefaultInstance())
                .build();
            builder.setSparseVectorsConfig(sparseConfig);
        }
        return builder.build();
    }

    private PointStruct buildPoint(ChunkInput chunk, CorpusRef corpus,
            Embedding denseEmbedding, Map<Integer, Float> sparseMap,
            int chunkIndex) {
        // Deterministic point ID from sourceDocumentId + per-document chunk index
        String idInput = chunk.sourceDocumentId() + "#" + chunkIndex;
        UUID pointId = UUID.nameUUIDFromBytes(idInput.getBytes(StandardCharsets.UTF_8));

        Vector denseVector = VectorFactory.vector(denseEmbedding.vectorAsList());
        Map<String, Vector> namedVectors;
        if (sparseMap != null) {
            List<Float> sparseValues = new ArrayList<>(sparseMap.size());
            List<Integer> sparseIndices = new ArrayList<>(sparseMap.size());
            for (Map.Entry<Integer, Float> entry : sparseMap.entrySet()) {
                sparseIndices.add(entry.getKey());
                sparseValues.add(entry.getValue());
            }
            Vector sparseVector = VectorFactory.vector(sparseValues, sparseIndices);
            namedVectors = Map.of(
                denseVectorName, denseVector,
                sparseVectorName, sparseVector
            );
        } else {
            namedVectors = Map.of(denseVectorName, denseVector);
        }
        Map<String, Value> payload = new HashMap<>();
        payload.put("content", ValueFactory.value(chunk.content()));
        payload.put("sourceDocumentId", ValueFactory.value(chunk.sourceDocumentId()));
        payload.put("tenantId", ValueFactory.value(corpus.tenantId()));
        for (Map.Entry<String, String> meta : chunk.metadata().entrySet()) {
            payload.put(meta.getKey(), ValueFactory.value(meta.getValue()));
        }
        return PointStruct.newBuilder()
            .setId(PointIdFactory.id(pointId))
            .setVectors(VectorsFactory.namedVectors(namedVectors))
            .putAllPayload(payload)
            .build();
    }

    private record EmbeddingResult(List<Embedding> dense, List<Map<Integer, Float>> sparse) {}
}
