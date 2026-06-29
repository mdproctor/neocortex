package io.casehub.rag.runtime;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.inference.tasks.CrossEncoderReranker;
import io.casehub.inference.tasks.RankedResult;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
import io.casehub.rag.ReactiveCaseRetriever;
import io.casehub.rag.RetrievalQuery;
import io.casehub.rag.RetrievedChunk;
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
    private final EmbeddingModel embeddingModel;
    private final SparseEmbedder sparseEmbedder;
    private final TenantGuard tenantGuard;
    private final CrossEncoderReranker reranker;
    private final RagConfig config;

    ReactiveHybridCaseRetriever(
            QdrantClient client,
            EmbeddingModel embeddingModel,
            SparseEmbedder sparseEmbedder,
            TenantGuard tenantGuard,
            CrossEncoderReranker reranker,
            RagConfig config) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.sparseEmbedder = sparseEmbedder;
        this.tenantGuard = tenantGuard;
        this.reranker = reranker;
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
                    return Uni.createFrom().item(() -> embedQuery(query))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                        .chain(embeddings -> executeQuery(query, collection, mergedFilter,
                            embeddings, maxResults))
                        .map(this::mapToChunks)
                        .chain(chunks -> maybeRerank(query, chunks, maxResults));
                });
        });
    }

    private QueryEmbeddings embedQuery(RetrievalQuery query) {
        Embedding denseEmbedding = embeddingModel.embed(TextSegment.from(query.searchText())).content();
        Map<Integer, Float> sparseMap = sparseEmbedder != null
            ? sparseEmbedder.embed(query.text()) : null;
        return new QueryEmbeddings(denseEmbedding, sparseMap);
    }

    private Uni<List<ScoredPoint>> executeQuery(RetrievalQuery query, String collection,
            Optional<Filter> mergedFilter, QueryEmbeddings embeddings, int maxResults) {
        int queryLimit = config.retrieval().rerankEnabled() && reranker != null
            ? Math.max(maxResults, config.retrieval().rerankTopN())
            : maxResults;

        QueryPoints queryPoints;
        boolean useRrf = embeddings.sparse != null || config.bm25Enabled();
        if (useRrf) {
            QueryPoints.Builder qb = QueryPoints.newBuilder()
                .setCollectionName(collection);

            // Dense prefetch (always present in RRF mode)
            PrefetchQuery.Builder densePrefetch = PrefetchQuery.newBuilder()
                .setQuery(QueryFactory.nearest(embeddings.dense.vectorAsList()))
                .setUsing(config.denseVectorName())
                .setLimit(config.retrieval().denseTopK());
            if (config.quantization().type() != DenseQuantization.NONE && config.quantization().oversampling().isPresent()) {
                densePrefetch.setParams(quantizationSearchParams());
            }
            mergedFilter.ifPresent(densePrefetch::setFilter);
            qb.addPrefetch(densePrefetch);

            // SPLADE prefetch (when available)
            if (embeddings.sparse != null) {
                List<Float> sparseValues = new ArrayList<>(embeddings.sparse.size());
                List<Integer> sparseIndices = new ArrayList<>(embeddings.sparse.size());
                for (Map.Entry<Integer, Float> entry : embeddings.sparse.entrySet()) {
                    sparseIndices.add(entry.getKey());
                    sparseValues.add(entry.getValue());
                }

                PrefetchQuery.Builder sparsePrefetch = PrefetchQuery.newBuilder()
                    .setQuery(QueryFactory.nearest(sparseValues, sparseIndices))
                    .setUsing(config.sparseVectorName())
                    .setLimit(config.retrieval().sparseTopK());
                mergedFilter.ifPresent(sparsePrefetch::setFilter);
                qb.addPrefetch(sparsePrefetch);
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
                qb.addPrefetch(bm25Prefetch);
            }

            qb.setQuery(QueryFactory.rrf(Rrf.newBuilder().setK(config.retrieval().rrfK()).build()))
               .setLimit(queryLimit)
               .setWithPayload(WithPayloadSelectorFactory.enable(true));
            queryPoints = qb.build();
        } else {
            // Dense-only mode: direct nearest-neighbor query (no fusion)
            QueryPoints.Builder builder = QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setQuery(QueryFactory.nearest(embeddings.dense.vectorAsList()))
                .setUsing(config.denseVectorName())
                .setLimit(queryLimit)
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
        return chunks;
    }

    private Uni<List<RetrievedChunk>> maybeRerank(RetrievalQuery query,
            List<RetrievedChunk> chunks, int maxResults) {
        if (!config.retrieval().rerankEnabled() || reranker == null || chunks.isEmpty()) {
            chunks.sort((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()));
            return Uni.createFrom().item(Collections.unmodifiableList(chunks));
        }
        return Uni.createFrom().item(() -> {
            List<String> texts = new ArrayList<>(chunks.size());
            for (RetrievedChunk chunk : chunks) texts.add(chunk.content());
            List<RankedResult> ranked = reranker.rerank(query.text(), texts);
            List<RetrievedChunk> reranked = new ArrayList<>(
                Math.min(ranked.size(), maxResults));
            for (int i = 0; i < Math.min(ranked.size(), maxResults); i++) {
                RankedResult r = ranked.get(i);
                RetrievedChunk original = chunks.get(r.originalIndex());
                reranked.add(new RetrievedChunk(
                    original.content(), original.sourceDocumentId(),
                    r.score(), original.metadata()));
            }
            return Collections.unmodifiableList(reranked);
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
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

    private record QueryEmbeddings(Embedding dense, Map<Integer, Float> sparse) {}
}
