package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.inference.EmbeddingMode;
import io.casehub.neocortex.inference.MultiModalEmbedder;
import io.casehub.neocortex.inference.MultiModalEmbedding;
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
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.Rrf;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchParams;
import io.qdrant.client.grpc.Points.QuantizationSearchParams;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ReactiveHybridCaseRetriever implements ReactiveCaseRetriever {

    private final QdrantClient client;
    private final MultiModalEmbedder embedder;
    private final TenantGuard tenantGuard;
    private final RagConfig config;

    ReactiveHybridCaseRetriever(
            QdrantClient client,
            MultiModalEmbedder embedder,
            TenantGuard tenantGuard,
            RagConfig config) {
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
                    return Uni.createFrom().item(() -> embedder.embed(query.searchText()))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                        .chain(embedding -> executeQuery(query, collection, mergedFilter,
                            embedding, maxResults))
                        .map(this::mapToChunks);
                });
        });
    }

    private Uni<List<ScoredPoint>> executeQuery(RetrievalQuery query, String collection,
            Optional<Filter> mergedFilter, MultiModalEmbedding embedding, int maxResults) {
        List<Float> denseVector = QdrantPointBuilder.floatListFrom(embedding.dense());

        QueryPoints queryPoints;
        boolean hasSparse = embedding.sparse() != null;
        boolean useRrf = hasSparse || config.bm25Enabled();
        if (useRrf) {
            List<PrefetchQuery> prefetchLegs = new ArrayList<>();

            // Dense prefetch (always present in RRF mode)
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
                Map<Integer, Float> sparseMap = embedding.sparse();
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

            // ColBERT MAX_SIM two-stage: RRF as prefetch, ColBERT as outer query
            if (embedder != null
                    && embedder.supportedModes().contains(EmbeddingMode.COLBERT)
                    && embedding.colbert() != null
                    && config.retrieval().rerankEnabled()) {
                queryPoints = QueryPoints.newBuilder()
                    .setCollectionName(collection)
                    .addPrefetch(PrefetchQuery.newBuilder()
                        .setQuery(QueryFactory.rrf(Rrf.newBuilder().setK(config.retrieval().rrfK()).build()))
                        .setLimit(config.retrieval().rerankTopN())
                        .addAllPrefetch(prefetchLegs))
                    .setQuery(QueryFactory.nearest(embedding.colbert()))
                    .setUsing(config.colbertVectorName())
                    .setLimit(maxResults)
                    .setWithPayload(WithPayloadSelectorFactory.enable(true))
                    .build();
            } else {
                QueryPoints.Builder qb = QueryPoints.newBuilder()
                    .setCollectionName(collection);
                qb.addAllPrefetch(prefetchLegs);
                qb.setQuery(QueryFactory.rrf(Rrf.newBuilder().setK(config.retrieval().rrfK()).build()))
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

        return QdrantFutures.toUni(client.queryAsync(queryPoints));
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
}
