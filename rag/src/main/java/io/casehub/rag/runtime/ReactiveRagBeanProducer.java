package io.casehub.rag.runtime;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.inference.tasks.CrossEncoderReranker;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.qdrant.client.QdrantClient;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@IfBuildProperty(name = "casehub.rag.reactive.enabled", stringValue = "true")
@ApplicationScoped
@Startup
public class ReactiveRagBeanProducer {

    @Inject RagConfig config;
    @Inject QdrantClient client;
    @Inject EmbeddingModel embeddingModel;
    @Inject Instance<SparseEmbedder> sparseEmbedderInstance;
    @Inject Instance<CrossEncoderReranker> rerankerInstance;
    @Inject CurrentPrincipal currentPrincipal;

    private int denseDimension;

    @PostConstruct
    void init() {
        denseDimension = embeddingModel.dimension();
    }

    @Produces
    @ApplicationScoped
    ReactiveQdrantEmbeddingIngestor corpusStore() {
        SparseEmbedder sparseEmbedder = sparseEmbedderInstance.isResolvable()
            ? sparseEmbedderInstance.get() : null;
        return new ReactiveQdrantEmbeddingIngestor(
            client, embeddingModel, sparseEmbedder,
            config.tenancyStrategy(),
            config.denseVectorName(), config.sparseVectorName(),
            denseDimension, currentPrincipal);
    }

    @Produces
    @ApplicationScoped
    ReactiveHybridCaseRetriever caseRetriever() {
        SparseEmbedder sparseEmbedder = sparseEmbedderInstance.isResolvable()
            ? sparseEmbedderInstance.get() : null;
        CrossEncoderReranker reranker = rerankerInstance.isResolvable()
            ? rerankerInstance.get() : null;
        return new ReactiveHybridCaseRetriever(
            client, embeddingModel, sparseEmbedder,
            config.tenancyStrategy(),
            config.denseVectorName(), config.sparseVectorName(),
            config.retrieval().denseTopK(), config.retrieval().sparseTopK(),
            config.retrieval().rrfK(), config.retrieval().rerankEnabled(),
            config.retrieval().rerankTopN(), reranker, currentPrincipal);
    }
}
