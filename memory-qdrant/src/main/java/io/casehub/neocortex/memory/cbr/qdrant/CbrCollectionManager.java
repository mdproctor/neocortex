package io.casehub.neocortex.memory.cbr.qdrant;

import com.google.common.util.concurrent.ListenableFuture;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.Modifier;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

final class CbrCollectionManager {

    private static final Logger LOG = Logger.getLogger(CbrCollectionManager.class.getName());

    private static final String[] BASE_KEYWORD_FIELDS =
        {"tenantId", "caseType", "entityId", "domain", "caseId", "scope"};

    private final QdrantClient client;
    private final QdrantCbrConfig config;
    private final Set<String> knownCollections = ConcurrentHashMap.newKeySet();

    CbrCollectionManager(QdrantClient client, QdrantCbrConfig config) {
        this.client = client;
        this.config = config;
    }

    private static <T> T awaitFuture(ListenableFuture<T> future, String operation) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during " + operation, e);
        } catch (ExecutionException e) {
            throw new RuntimeException(operation + " failed", e.getCause());
        }
    }

    String collectionName(String caseType) {
        return config.collectionPrefix() + "_" + caseType;
    }

    void ensureCollection(String caseType, int vectorDimension) {
        String collection = collectionName(caseType);
        if (knownCollections.contains(collection)) return;

        int effectiveDim = vectorDimension > 0 ? vectorDimension : 1;

        boolean exists = awaitFuture(client.collectionExistsAsync(collection), "collectionExists");
        if (exists) {
            var info = awaitFuture(client.getCollectionInfoAsync(collection), "getCollectionInfo");
            handleExistingCollection(collection, caseType, info, effectiveDim);
        } else {
            createNewCollection(collection, effectiveDim);
        }
        knownCollections.add(collection);
    }

    void registerSchemaIndexes(CbrFeatureSchema schema, int vectorDimension) {
        ensureCollection(schema.caseType(), vectorDimension);
        String collection = collectionName(schema.caseType());
        for (FeatureField field : schema.fields()) {
            indexesForField(collection, "f_" + field.name(), field);
        }
    }

    private static PayloadSchemaType innerPayloadType(FeatureField field) {
        return switch (field) {
            case FeatureField.Categorical c -> PayloadSchemaType.Keyword;
            case FeatureField.Numeric n -> PayloadSchemaType.Float;
            case FeatureField.Text t -> PayloadSchemaType.Keyword;
            default -> throw new IllegalStateException("Unexpected inner field type: " + field);
        };
    }

    private void indexesForField(String collection, String payloadKey, FeatureField field) {
        switch (field) {
            case FeatureField.Categorical c ->
                awaitFuture(client.createPayloadIndexAsync(collection, payloadKey,
                    PayloadSchemaType.Keyword, null, true, null, null), "createIndex");
            case FeatureField.Numeric n ->
                awaitFuture(client.createPayloadIndexAsync(collection, payloadKey,
                    PayloadSchemaType.Float, null, true, null, null), "createIndex");
            case FeatureField.Text t ->
                awaitFuture(client.createPayloadIndexAsync(collection, payloadKey,
                    PayloadSchemaType.Keyword, null, true, null, null), "createIndex");
            case FeatureField.CategoricalList cl ->
                awaitFuture(client.createPayloadIndexAsync(collection, payloadKey,
                    PayloadSchemaType.Keyword, null, true, null, null), "createIndex");
            case FeatureField.NumericList nl ->
                awaitFuture(client.createPayloadIndexAsync(collection, payloadKey,
                    PayloadSchemaType.Float, null, true, null, null), "createIndex");
            case FeatureField.NestedObject no -> {
                for (FeatureField inner : no.innerFields()) {
                    awaitFuture(client.createPayloadIndexAsync(collection, payloadKey + "." + inner.name(),
                        innerPayloadType(inner), null, true, null, null), "createIndex");
                }
            }
            case FeatureField.ObjectList ol -> {
                for (FeatureField inner : ol.innerFields()) {
                    awaitFuture(client.createPayloadIndexAsync(collection, payloadKey + "[]." + inner.name(),
                        innerPayloadType(inner), null, true, null, null), "createIndex");
                }
            }
            case FeatureField.TimeSeries ts -> {}
            case FeatureField.DiscreteSequence ds -> {}
        }
    }

    int deleteByFilter(String collection, io.qdrant.client.grpc.Common.Filter filter) {
        var scrollBuilder = io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
            .setCollectionName(collection)
            .setFilter(filter)
            .setLimit(10000)
            .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(false));

        var response = awaitFuture(client.scrollAsync(scrollBuilder.build()), "scrollForDelete");
        int count = response.getResultCount();
        if (count > 0) {
            awaitFuture(client.deleteAsync(collection, filter), "deleteByFilter");
        }
        return count;
    }

    private void createBasePayloadIndexes(String collection) {
        for (String field : BASE_KEYWORD_FIELDS) {
            awaitFuture(client.createPayloadIndexAsync(collection, field,
                PayloadSchemaType.Keyword, null, true, null, null), "createBaseIndex");
        }
        awaitFuture(client.createPayloadIndexAsync(collection, "_stored_at",
            PayloadSchemaType.Float, null, true, null, null), "createStoredAtIndex");
    }

    private void createNewCollection(String collection, int effectiveDim) {
        VectorParams denseParams = VectorParams.newBuilder()
            .setSize(effectiveDim)
            .setDistance(Distance.Cosine)
            .build();

        CreateCollection.Builder createBuilder = CreateCollection.newBuilder()
            .setCollectionName(collection)
            .setVectorsConfig(VectorsConfig.newBuilder()
                .setParamsMap(VectorParamsMap.newBuilder()
                    .putMap(config.denseVectorName(), denseParams)
                    .build())
                .build());

        if (config.spladeEnabled() || config.bm25Enabled()) {
            SparseVectorConfig.Builder sparseBuilder = SparseVectorConfig.newBuilder();
            if (config.spladeEnabled()) {
                sparseBuilder.putMap(config.spladeVectorName(), SparseVectorParams.getDefaultInstance());
            }
            if (config.bm25Enabled()) {
                sparseBuilder.putMap(config.bm25VectorName(),
                    SparseVectorParams.newBuilder().setModifier(Modifier.Idf).build());
            }
            createBuilder.setSparseVectorsConfig(sparseBuilder.build());
        }

        awaitFuture(client.createCollectionAsync(createBuilder.build()), "createCollection");
        createBasePayloadIndexes(collection);
    }

    private void handleExistingCollection(String collection, String caseType,
                                          io.qdrant.client.grpc.Collections.CollectionInfo info,
                                          int effectiveDim) {
        var vectorsConfig = info.getConfig().getParams().getVectorsConfig();
        if (vectorsConfig.hasParamsMap()) {
            var params = vectorsConfig.getParamsMap().getMapMap().get(config.denseVectorName());
            if (params != null && params.getSize() != effectiveDim) {
                if (!config.allowDimensionMigration()) {
                    throw new CbrDimensionMismatchException(collection, (int) params.getSize(), effectiveDim);
                }
                LOG.warning("Collection " + collection + " dimension mismatch ("
                            + params.getSize() + " → " + effectiveDim
                            + ") — recreating. ALL tenants sharing caseType=" + caseType
                            + " are affected. Run reconciliation per tenant to recover data.");
                awaitFuture(client.deleteCollectionAsync(collection), "deleteCollection");
                createNewCollection(collection, effectiveDim);
            } else if (hasMissingSparseVectors(info)) {
                recreateForSparseVectors(collection, effectiveDim);
            }
        } else if (hasMissingSparseVectors(info)) {
            recreateForSparseVectors(collection, effectiveDim);
        }
    }

    private void recreateForSparseVectors(String collection, int effectiveDim) {
        if (!config.allowSparseVectorMigration()) {
            throw new CbrSparseVectorMigrationException(collection);
        }
        LOG.warning("Collection " + collection
                    + " missing required sparse vectors — recreating."
                    + " Run reconciliation per tenant to recover data.");
        awaitFuture(client.deleteCollectionAsync(collection), "deleteCollection");
        createNewCollection(collection, effectiveDim);
    }

    private boolean hasMissingSparseVectors(io.qdrant.client.grpc.Collections.CollectionInfo info) {
        var existingSparse = info.getConfig().getParams()
            .getSparseVectorsConfig().getMapMap();
        if (config.spladeEnabled() && !existingSparse.containsKey(config.spladeVectorName())) {
            return true;
        }
        return config.bm25Enabled() && !existingSparse.containsKey(config.bm25VectorName());
    }

    void invalidateCollection(String caseType) {
        knownCollections.remove(collectionName(caseType));
    }

    QdrantClient client() {
        return client;
    }
}
