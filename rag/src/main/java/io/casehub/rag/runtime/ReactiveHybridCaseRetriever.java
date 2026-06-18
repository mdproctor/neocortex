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
import io.casehub.rag.RetrievedChunk;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.Rrf;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ReactiveHybridCaseRetriever implements ReactiveCaseRetriever {

    private static final java.util.Set<String> RESERVED_PAYLOAD_KEYS =
        java.util.Set.of("content", "sourceDocumentId", "tenantId");

    private final QdrantClient client;
    private final EmbeddingModel embeddingModel;
    private final SparseEmbedder sparseEmbedder;
    private final TenancyStrategy tenancyStrategy;
    private final String denseVectorName;
    private final String sparseVectorName;
    private final int denseTopK;
    private final int sparseTopK;
    private final int rrfK;
    private final boolean rerankEnabled;
    private final int rerankTopN;
    private final CrossEncoderReranker reranker;
    private final TenantGuard tenantGuard;

    ReactiveHybridCaseRetriever(
            QdrantClient client,
            EmbeddingModel embeddingModel,
            SparseEmbedder sparseEmbedder,
            TenancyStrategy tenancyStrategy,
            String denseVectorName,
            String sparseVectorName,
            int denseTopK,
            int sparseTopK,
            int rrfK,
            boolean rerankEnabled,
            int rerankTopN,
            CrossEncoderReranker reranker,
            TenantGuard tenantGuard) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.sparseEmbedder = sparseEmbedder;
        this.tenancyStrategy = tenancyStrategy;
        this.denseVectorName = denseVectorName;
        this.sparseVectorName = sparseVectorName;
        this.denseTopK = denseTopK;
        this.sparseTopK = sparseTopK;
        this.rrfK = rrfK;
        this.rerankEnabled = rerankEnabled;
        this.rerankTopN = rerankTopN;
        this.reranker = reranker;
        this.tenantGuard = tenantGuard;
    }

    @Override
    public Uni<List<RetrievedChunk>> retrieve(String query, CorpusRef corpus, int maxResults, PayloadFilter filter) {
        return Uni.createFrom().deferred(() -> {
            tenantGuard.assertTenant(corpus.tenantId());

            String collection = tenancyStrategy.collectionName(corpus);
            Optional<Filter> tenantFilter = tenancyStrategy.tenantFilter(corpus);
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
                        .chain(embeddings -> executeQuery(collection, mergedFilter,
                            embeddings, maxResults))
                        .map(this::mapToChunks)
                        .chain(chunks -> maybeRerank(query, chunks, maxResults));
                });
        });
    }

    private QueryEmbeddings embedQuery(String query) {
        Embedding denseEmbedding = embeddingModel.embed(TextSegment.from(query)).content();
        Map<Integer, Float> sparseMap = sparseEmbedder != null
            ? sparseEmbedder.embed(query) : null;
        return new QueryEmbeddings(denseEmbedding, sparseMap);
    }

    private Uni<List<ScoredPoint>> executeQuery(String collection,
            Optional<Filter> tenantFilter, QueryEmbeddings embeddings, int maxResults) {
        int queryLimit = rerankEnabled && reranker != null
            ? Math.max(maxResults, rerankTopN)
            : maxResults;

        QueryPoints queryPoints;
        if (embeddings.sparse != null) {
            // Hybrid mode: dense + sparse prefetch with RRF fusion
            List<Float> sparseValues = new ArrayList<>(embeddings.sparse.size());
            List<Integer> sparseIndices = new ArrayList<>(embeddings.sparse.size());
            for (Map.Entry<Integer, Float> entry : embeddings.sparse.entrySet()) {
                sparseIndices.add(entry.getKey());
                sparseValues.add(entry.getValue());
            }

            PrefetchQuery.Builder densePrefetch = PrefetchQuery.newBuilder()
                .setQuery(QueryFactory.nearest(embeddings.dense.vectorAsList()))
                .setUsing(denseVectorName)
                .setLimit(denseTopK);
            tenantFilter.ifPresent(densePrefetch::setFilter);

            PrefetchQuery.Builder sparsePrefetch = PrefetchQuery.newBuilder()
                .setQuery(QueryFactory.nearest(sparseValues, sparseIndices))
                .setUsing(sparseVectorName)
                .setLimit(sparseTopK);
            tenantFilter.ifPresent(sparsePrefetch::setFilter);

            queryPoints = QueryPoints.newBuilder()
                .setCollectionName(collection)
                .addPrefetch(densePrefetch)
                .addPrefetch(sparsePrefetch)
                .setQuery(QueryFactory.rrf(Rrf.newBuilder().setK(rrfK).build()))
                .setLimit(queryLimit)
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .build();
        } else {
            // Dense-only mode: direct nearest-neighbor query
            QueryPoints.Builder builder = QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setQuery(QueryFactory.nearest(embeddings.dense.vectorAsList()))
                .setUsing(denseVectorName)
                .setLimit(queryLimit)
                .setWithPayload(WithPayloadSelectorFactory.enable(true));
            tenantFilter.ifPresent(builder::setFilter);
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
                if (!RESERVED_PAYLOAD_KEYS.contains(entry.getKey())
                        && entry.getValue().hasStringValue()) {
                    metadata.put(entry.getKey(), entry.getValue().getStringValue());
                }
            }
            chunks.add(new RetrievedChunk(content, sourceDocumentId,
                point.getScore(), Map.copyOf(metadata)));
        }
        return chunks;
    }

    private Uni<List<RetrievedChunk>> maybeRerank(String query,
            List<RetrievedChunk> chunks, int maxResults) {
        if (!rerankEnabled || reranker == null || chunks.isEmpty()) {
            chunks.sort((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()));
            return Uni.createFrom().item(Collections.unmodifiableList(chunks));
        }
        return Uni.createFrom().item(() -> {
            List<String> texts = new ArrayList<>(chunks.size());
            for (RetrievedChunk chunk : chunks) texts.add(chunk.content());
            List<RankedResult> ranked = reranker.rerank(query, texts);
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

    private record QueryEmbeddings(Embedding dense, Map<Integer, Float> sparse) {}
}
