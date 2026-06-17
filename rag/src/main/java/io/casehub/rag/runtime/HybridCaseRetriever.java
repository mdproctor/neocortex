package io.casehub.rag.runtime;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.inference.tasks.CrossEncoderReranker;
import io.casehub.inference.tasks.RankedResult;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.memory.MemoryPermissions;
import io.casehub.rag.CaseRetriever;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public class HybridCaseRetriever implements CaseRetriever {

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
    private final CurrentPrincipal currentPrincipal;

    public HybridCaseRetriever(
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
            CurrentPrincipal currentPrincipal) {
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
        this.currentPrincipal = currentPrincipal;
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, CorpusRef corpus, int maxResults, PayloadFilter filter) {
        MemoryPermissions.assertTenant(corpus.tenantId(), currentPrincipal, RequestContextCheck.isActive());

        String collection = tenancyStrategy.collectionName(corpus);
        Optional<Filter> tenantFilter = tenancyStrategy.tenantFilter(corpus);
        Optional<Filter> payloadFilter = PayloadFilterTranslator.toQdrantFilter(filter);

        Filter.Builder combined = Filter.newBuilder();
        tenantFilter.ifPresent(tf -> combined.addAllMust(tf.getMustList()));
        payloadFilter.ifPresent(pf -> combined.addAllMust(pf.getMustList()));
        Optional<Filter> mergedFilter = combined.getMustCount() > 0
            ? Optional.of(combined.build()) : Optional.empty();

        // Check collection exists — return empty if not
        if (!collectionExists(collection)) {
            return List.of();
        }

        // Embed query: dense (always) + sparse (when available)
        Embedding denseEmbedding = embeddingModel.embed(TextSegment.from(query)).content();

        // Determine query limit: fetch more candidates if reranking
        int queryLimit = rerankEnabled && reranker != null
            ? Math.max(maxResults, rerankTopN)
            : maxResults;

        QueryPoints queryPoints;
        if (sparseEmbedder != null) {
            // Hybrid mode: dense + sparse prefetch with RRF fusion
            Map<Integer, Float> sparseMap = sparseEmbedder.embed(query);
            List<Float> sparseValues = new ArrayList<>(sparseMap.size());
            List<Integer> sparseIndices = new ArrayList<>(sparseMap.size());
            for (Map.Entry<Integer, Float> entry : sparseMap.entrySet()) {
                sparseIndices.add(entry.getKey());
                sparseValues.add(entry.getValue());
            }

            PrefetchQuery.Builder densePrefetch = PrefetchQuery.newBuilder()
                .setQuery(QueryFactory.nearest(denseEmbedding.vectorAsList()))
                .setUsing(denseVectorName)
                .setLimit(denseTopK);
            mergedFilter.ifPresent(densePrefetch::setFilter);

            PrefetchQuery.Builder sparsePrefetch = PrefetchQuery.newBuilder()
                .setQuery(QueryFactory.nearest(sparseValues, sparseIndices))
                .setUsing(sparseVectorName)
                .setLimit(sparseTopK);
            mergedFilter.ifPresent(sparsePrefetch::setFilter);

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
                .setQuery(QueryFactory.nearest(denseEmbedding.vectorAsList()))
                .setUsing(denseVectorName)
                .setLimit(queryLimit)
                .setWithPayload(WithPayloadSelectorFactory.enable(true));
            mergedFilter.ifPresent(builder::setFilter);
            queryPoints = builder.build();
        }

        // Execute query
        List<ScoredPoint> scoredPoints = executeQuery(queryPoints);

        // Map ScoredPoint results to RetrievedChunk
        List<RetrievedChunk> chunks = new ArrayList<>(scoredPoints.size());
        for (ScoredPoint point : scoredPoints) {
            Map<String, Value> payload = point.getPayloadMap();

            String content = extractStringPayload(payload, "content");
            String sourceDocumentId = extractStringPayload(payload, "sourceDocumentId");

            if (content == null || sourceDocumentId == null) {
                continue; // skip malformed points
            }

            // Extract remaining payload entries as metadata (excluding reserved keys)
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

        // Optional cross-encoder reranking
        if (rerankEnabled && reranker != null && !chunks.isEmpty()) {
            List<String> texts = new ArrayList<>(chunks.size());
            for (RetrievedChunk chunk : chunks) {
                texts.add(chunk.content());
            }

            List<RankedResult> ranked = reranker.rerank(query, texts);

            List<RetrievedChunk> reranked = new ArrayList<>(Math.min(ranked.size(), maxResults));
            for (int i = 0; i < Math.min(ranked.size(), maxResults); i++) {
                RankedResult r = ranked.get(i);
                RetrievedChunk original = chunks.get(r.originalIndex());
                reranked.add(new RetrievedChunk(
                    original.content(), original.sourceDocumentId(),
                    r.score(), original.metadata()));
            }
            return Collections.unmodifiableList(reranked);
        }

        // Sort by descending relevance and return
        chunks.sort((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()));
        return Collections.unmodifiableList(chunks);
    }

    private boolean collectionExists(String collection) {
        try {
            return client.collectionExistsAsync(collection).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted checking collection existence", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to check collection existence", e.getCause());
        }
    }

    private List<ScoredPoint> executeQuery(QueryPoints queryPoints) {
        try {
            return client.queryAsync(queryPoints).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during query", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Query failed", e.getCause());
        }
    }

    private static String extractStringPayload(Map<String, Value> payload, String key) {
        Value value = payload.get(key);
        if (value != null && value.hasStringValue()) {
            return value.getStringValue();
        }
        return null;
    }
}
