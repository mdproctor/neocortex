package io.casehub.rag.runtime;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.inference.tasks.CrossEncoderReranker;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.qdrant.client.QdrantClient;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.Startup;
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
    @Inject Instance<CurrentPrincipal> currentPrincipalInstance;

    private EmbeddingModel effectiveEmbeddingModel() {
        return config.matryoshka().dimension().isPresent()
            ? new MatryoshkaEmbeddingModel(embeddingModel,
                config.matryoshka().dimension().getAsInt())
            : embeddingModel;
    }

    @Produces
    @ApplicationScoped
    ReactiveQdrantEmbeddingIngestor corpusStore() {
        SparseEmbedder sparseEmbedder = sparseEmbedderInstance.isResolvable()
            ? sparseEmbedderInstance.get() : null;
        CurrentPrincipal principal = currentPrincipalInstance.isResolvable()
            ? currentPrincipalInstance.get() : null;
        return new ReactiveQdrantEmbeddingIngestor(
            client, effectiveEmbeddingModel(), sparseEmbedder,
            config.tenancyStrategy(),
            config.denseVectorName(), config.sparseVectorName(),
            TenantGuard.of(principal),
            config.embeddingBatchSize(),
            config.quantization().type(),
            config.quantization().alwaysRam());
    }

    @Produces
    @ApplicationScoped
    ReactiveHybridCaseRetriever caseRetriever() {
        SparseEmbedder sparseEmbedder = sparseEmbedderInstance.isResolvable()
            ? sparseEmbedderInstance.get() : null;
        CrossEncoderReranker reranker = rerankerInstance.isResolvable()
            ? rerankerInstance.get() : null;
        CurrentPrincipal principal = currentPrincipalInstance.isResolvable()
            ? currentPrincipalInstance.get() : null;
        TenantGuard tenantGuard = TenantGuard.of(principal);
        return new ReactiveHybridCaseRetriever(
            client, effectiveEmbeddingModel(), sparseEmbedder,
            config.tenancyStrategy(),
            config.denseVectorName(), config.sparseVectorName(),
            config.retrieval().denseTopK(), config.retrieval().sparseTopK(),
            config.retrieval().rrfK(), config.retrieval().rerankEnabled(),
            config.retrieval().rerankTopN(), reranker, tenantGuard,
            config.quantization().type(),
            config.quantization().oversampling());
    }
}
