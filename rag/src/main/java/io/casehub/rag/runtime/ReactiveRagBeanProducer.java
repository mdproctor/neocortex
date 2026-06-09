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
    @Inject SparseEmbedder sparseEmbedder;
    @Inject Instance<CrossEncoderReranker> rerankerInstance;
    @Inject CurrentPrincipal currentPrincipal;

    private int denseDimension;

    @PostConstruct
    void init() {
        denseDimension = embeddingModel.dimension();
    }

    @Produces
    @ApplicationScoped
    ReactiveQdrantCorpusStore corpusStore() {
        return new ReactiveQdrantCorpusStore(
            client, embeddingModel, sparseEmbedder,
            config.tenancyStrategy(),
            config.denseVectorName(), config.sparseVectorName(),
            denseDimension, currentPrincipal);
    }

    @Produces
    @ApplicationScoped
    ReactiveHybridCaseRetriever caseRetriever() {
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
