package io.casehub.neocortex.memory.cbr.qdrant;

import static io.casehub.neocortex.memory.cbr.qdrant.QdrantFutures.toUni;

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

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
        {"tenantId", "caseType", "entityId", "domain", "caseId", "scope"};

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

    void ensureCollection(String caseType, int vectorDimension) {
        ensureCollectionAsync(caseType, vectorDimension).await().indefinitely();
    }

    Uni<Void> ensureCollectionAsync(String caseType, int vectorDimension) {
        String collection = collectionName(caseType);
        if (knownCollections.contains(collection)) {
            return Uni.createFrom().voidItem();
        }

        int effectiveDim = vectorDimension > 0 ? vectorDimension : 1;

        return toUni(client.collectionExistsAsync(collection))
                       .chain(exists -> {
                           if (exists) {
                               return toUni(client.getCollectionInfoAsync(collection))
                                              .chain(info -> handleExistingCollectionAsync(collection, caseType, info, effectiveDim));
                           }
                           return createNewCollectionAsync(collection, effectiveDim);
                       })
                       .invoke(() -> knownCollections.add(collection));
    }


    void registerSchemaIndexes(CbrFeatureSchema schema, int vectorDimension) {
        registerSchemaIndexesAsync(schema, vectorDimension).await().indefinitely();
    }

    Uni<Void> registerSchemaIndexesAsync(CbrFeatureSchema schema, int vectorDimension) {
        return ensureCollectionAsync(schema.caseType(), vectorDimension)
                       .chain(() -> {
                           String collection = collectionName(schema.caseType());
                           return Multi.createFrom().iterable(schema.fields())
                                       .onItem().transformToUniAndConcatenate(field ->
                                                                                      indexesForField(collection, "f_" + field.name(), field))
                                       .collect().asList()
                                       .replaceWithVoid();
                       });
    }


    private static PayloadSchemaType innerPayloadType(FeatureField field) {
        return switch (field) {
            case FeatureField.Categorical c -> PayloadSchemaType.Keyword;
            case FeatureField.Numeric n -> PayloadSchemaType.Float;
            case FeatureField.Text t -> PayloadSchemaType.Keyword;
            default -> throw new IllegalStateException("Unexpected inner field type: " + field);
        };
    }

    private Uni<Void> indexesForField(String collection, String payloadKey, FeatureField field) {
        return switch (field) {
            case FeatureField.Categorical c -> toUni(client.createPayloadIndexAsync(collection, payloadKey,
                                                                                    PayloadSchemaType.Keyword, null, true, null, null)).replaceWithVoid();
            case FeatureField.Numeric n -> toUni(client.createPayloadIndexAsync(collection, payloadKey,
                                                                                PayloadSchemaType.Float, null, true, null, null)).replaceWithVoid();
            case FeatureField.Text t -> toUni(client.createPayloadIndexAsync(collection, payloadKey,
                                                                             PayloadSchemaType.Keyword, null, true, null, null)).replaceWithVoid();
            case FeatureField.CategoricalList cl -> toUni(client.createPayloadIndexAsync(collection, payloadKey,
                                                                                         PayloadSchemaType.Keyword, null, true, null, null)).replaceWithVoid();
            case FeatureField.NumericList nl -> toUni(client.createPayloadIndexAsync(collection, payloadKey,
                                                                                     PayloadSchemaType.Float, null, true, null, null)).replaceWithVoid();
            case FeatureField.NestedObject no -> Multi.createFrom().iterable(no.innerFields())
                                                      .onItem().transformToUniAndConcatenate(inner ->
                                                                                                     toUni(client.createPayloadIndexAsync(collection, payloadKey + "." + inner.name(),
                                                                                                                                          innerPayloadType(inner), null, true, null, null)).replaceWithVoid())
                                                      .collect().asList().replaceWithVoid();
            case FeatureField.ObjectList ol -> Multi.createFrom().iterable(ol.innerFields())
                                                    .onItem().transformToUniAndConcatenate(inner ->
                                                                                                   toUni(client.createPayloadIndexAsync(collection, payloadKey + "[]." + inner.name(),
                                                                                                                                        innerPayloadType(inner), null, true, null, null)).replaceWithVoid())
                                                    .collect().asList().replaceWithVoid();
            case FeatureField.TimeSeries ts -> Uni.createFrom().voidItem();
            case FeatureField.DiscreteSequence ds -> Uni.createFrom().voidItem();
        };
    }


    int deleteByFilter(String collection, io.qdrant.client.grpc.Common.Filter filter) {
        return deleteByFilterAsync(collection, filter).await().indefinitely();
    }

    Uni<Integer> deleteByFilterAsync(String collection, io.qdrant.client.grpc.Common.Filter filter) {
        var scrollBuilder = io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                                                                     .setCollectionName(collection)
                                                                     .setFilter(filter)
                                                                     .setLimit(10000)
                                                                     .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(false));

        return toUni(client.scrollAsync(scrollBuilder.build()))
                       .chain(response -> {
                           int count = response.getResultCount();
                           if (count > 0) {
                               return toUni(client.deleteAsync(collection, filter))
                                              .replaceWith(count);
                           }
                           return Uni.createFrom().item(0);
                       });
    }


    private Uni<Void> createBasePayloadIndexesAsync(String collection) {
        return Multi.createFrom().items(BASE_KEYWORD_FIELDS)
                    .onItem().transformToUniAndConcatenate(field ->
                                                                   toUni(client.createPayloadIndexAsync(collection, field,
                                                                                                        PayloadSchemaType.Keyword, null, true, null, null)).replaceWithVoid())
                    .collect().asList()
                    .chain(() -> toUni(client.createPayloadIndexAsync(collection, "_stored_at",
                                                                      PayloadSchemaType.Float, null, true, null, null)).replaceWithVoid());
    }

    private Uni<Void> createNewCollectionAsync(String collection, int effectiveDim) {
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

        return toUni(client.createCollectionAsync(createBuilder.build()))
                       .chain(v -> createBasePayloadIndexesAsync(collection));
    }

    private Uni<Void> handleExistingCollectionAsync(String collection, String caseType,
                                                    io.qdrant.client.grpc.Collections.CollectionInfo info,
                                                    int effectiveDim) {
        var vectorsConfig = info.getConfig().getParams().getVectorsConfig();
        if (vectorsConfig.hasParamsMap()) {
            var params = vectorsConfig.getParamsMap().getMapMap().get(config.denseVectorName());
            if (params != null && params.getSize() != effectiveDim) {
                if (!config.allowDimensionMigration()) {
                    return Uni.createFrom().failure(
                            new CbrDimensionMismatchException(collection, (int) params.getSize(), effectiveDim));
                }
                LOG.warning("Collection " + collection + " dimension mismatch ("
                            + params.getSize() + " → " + effectiveDim
                            + ") — recreating. ALL tenants sharing caseType=" + caseType
                            + " are affected. Run reconciliation per tenant to recover data.");
                return toUni(client.deleteCollectionAsync(collection))
                               .chain(v -> createNewCollectionAsync(collection, effectiveDim));
            } else if (hasMissingSparseVectors(info)) {
                if (!config.allowSparseVectorMigration()) {
                    return Uni.createFrom().failure(new CbrSparseVectorMigrationException(collection));
                }
                LOG.warning("Collection " + collection
                            + " missing required sparse vectors — recreating."
                            + " Run reconciliation per tenant to recover data.");
                return toUni(client.deleteCollectionAsync(collection))
                               .chain(v -> createNewCollectionAsync(collection, effectiveDim));
            } else {
                return Uni.createFrom().voidItem();
            }
        } else if (hasMissingSparseVectors(info)) {
            if (!config.allowSparseVectorMigration()) {
                return Uni.createFrom().failure(new CbrSparseVectorMigrationException(collection));
            }
            LOG.warning("Collection " + collection
                        + " missing required sparse vectors — recreating."
                        + " Run reconciliation per tenant to recover data.");
            return toUni(client.deleteCollectionAsync(collection))
                           .chain(v -> createNewCollectionAsync(collection, effectiveDim));
        } else {
            return Uni.createFrom().voidItem();
        }
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
