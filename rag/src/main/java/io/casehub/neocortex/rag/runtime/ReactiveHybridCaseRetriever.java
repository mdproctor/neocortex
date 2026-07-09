package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.inference.EmbeddingMode;
import io.casehub.neocortex.inference.MultiModalEmbedder;
import io.casehub.neocortex.inference.MultiModalEmbedding;
import io.casehub.neocortex.fusion.CamelCaseExpander;
import io.casehub.neocortex.fusion.FusionStrategy;
import io.casehub.neocortex.fusion.ScoreFusion;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.ReactiveCaseRetriever;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.Document;
import io.qdrant.client.grpc.Points.Fusion;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.Rrf;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchParams;
import io.qdrant.client.grpc.Points.QuantizationSearchParams;
import io.casehub.neocortex.inference.MatryoshkaMultiModalEmbedder;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
@IfBuildProperty(name = "casehub.rag.reactive.enabled", stringValue = "true")
public class ReactiveHybridCaseRetriever implements ReactiveCaseRetriever {

    private final QdrantClient client;
    private final MultiModalEmbedder embedder;
    private final TenantGuard tenantGuard;
    private final RagConfig config;

    @Inject
    ReactiveHybridCaseRetriever(QdrantClient client, MultiModalEmbedder embedder,
                                Instance<CurrentPrincipal> currentPrincipalInstance,
                                RagConfig config) {
        this(client,
            MatryoshkaMultiModalEmbedder.wrapIfNeeded(embedder, config.matryoshka().dimension()),
            TenantGuard.of(currentPrincipalInstance.isResolvable()
                ? currentPrincipalInstance.get() : null),
            config);
    }

    ReactiveHybridCaseRetriever(QdrantClient client, MultiModalEmbedder embedder,
                                TenantGuard tenantGuard, RagConfig config) {
        this.client = client;
        this.embedder = embedder;
        this.tenantGuard = tenantGuard;
        this.config = config;
    }

    @Override
    public Uni<List<RetrievedChunk>> retrieve(RetrievalQuery query, CorpusRef corpus, int maxResults, PayloadFilter filter) {
        return Uni.createFrom().deferred(() -> {
            tenantGuard.assertTenant(corpus.tenantId());

            String collection = config.tenancyStrategy().collectionName(corpus);
            Optional<Filter> tenantFilter = config.tenancyStrategy().tenantFilter(corpus);
            Optional<Filter> payloadFilter = PayloadFilterTranslator.toQdrantFilter(filter);

            Filter.Builder combined = Filter.newBuilder();
            tenantFilter.ifPresent(tf -> combined.addAllMust(tf.getMustList()));
            payloadFilter.ifPresent(pf -> combined.addAllMust(pf.getMustList()));
            Optional<Filter> mergedFilter = combined.getMustCount() > 0
                ? Optional.of(combined.build()) : Optional.empty();

            return QdrantFutures.<Boolean>toUni(client.collectionExistsAsync(collection))
                .chain(exists -> {
                    if (!exists) {
                        return Uni.createFrom().item(List.<RetrievedChunk>of());
                    }
                    // Embed query: when expansion is active, batch-embed both searchText (expanded)
                    // and text (original). Dense leg uses searchText, sparse/ColBERT use text.
                    if (query.expandedText() != null) {
                        return Uni.createFrom().item(() -> embedder.embedBatch(
                                List.of(query.searchText(), query.text())))
                            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                            .chain(embeddings -> executeRetrieve(query, collection, mergedFilter,
                                embeddings.get(0), embeddings.get(1), maxResults));
                    } else {
                        return Uni.createFrom().item(() -> embedder.embed(query.searchText()))
                            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                            .chain(embedding -> executeRetrieve(query, collection, mergedFilter,
                                embedding, embedding, maxResults));
                    }
                });
        });
    }

    private Uni<List<RetrievedChunk>> executeRetrieve(RetrievalQuery query, String collection,
            Optional<Filter> mergedFilter, MultiModalEmbedding searchTextEmbedding,
            MultiModalEmbedding originalTextEmbedding, int maxResults) {
        List<Float> denseVector = QdrantPointBuilder.floatListFrom(searchTextEmbedding.dense());

        boolean hasSparse = originalTextEmbedding.sparse() != null;
        boolean useFusion = hasSparse || config.bm25Enabled();
        FusionStrategy fusionStrategy = config.retrieval().fusionStrategy();

        // CC fusion uses client-side fusion, not server-side prefetch
        if (useFusion && fusionStrategy == FusionStrategy.CC) {
            return executeConvexCombinationFusion(collection, query, searchTextEmbedding,
                originalTextEmbedding, mergedFilter, maxResults);
        }

        QueryPoints queryPoints;
        if (useFusion) {
            List<PrefetchQuery> prefetchLegs = new ArrayList<>();

            // Dense prefetch (always present in fusion mode)
            PrefetchQuery.Builder densePrefetch = PrefetchQuery.newBuilder()
                .setQuery(QueryFactory.nearest(denseVector))
                .setUsing(config.denseVectorName())
                .setLimit(config.retrieval().denseTopK());
            if (config.quantization().type() != DenseQuantization.NONE && config.quantization().oversampling().isPresent()) {
                densePrefetch.setParams(quantizationSearchParams());
            }
            mergedFilter.ifPresent(densePrefetch::setFilter);
            prefetchLegs.add(densePrefetch.build());

            // SPLADE prefetch (when available)
            if (hasSparse) {
                Map<Integer, Float> sparseMap = originalTextEmbedding.sparse();
                List<Float> sparseValues = new ArrayList<>(sparseMap.size());
                List<Integer> sparseIndices = new ArrayList<>(sparseMap.size());
                for (Map.Entry<Integer, Float> entry : sparseMap.entrySet()) {
                    sparseIndices.add(entry.getKey());
                    sparseValues.add(entry.getValue());
                }

                PrefetchQuery.Builder sparsePrefetch = PrefetchQuery.newBuilder()
                    .setQuery(QueryFactory.nearest(sparseValues, sparseIndices))
                    .setUsing(config.sparseVectorName())
                    .setLimit(config.retrieval().sparseTopK());
                mergedFilter.ifPresent(sparsePrefetch::setFilter);
                prefetchLegs.add(sparsePrefetch.build());
            }

            // BM25 prefetch (when enabled)
            if (config.bm25Enabled()) {
                String expandedQuery = CamelCaseExpander.expand(query.text());
                PrefetchQuery.Builder bm25Prefetch = PrefetchQuery.newBuilder()
                    .setQuery(QueryFactory.nearest(
                        Document.newBuilder()
                            .setText(expandedQuery)
                            .setModel(QdrantPointBuilder.BM25_MODEL)
                            .build()))
                    .setUsing(config.bm25VectorName())
                    .setLimit(config.retrieval().bm25TopK());
                mergedFilter.ifPresent(bm25Prefetch::setFilter);
                prefetchLegs.add(bm25Prefetch.build());
            }

            // ColBERT MAX_SIM two-stage: fusion as prefetch, ColBERT as outer query
            // Note: CC fusion does not support ColBERT reranking (handled separately above)
            if (embedder != null
                    && embedder.supportedModes().contains(EmbeddingMode.COLBERT)
                    && originalTextEmbedding.colbert() != null
                    && config.retrieval().rerankEnabled()) {
                queryPoints = QueryPoints.newBuilder()
                    .setCollectionName(collection)
                    .addPrefetch(PrefetchQuery.newBuilder()
                        .setQuery(buildFusionQuery(fusionStrategy))
                        .setLimit(config.retrieval().rerankTopN())
                        .addAllPrefetch(prefetchLegs))
                    .setQuery(QueryFactory.nearest(originalTextEmbedding.colbert()))
                    .setUsing(config.colbertVectorName())
                    .setLimit(maxResults)
                    .setWithPayload(WithPayloadSelectorFactory.enable(true))
                    .build();
            } else {
                QueryPoints.Builder qb = QueryPoints.newBuilder()
                    .setCollectionName(collection);
                qb.addAllPrefetch(prefetchLegs);
                qb.setQuery(buildFusionQuery(fusionStrategy))
                   .setLimit(maxResults)
                   .setWithPayload(WithPayloadSelectorFactory.enable(true));
                queryPoints = qb.build();
            }
        } else {
            // Dense-only mode: direct nearest-neighbor query (no fusion)
            QueryPoints.Builder builder = QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setQuery(QueryFactory.nearest(denseVector))
                .setUsing(config.denseVectorName())
                .setLimit(maxResults)
                .setWithPayload(WithPayloadSelectorFactory.enable(true));
            if (config.quantization().type() != DenseQuantization.NONE && config.quantization().oversampling().isPresent()) {
                builder.setParams(quantizationSearchParams());
            }
            mergedFilter.ifPresent(builder::setFilter);
            queryPoints = builder.build();
        }

        return QdrantFutures.toUni(client.queryAsync(queryPoints))
            .map(this::mapToChunks);
    }

    private List<RetrievedChunk> mapToChunks(List<ScoredPoint> scoredPoints) {
        List<RetrievedChunk> chunks = new ArrayList<>(scoredPoints.size());
        for (ScoredPoint point : scoredPoints) {
            Map<String, Value> payload = point.getPayloadMap();
            String content = extractString(payload, "content");
            String sourceDocumentId = extractString(payload, "sourceDocumentId");
            if (content == null || sourceDocumentId == null) continue;

            Map<String, String> metadata = new HashMap<>();
            for (Map.Entry<String, Value> entry : payload.entrySet()) {
                if (!QdrantPointBuilder.RESERVED_PAYLOAD_KEYS.contains(entry.getKey())
                        && entry.getValue().hasStringValue()) {
                    metadata.put(entry.getKey(), entry.getValue().getStringValue());
                }
            }
            chunks.add(new RetrievedChunk(content, sourceDocumentId,
                point.getScore(), Map.copyOf(metadata)));
        }
        chunks.sort((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()));
        return Collections.unmodifiableList(chunks);
    }

    private static String extractString(Map<String, Value> payload, String key) {
        Value value = payload.get(key);
        if (value != null && value.hasStringValue()) return value.getStringValue();
        return null;
    }

    private SearchParams quantizationSearchParams() {
        return SearchParams.newBuilder()
            .setQuantization(QuantizationSearchParams.newBuilder()
                .setOversampling(config.quantization().oversampling().getAsDouble())
                .setRescore(true)
                .build())
            .build();
    }

    private io.qdrant.client.grpc.Points.Query buildFusionQuery(FusionStrategy strategy) {
        return switch (strategy) {
            case RRF -> QueryFactory.rrf(Rrf.newBuilder().setK(config.retrieval().rrfK()).build());
            case DBSF -> QueryFactory.fusion(Fusion.DBSF);
            case CC -> throw new IllegalStateException(
                "CC fusion should be handled by executeConvexCombinationFusion");
        };
    }

    private Uni<List<RetrievedChunk>> executeConvexCombinationFusion(
            String collection, RetrievalQuery query, MultiModalEmbedding searchTextEmbedding,
            MultiModalEmbedding originalTextEmbedding, Optional<Filter> mergedFilter, int maxResults) {

        List<Float> denseVector = QdrantPointBuilder.floatListFrom(searchTextEmbedding.dense());

        // Build dense query
        QueryPoints.Builder denseQuery = QueryPoints.newBuilder()
            .setCollectionName(collection)
            .setQuery(QueryFactory.nearest(denseVector))
            .setUsing(config.denseVectorName())
            .setLimit(config.retrieval().denseTopK())
            .setWithPayload(WithPayloadSelectorFactory.enable(true));
        if (config.quantization().type() != DenseQuantization.NONE && config.quantization().oversampling().isPresent()) {
            denseQuery.setParams(quantizationSearchParams());
        }
        mergedFilter.ifPresent(denseQuery::setFilter);

        Uni<List<RetrievedChunk>> denseUni = QdrantFutures.toUni(client.queryAsync(denseQuery.build()))
            .map(this::mapToChunks);

        // Build sparse query (if available)
        Uni<List<RetrievedChunk>> sparseUni = null;
        if (originalTextEmbedding.sparse() != null) {
            Map<Integer, Float> sparseMap = originalTextEmbedding.sparse();
            List<Float> sparseValues = new ArrayList<>(sparseMap.size());
            List<Integer> sparseIndices = new ArrayList<>(sparseMap.size());
            for (Map.Entry<Integer, Float> entry : sparseMap.entrySet()) {
                sparseIndices.add(entry.getKey());
                sparseValues.add(entry.getValue());
            }

            QueryPoints.Builder sparseQuery = QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setQuery(QueryFactory.nearest(sparseValues, sparseIndices))
                .setUsing(config.sparseVectorName())
                .setLimit(config.retrieval().sparseTopK())
                .setWithPayload(WithPayloadSelectorFactory.enable(true));
            mergedFilter.ifPresent(sparseQuery::setFilter);

            sparseUni = QdrantFutures.toUni(client.queryAsync(sparseQuery.build()))
                .map(this::mapToChunks);
        }

        // Build BM25 query (if enabled)
        Uni<List<RetrievedChunk>> bm25Uni = null;
        if (config.bm25Enabled()) {
            String expandedQuery = CamelCaseExpander.expand(query.text());
            QueryPoints.Builder bm25Query = QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setQuery(QueryFactory.nearest(
                    Document.newBuilder()
                        .setText(expandedQuery)
                        .setModel(QdrantPointBuilder.BM25_MODEL)
                        .build()))
                .setUsing(config.bm25VectorName())
                .setLimit(config.retrieval().bm25TopK())
                .setWithPayload(WithPayloadSelectorFactory.enable(true));
            mergedFilter.ifPresent(bm25Query::setFilter);

            bm25Uni = QdrantFutures.toUni(client.queryAsync(bm25Query.build()))
                .map(this::mapToChunks);
        }

        // Combine all Unis
        List<Uni<List<RetrievedChunk>>> unis = new ArrayList<>();
        unis.add(denseUni);
        boolean hasSparseUni = sparseUni != null;
        boolean hasBm25Uni = bm25Uni != null;
        if (hasSparseUni) unis.add(sparseUni);
        if (hasBm25Uni) unis.add(bm25Uni);

        return Uni.combine().all().unis(unis).combinedWith(results -> {
            List<ScoreFusion.ScoredLeg<RetrievedChunk>> legs = new ArrayList<>();

            // Dense leg (always present at index 0)
            @SuppressWarnings("unchecked")
            List<RetrievedChunk> denseChunks = (List<RetrievedChunk>) results.get(0);
            if (!denseChunks.isEmpty()) {
                legs.add(new ScoreFusion.ScoredLeg<>(
                    denseChunks, RetrievedChunk::relevanceScore, config.retrieval().ccWeights().dense()));
            }

            // Sparse leg (if present, at index 1)
            if (hasSparseUni) {
                @SuppressWarnings("unchecked")
                List<RetrievedChunk> sparseChunks = (List<RetrievedChunk>) results.get(1);
                if (!sparseChunks.isEmpty()) {
                    legs.add(new ScoreFusion.ScoredLeg<>(
                        sparseChunks, RetrievedChunk::relevanceScore, config.retrieval().ccWeights().sparse()));
                }
            }

            // BM25 leg (if present, at index 1 or 2 depending on sparse)
            if (hasBm25Uni) {
                int bm25Index = hasSparseUni ? 2 : 1;
                @SuppressWarnings("unchecked")
                List<RetrievedChunk> bm25Chunks = (List<RetrievedChunk>) results.get(bm25Index);
                if (!bm25Chunks.isEmpty()) {
                    legs.add(new ScoreFusion.ScoredLeg<>(
                        bm25Chunks, RetrievedChunk::relevanceScore, config.retrieval().ccWeights().bm25()));
                }
            }

            return ScoreFusion.convexCombination(legs, RetrievedChunk::fusionKey, maxResults)
                .stream().map(f -> f.item().withRelevanceScore(f.score())).toList();
        });
    }
}
