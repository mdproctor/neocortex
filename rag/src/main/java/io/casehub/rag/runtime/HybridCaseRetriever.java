package io.casehub.rag.runtime;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.inference.tasks.CrossEncoderReranker;
import io.casehub.inference.tasks.RankedResult;
import io.casehub.rag.CaseRetriever;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public class HybridCaseRetriever implements CaseRetriever {

    private final QdrantClient client;
    private final EmbeddingModel embeddingModel;
    private final SparseEmbedder sparseEmbedder;
    private final TenantGuard tenantGuard;
    private final CrossEncoderReranker reranker;
    private final RagConfig config;

    HybridCaseRetriever(
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
    public List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus, int maxResults, PayloadFilter filter) {
        tenantGuard.assertTenant(corpus.tenantId());

        String collection = config.tenancyStrategy().collectionName(corpus);
        Optional<Filter> tenantFilter = config.tenancyStrategy().tenantFilter(corpus);
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
        Embedding denseEmbedding = embeddingModel.embed(TextSegment.from(query.searchText())).content();

        // Determine query limit: fetch more candidates if reranking
        int queryLimit = config.retrieval().rerankEnabled() && reranker != null
            ? Math.max(maxResults, config.retrieval().rerankTopN())
            : maxResults;

        QueryPoints queryPoints;
        boolean useRrf = sparseEmbedder != null || config.bm25Enabled();
        if (useRrf) {
            QueryPoints.Builder qb = QueryPoints.newBuilder()
                .setCollectionName(collection);

            // Dense prefetch (always present in RRF mode)
            PrefetchQuery.Builder densePrefetch = PrefetchQuery.newBuilder()
                .setQuery(QueryFactory.nearest(denseEmbedding.vectorAsList()))
                .setUsing(config.denseVectorName())
                .setLimit(config.retrieval().denseTopK());
            if (config.quantization().type() != DenseQuantization.NONE && config.quantization().oversampling().isPresent()) {
                densePrefetch.setParams(quantizationSearchParams());
            }
            mergedFilter.ifPresent(densePrefetch::setFilter);
            qb.addPrefetch(densePrefetch);

            // SPLADE prefetch (when available)
            if (sparseEmbedder != null) {
                Map<Integer, Float> sparseMap = sparseEmbedder.embed(query.text());
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
                .setQuery(QueryFactory.nearest(denseEmbedding.vectorAsList()))
                .setUsing(config.denseVectorName())
                .setLimit(queryLimit)
                .setWithPayload(WithPayloadSelectorFactory.enable(true));
            if (config.quantization().type() != DenseQuantization.NONE && config.quantization().oversampling().isPresent()) {
                builder.setParams(quantizationSearchParams());
            }
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
                if (!QdrantPointBuilder.RESERVED_PAYLOAD_KEYS.contains(entry.getKey())
                        && entry.getValue().hasStringValue()) {
                    metadata.put(entry.getKey(), entry.getValue().getStringValue());
                }
            }

            chunks.add(new RetrievedChunk(content, sourceDocumentId,
                point.getScore(), Map.copyOf(metadata)));
        }

        // Optional cross-encoder reranking
        if (config.retrieval().rerankEnabled() && reranker != null && !chunks.isEmpty()) {
            List<String> texts = new ArrayList<>(chunks.size());
            for (RetrievedChunk chunk : chunks) {
                texts.add(chunk.content());
            }

            List<RankedResult> ranked = reranker.rerank(query.text(), texts);

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

    private SearchParams quantizationSearchParams() {
        return SearchParams.newBuilder()
            .setQuantization(QuantizationSearchParams.newBuilder()
                .setOversampling(config.quantization().oversampling().getAsDouble())
                .setRescore(true)
                .build())
            .build();
    }
}
