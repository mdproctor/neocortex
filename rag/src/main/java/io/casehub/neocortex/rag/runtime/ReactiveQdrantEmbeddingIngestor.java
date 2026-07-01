package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.inference.EmbeddingMode;
import io.casehub.neocortex.inference.MultiModalEmbedder;
import io.casehub.neocortex.inference.MultiModalEmbedding;
import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.ReactiveEmbeddingIngestor;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.MultiVectorComparator;
import io.qdrant.client.grpc.Collections.MultiVectorConfig;
import io.qdrant.client.grpc.Collections.PayloadIndexParams;
import io.qdrant.client.grpc.Collections.PayloadSchemaInfo;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.Modifier;
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
    private final MultiModalEmbedder embedder;
    private final TenantGuard tenantGuard;
    private final RagConfig config;

    private final ConcurrentHashMap<String, Uni<Void>> ensuredCollections = new ConcurrentHashMap<>();

    ReactiveQdrantEmbeddingIngestor(
            QdrantClient client,
            MultiModalEmbedder embedder,
            TenantGuard tenantGuard,
            RagConfig config) {
        if (config.embeddingBatchSize() <= 0) {
            throw new IllegalArgumentException("batchSize must be positive, got: " + config.embeddingBatchSize());
        }
        this.client = client;
        this.embedder = embedder;
        this.tenantGuard = tenantGuard;
        this.config = config;
    }

    @Override
    public Uni<Void> ingest(CorpusRef corpus, List<ChunkInput> chunks) {
        return Uni.createFrom().deferred(() -> {
            tenantGuard.assertTenant(corpus.tenantId());
            if (chunks.isEmpty()) return Uni.createFrom().voidItem();

            String collection = config.tenancyStrategy().collectionName(corpus);
            int[] chunkIndices = QdrantPointBuilder.computeChunkIndices(chunks);
            int effectiveBatchSize = Math.min(config.embeddingBatchSize(), chunks.size());
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
                            List<String> texts = new ArrayList<>(batch.size());
                            for (ChunkInput chunk : batch) {
                                texts.add(chunk.content());
                            }
                            return embedder.embedBatch(texts);
                        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                        .chain(embeddings -> {
                            List<PointStruct> points = new ArrayList<>(batch.size());
                            for (int i = 0; i < batch.size(); i++) {
                                points.add(QdrantPointBuilder.buildPoint(batch.get(i), corpus,
                                    embeddings.get(i),
                                    chunkIndices[start + i], config));
                            }
                            return QdrantFutures.toUni(client.upsertAsync(collection, points))
                                .invoke(() -> {
                                    LOG.fine(() -> "Ingested batch " + (batchNum + 1) + "/" + totalBatches
                                        + " (" + end + "/" + chunks.size() + " chunks)");
                                })
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
            String collection = config.tenancyStrategy().collectionName(corpus);

            Filter.Builder filterBuilder = Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("sourceDocumentId", sourceDocumentId));
            config.tenancyStrategy().tenantFilter(corpus)
                .ifPresent(tf -> tf.getMustList().forEach(filterBuilder::addMust));

            return QdrantFutures.toUni(client.deleteAsync(collection, filterBuilder.build()))
                .replaceWithVoid();
        });
    }

    @Override
    public Uni<Void> deleteCorpus(CorpusRef corpus) {
        return Uni.createFrom().deferred(() -> {
            tenantGuard.assertTenant(corpus.tenantId());
            String collection = config.tenancyStrategy().collectionName(corpus);

            if (config.tenancyStrategy() == TenancyStrategy.SEPARATE_COLLECTIONS) {
                return QdrantFutures.toUni(client.deleteCollectionAsync(collection))
                    .invoke(() -> ensuredCollections.remove(collection))
                    .replaceWithVoid();
            } else {
                Optional<Filter> tenantFilter = config.tenancyStrategy().tenantFilter(corpus);
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
            String collection = config.tenancyStrategy().collectionName(corpus);
            Optional<Filter> tenantFilter = config.tenancyStrategy().tenantFilter(corpus);

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
                                    .get(config.denseVectorName()).getSize();
                                int denseDim = embedder.denseDimension();
                                if (existingDim != denseDim) {
                                    throw new IllegalStateException(
                                        "Configured embedding dimension (" + denseDim
                                            + ") does not match existing collection dimension ("
                                            + existingDim + ") for collection '" + k
                                            + "'. Re-index the collection or adjust matryoshka.dimension.");
                                }
                                var existingSparse = info.getConfig().getParams()
                                    .getSparseVectorsConfig().getMapMap();
                                if (embedder.supportedModes().contains(EmbeddingMode.SPARSE)
                                        && !existingSparse.containsKey(config.sparseVectorName())) {
                                    throw new IllegalStateException(
                                        "Existing collection '" + k
                                            + "' is missing required sparse vector '" + config.sparseVectorName()
                                            + "'. Re-create the collection with sparse vector support.");
                                }
                                if (config.bm25Enabled() && !existingSparse.containsKey(config.bm25VectorName())) {
                                    throw new IllegalStateException(
                                        "Existing collection '" + k
                                            + "' is missing required sparse vector '" + config.bm25VectorName()
                                            + "'. Re-create the collection with BM25 support.");
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
            .setSize(embedder.denseDimension())
            .setDistance(Distance.Cosine);

        if (config.quantization().type() == DenseQuantization.BINARY) {
            denseParamsBuilder.setQuantizationConfig(
                io.qdrant.client.grpc.Collections.QuantizationConfig.newBuilder()
                    .setBinary(io.qdrant.client.grpc.Collections.BinaryQuantization.newBuilder()
                        .setAlwaysRam(config.quantization().alwaysRam())
                        .build())
                    .build());
        } else if (config.quantization().type() == DenseQuantization.SCALAR) {
            denseParamsBuilder.setQuantizationConfig(
                io.qdrant.client.grpc.Collections.QuantizationConfig.newBuilder()
                    .setScalar(io.qdrant.client.grpc.Collections.ScalarQuantization.newBuilder()
                        .setType(io.qdrant.client.grpc.Collections.QuantizationType.Int8)
                        .setAlwaysRam(config.quantization().alwaysRam())
                        .build())
                    .build());
        }

        VectorParams denseParams = denseParamsBuilder.build();

        VectorParamsMap.Builder paramsMapBuilder = VectorParamsMap.newBuilder()
            .putMap(config.denseVectorName(), denseParams);

        if (embedder.supportedModes().contains(EmbeddingMode.COLBERT)) {
            VectorParams colbertParams = VectorParams.newBuilder()
                .setSize(embedder.colbertDimension().orElseThrow())
                .setDistance(Distance.Cosine)
                .setMultivectorConfig(MultiVectorConfig.newBuilder()
                    .setComparator(MultiVectorComparator.MaxSim).build())
                .build();
            paramsMapBuilder.putMap(config.colbertVectorName(), colbertParams);
        }

        VectorParamsMap paramsMap = paramsMapBuilder.build();
        CreateCollection.Builder builder = CreateCollection.newBuilder()
            .setCollectionName(collection)
            .setVectorsConfig(VectorsConfig.newBuilder().setParamsMap(paramsMap).build());
        if (embedder.supportedModes().contains(EmbeddingMode.SPARSE) || config.bm25Enabled()) {
            SparseVectorConfig.Builder sparseConfigBuilder = SparseVectorConfig.newBuilder();
            if (embedder.supportedModes().contains(EmbeddingMode.SPARSE)) {
                sparseConfigBuilder.putMap(config.sparseVectorName(), SparseVectorParams.getDefaultInstance());
            }
            if (config.bm25Enabled()) {
                sparseConfigBuilder.putMap(config.bm25VectorName(),
                    SparseVectorParams.newBuilder().setModifier(Modifier.Idf).build());
            }
            builder.setSparseVectorsConfig(sparseConfigBuilder.build());
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
}
