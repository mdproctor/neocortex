package io.casehub.rag.runtime;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.inference.tasks.CrossEncoderReranker;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.qdrant.client.QdrantClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class RagBeanProducer {

    @Inject RagConfig config;
    @Inject QdrantClient client;
    @Inject EmbeddingModel embeddingModel;
    @Inject Instance<SparseEmbedder> sparseEmbedderInstance;
    @Inject Instance<CrossEncoderReranker> rerankerInstance;
    @Inject CurrentPrincipal currentPrincipal;

    @Produces
    @ApplicationScoped
    QdrantEmbeddingIngestor corpusStore() {
        SparseEmbedder sparseEmbedder = sparseEmbedderInstance.isResolvable()
            ? sparseEmbedderInstance.get() : null;
        return new QdrantEmbeddingIngestor(
            client,
            embeddingModel,
            sparseEmbedder,
            config.tenancyStrategy(),
            config.denseVectorName(),
            config.sparseVectorName(),
            currentPrincipal);
    }

    @Produces
    @ApplicationScoped
    HybridCaseRetriever caseRetriever() {
        SparseEmbedder sparseEmbedder = sparseEmbedderInstance.isResolvable()
            ? sparseEmbedderInstance.get() : null;
        CrossEncoderReranker reranker = rerankerInstance.isResolvable()
            ? rerankerInstance.get() : null;
        return new HybridCaseRetriever(
            client,
            embeddingModel,
            sparseEmbedder,
            config.tenancyStrategy(),
            config.denseVectorName(),
            config.sparseVectorName(),
            config.retrieval().denseTopK(),
            config.retrieval().sparseTopK(),
            config.retrieval().rrfK(),
            config.retrieval().rerankEnabled(),
            config.retrieval().rerankTopN(),
            reranker,
            currentPrincipal);
    }
}
