package io.casehub.neocortex.memory.cbr.qdrant;

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

/**
 * Manages Qdrant collections for CBR case storage.
 *
 * <p>Creates collections with a single named dense vector (when dimension known)
 * or without vectors (payload-filter-only mode). Creates payload indexes for
 * base fields (tenantId, caseType, entityId, domain, caseId) and for registered
 * feature schema fields.
 */
final class CbrCollectionManager {

    private static final Logger LOG = Logger.getLogger(CbrCollectionManager.class.getName());

    /** Base payload fields that always get keyword indexes. */
    private static final String[] BASE_KEYWORD_FIELDS =
        {"tenantId", "caseType", "entityId", "domain", "caseId"};

    private final QdrantClient client;
    private final QdrantCbrConfig config;
    private final Set<String> knownCollections = ConcurrentHashMap.newKeySet();

    CbrCollectionManager(QdrantClient client, QdrantCbrConfig config) {
        this.client = client;
        this.config = config;
    }

    /**
     * Resolve the Qdrant collection name for a given case type.
     */
    String collectionName(String caseType) {
        return config.collectionPrefix() + "_" + caseType;
    }

    /**
     * Ensure the collection exists with base payload indexes.
     * Creates the collection if it does not exist.
     *
     * @param caseType       the case type (determines collection name)
     * @param vectorDimension dimension of dense vectors, or 0 if no embedding model
     */
    void ensureCollection(String caseType, int vectorDimension) {
        String collection = collectionName(caseType);
        if (knownCollections.contains(collection)) {
            return;
        }

        int effectiveDim = vectorDimension > 0 ? vectorDimension : 1;

        try {
            if (client.collectionExistsAsync(collection).get()) {
                var info = client.getCollectionInfoAsync(collection).get();
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
                        client.deleteCollectionAsync(collection).get();
                    } else if (hasMissingSparseVectors(info)) {
                        if (!config.allowSparseVectorMigration()) {
                            throw new CbrSparseVectorMigrationException(collection);
                        }
                        LOG.warning("Collection " + collection
                            + " missing required sparse vectors — recreating."
                            + " Run reconciliation per tenant to recover data.");
                        client.deleteCollectionAsync(collection).get();
                    } else {
                        knownCollections.add(collection);
                        return;
                    }
                } else if (hasMissingSparseVectors(info)) {
                    if (!config.allowSparseVectorMigration()) {
                        throw new CbrSparseVectorMigrationException(collection);
                    }
                    LOG.warning("Collection " + collection
                        + " missing required sparse vectors — recreating."
                        + " Run reconciliation per tenant to recover data.");
                    client.deleteCollectionAsync(collection).get();
                } else {
                    knownCollections.add(collection);
                    return;
                }
            }

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

            client.createCollectionAsync(createBuilder.build()).get();
            createBasePayloadIndexes(collection);
            knownCollections.add(collection);

        } catch (CbrDimensionMismatchException | CbrSparseVectorMigrationException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during ensureCollection", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("ensureCollection failed for " + collection, e.getCause());
        }
    }

    /**
     * Register feature schema payload indexes on the collection.
     * Called from {@code registerSchema()} — creates indexes asynchronously
     * (fires and waits to ensure they exist for subsequent queries).
     */
    void registerSchemaIndexes(CbrFeatureSchema schema, int vectorDimension) {
        ensureCollection(schema.caseType(), vectorDimension);
        String collection = collectionName(schema.caseType());

        try {
            for (FeatureField field : schema.fields()) {
                String payloadKey = "f_" + field.name();
                switch (field) {
                    case FeatureField.Categorical c -> client.createPayloadIndexAsync(collection, payloadKey,
                                                                                      PayloadSchemaType.Keyword, null, true, null, null).get();
                    case FeatureField.Numeric n -> client.createPayloadIndexAsync(collection, payloadKey,
                                                                                  PayloadSchemaType.Float, null, true, null, null).get();
                    case FeatureField.Text t -> client.createPayloadIndexAsync(collection, payloadKey,
                                                                               PayloadSchemaType.Keyword, null, true, null, null).get();
                    case FeatureField.CategoricalList cl -> client.createPayloadIndexAsync(collection, payloadKey,
                                                                                           PayloadSchemaType.Keyword, null, true, null, null).get();
                    case FeatureField.NumericList nl -> client.createPayloadIndexAsync(collection, payloadKey,
                                                                                       PayloadSchemaType.Float, null, true, null, null).get();
                    case FeatureField.NestedObject no -> {
                        for (FeatureField inner : no.innerFields()) {
                            String            innerKey = payloadKey + "." + inner.name();
                            PayloadSchemaType type     = innerPayloadType(inner);
                            client.createPayloadIndexAsync(collection, innerKey,
                                                           type, null, true, null, null).get();
                        }
                    }
                    case FeatureField.ObjectList ol -> {
                        for (FeatureField inner : ol.innerFields()) {
                            String            innerKey = payloadKey + "[]." + inner.name();
                            PayloadSchemaType type     = innerPayloadType(inner);
                            client.createPayloadIndexAsync(collection, innerKey,
                                                           type, null, true, null, null).get();
                        }
                    }
                    case FeatureField.TimeSeries ts -> {}
                    case FeatureField.DiscreteSequence ds -> {}
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during registerSchemaIndexes", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("registerSchemaIndexes failed", e.getCause());
        }}

    private static PayloadSchemaType innerPayloadType(FeatureField field) {
        return switch (field) {
            case FeatureField.Categorical c -> PayloadSchemaType.Keyword;
            case FeatureField.Numeric n -> PayloadSchemaType.Float;
            case FeatureField.Text t -> PayloadSchemaType.Keyword;
            default -> throw new IllegalStateException("Unexpected inner field type: " + field);
        };
    }


    /**
     * Delete all points matching a filter from a collection.
     * Returns the estimated count of deleted points (via scroll before delete).
     */
    int deleteByFilter(String collection, io.qdrant.client.grpc.Common.Filter filter) {
        try {
            // Count matching points by scrolling
            var scrollBuilder = io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                .setCollectionName(collection)
                .setFilter(filter)
                .setLimit(10000)
                .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(false));

            var response = client.scrollAsync(scrollBuilder.build()).get();
            int count = response.getResultCount();

            if (count > 0) {
                client.deleteAsync(collection, filter).get();
            }
            return count;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during delete", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Delete failed", e.getCause());
        }
    }

    private void createBasePayloadIndexes(String collection)
            throws InterruptedException, ExecutionException {
        for (String field : BASE_KEYWORD_FIELDS) {
            client.createPayloadIndexAsync(collection, field,
                PayloadSchemaType.Keyword, null, true, null, null).get();
        }
        client.createPayloadIndexAsync(collection, "_stored_at",
            PayloadSchemaType.Float, null, true, null, null).get();
    }

    private boolean hasMissingSparseVectors(io.qdrant.client.grpc.Collections.CollectionInfo info) {
        var existingSparse = info.getConfig().getParams()
            .getSparseVectorsConfig().getMapMap();
        if (config.spladeEnabled() && !existingSparse.containsKey(config.spladeVectorName())) {
            return true;
        }
        return config.bm25Enabled() && !existingSparse.containsKey(config.bm25VectorName());
    }


    /**
     * Invalidate the cached state for a collection, forcing the next
     * {@link #ensureCollection} call to re-check and recreate if needed.
     * Used by reconciliation when a collection is found to be missing.
     */
    void invalidateCollection(String caseType) {
        knownCollections.remove(collectionName(caseType));
    }

    QdrantClient client() {
        return client;
    }
}
