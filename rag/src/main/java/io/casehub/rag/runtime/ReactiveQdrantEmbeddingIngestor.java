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
import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadIndexParams;
import io.qdrant.client.grpc.Collections.PayloadSchemaInfo;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.TextIndexParams;
import io.qdrant.client.grpc.Collections.TokenizerType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class ReactiveQdrantEmbeddingIngestor implements ReactiveEmbeddingIngestor {

    private static final Logger LOG = Logger.getLogger(ReactiveQdrantEmbeddingIngestor.class.getName());

    private final QdrantClient client;
    private final EmbeddingModel embeddingModel;
    private final SparseEmbedder sparseEmbedder;
    private final TenancyStrategy tenancyStrategy;
    private final String denseVectorName;
    private final String sparseVectorName;
    private final int denseDimension;
    private final TenantGuard tenantGuard;
    private final int batchSize;
    private final DenseQuantization quantizationType;
    private final boolean alwaysRam;

    private final ConcurrentHashMap<String, Uni<Void>> ensuredCollections = new ConcurrentHashMap<>();

    ReactiveQdrantEmbeddingIngestor(
            QdrantClient client,
            EmbeddingModel embeddingModel,
            SparseEmbedder sparseEmbedder,
            TenancyStrategy tenancyStrategy,
            String denseVectorName,
            String sparseVectorName,
            TenantGuard tenantGuard,
            int batchSize,
            DenseQuantization quantizationType,
            boolean alwaysRam) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive, got: " + batchSize);
        }
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.sparseEmbedder = sparseEmbedder;
        this.tenancyStrategy = tenancyStrategy;
        this.denseVectorName = denseVectorName;
        this.sparseVectorName = sparseVectorName;
        this.denseDimension = embeddingModel.dimension();
        this.tenantGuard = tenantGuard;
        this.batchSize = batchSize;
        this.quantizationType = quantizationType;
        this.alwaysRam = alwaysRam;
    }

    @Override
    public Uni<Void> ingest(CorpusRef corpus, List<ChunkInput> chunks) {
        return Uni.createFrom().deferred(() -> {
            tenantGuard.assertTenant(corpus.tenantId());
            if (chunks.isEmpty()) return Uni.createFrom().voidItem();

            String collection = tenancyStrategy.collectionName(corpus);
            int[] chunkIndices = QdrantPointBuilder.computeChunkIndices(chunks);
            int effectiveBatchSize = Math.min(batchSize, chunks.size());
            int totalBatches = (chunks.size() + effectiveBatchSize - 1) / effectiveBatchSize;

            List<int[]> batchRanges = new ArrayList<>(totalBatches);
            for (int b = 0; b < totalBatches; b++) {
                int start = b * effectiveBatchSize;
                int end = Math.min(start + effectiveBatchSize, chunks.size());
                batchRanges.add(new int[]{b, start, end});
            }

            return ensureCollection(collection)
                .chain(() -> Multi.createFrom().iterable(batchRanges)
                    .onItem().transformToUniAndConcatenate(range -> {
                        int batchNum = range[0];
                        int start = range[1];
                        int end = range[2];
                        List<ChunkInput> batch = chunks.subList(start, end);

                        return Uni.createFrom().item(() -> {
                            List<TextSegment> segments = new ArrayList<>(batch.size());
                            List<String> texts = new ArrayList<>(batch.size());
                            for (ChunkInput chunk : batch) {
                                segments.add(TextSegment.from(chunk.content()));
                                texts.add(chunk.content());
                            }
                            Response<List<Embedding>> denseResponse = embeddingModel.embedAll(segments);
                            List<Map<Integer, Float>> sparseEmbeddings = sparseEmbedder != null
                                ? sparseEmbedder.embedBatch(texts) : null;
                            return new EmbeddingResult(denseResponse.content(), sparseEmbeddings);
                        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                        .chain(embeddings -> {
                            List<PointStruct> points = new ArrayList<>(batch.size());
                            for (int i = 0; i < batch.size(); i++) {
                                points.add(QdrantPointBuilder.buildPoint(batch.get(i), corpus,
                                    embeddings.dense().get(i),
                                    embeddings.sparse() != null ? embeddings.sparse().get(i) : null,
                                    chunkIndices[start + i], denseVectorName, sparseVectorName));
                            }
                            return QdrantFutures.toUni(client.upsertAsync(collection, points))
                                .invoke(() -> LOG.fine(() -> "Ingested batch " + (batchNum + 1) + "/" + totalBatches
                                    + " (" + end + "/" + chunks.size() + " chunks)"))
                                .replaceWithVoid();
                        });
                    })
                    .collect().last()
                    .replaceWithVoid());
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
                    if (exists) {
                        return QdrantFutures.toUni(client.getCollectionInfoAsync(k))
                            .chain(info -> {
                                int existingDim = (int) info.getConfig().getParams()
                                    .getVectorsConfig().getParamsMap().getMapMap()
                                    .get(denseVectorName).getSize();
                                if (existingDim != denseDimension) {
                                    throw new IllegalStateException(
                                        "Configured embedding dimension (" + denseDimension
                                            + ") does not match existing collection dimension ("
                                            + existingDim + ") for collection '" + k
                                            + "'. Re-index the collection or adjust matryoshka.dimension.");
                                }
                                return ensurePayloadIndexes(k, info.getPayloadSchemaMap());
                            });
                    }
                    return QdrantFutures.toUni(
                        client.createCollectionAsync(buildCreateRequest(k)))
                        .chain(() -> ensurePayloadIndexes(k, Map.of()));
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
        VectorParams.Builder denseParamsBuilder = VectorParams.newBuilder()
            .setSize(denseDimension)
            .setDistance(Distance.Cosine);

        if (quantizationType == DenseQuantization.BINARY) {
            denseParamsBuilder.setQuantizationConfig(
                io.qdrant.client.grpc.Collections.QuantizationConfig.newBuilder()
                    .setBinary(io.qdrant.client.grpc.Collections.BinaryQuantization.newBuilder()
                        .setAlwaysRam(alwaysRam)
                        .build())
                    .build());
        } else if (quantizationType == DenseQuantization.SCALAR) {
            denseParamsBuilder.setQuantizationConfig(
                io.qdrant.client.grpc.Collections.QuantizationConfig.newBuilder()
                    .setScalar(io.qdrant.client.grpc.Collections.ScalarQuantization.newBuilder()
                        .setType(io.qdrant.client.grpc.Collections.QuantizationType.Int8)
                        .setAlwaysRam(alwaysRam)
                        .build())
                    .build());
        }

        VectorParams denseParams = denseParamsBuilder.build();

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

    private Uni<Void> ensurePayloadIndexes(String collection,
            Map<String, PayloadSchemaInfo> existingSchema) {
        checkIndexType(existingSchema, "content", PayloadSchemaType.Text, collection);
        checkIndexType(existingSchema, "sourceDocumentId", PayloadSchemaType.Keyword, collection);
        checkIndexType(existingSchema, "tenantId", PayloadSchemaType.Keyword, collection);

        List<Uni<Void>> pending = new ArrayList<>();

        if (!existingSchema.containsKey("content")) {
            PayloadIndexParams textParams = PayloadIndexParams.newBuilder()
                .setTextIndexParams(TextIndexParams.newBuilder()
                    .setTokenizer(TokenizerType.Word)
                    .setLowercase(true)
                    .setMinTokenLen(2)
                    .setMaxTokenLen(40)
                    .build())
                .build();
            pending.add(QdrantFutures.toUni(
                client.createPayloadIndexAsync(collection, "content",
                    PayloadSchemaType.Text, textParams, true, null, null))
                .replaceWithVoid());
        }
        if (!existingSchema.containsKey("sourceDocumentId")) {
            pending.add(QdrantFutures.toUni(
                client.createPayloadIndexAsync(collection, "sourceDocumentId",
                    PayloadSchemaType.Keyword, null, true, null, null))
                .replaceWithVoid());
        }
        if (!existingSchema.containsKey("tenantId")) {
            pending.add(QdrantFutures.toUni(
                client.createPayloadIndexAsync(collection, "tenantId",
                    PayloadSchemaType.Keyword, null, true, null, null))
                .replaceWithVoid());
        }

        if (pending.isEmpty()) return Uni.createFrom().voidItem();
        return Uni.join().all(pending).andFailFast().replaceWithVoid();
    }

    private static void checkIndexType(Map<String, PayloadSchemaInfo> schema,
            String field, PayloadSchemaType expected, String collection) {
        PayloadSchemaInfo info = schema.get(field);
        if (info != null && info.getDataType() != expected) {
            throw new IllegalStateException(
                "Payload index type mismatch on field '" + field
                    + "' in collection '" + collection
                    + "': expected " + expected + " but found " + info.getDataType());
        }
    }

    private record EmbeddingResult(List<Embedding> dense, List<Map<Integer, Float>> sparse) {}
}
