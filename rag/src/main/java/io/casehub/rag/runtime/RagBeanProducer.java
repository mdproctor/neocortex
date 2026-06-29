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
    @Inject Instance<CurrentPrincipal> currentPrincipalInstance;

    private EmbeddingModel effectiveEmbeddingModel() {
        return config.matryoshka().dimension().isPresent()
            ? new MatryoshkaEmbeddingModel(embeddingModel,
                config.matryoshka().dimension().getAsInt())
            : embeddingModel;
    }

    private SparseEmbedder resolveSparseEmbedder() {
        return sparseEmbedderInstance.isResolvable()
            ? sparseEmbedderInstance.get() : null;
    }

    private TenantGuard resolveTenantGuard() {
        CurrentPrincipal principal = currentPrincipalInstance.isResolvable()
            ? currentPrincipalInstance.get() : null;
        return TenantGuard.of(principal);
    }

    private CrossEncoderReranker resolveReranker() {
        return rerankerInstance.isResolvable()
            ? rerankerInstance.get() : null;
    }

    @Produces
    @ApplicationScoped
    QdrantEmbeddingIngestor corpusStore() {
        return new QdrantEmbeddingIngestor(client, effectiveEmbeddingModel(),
            resolveSparseEmbedder(), resolveTenantGuard(), config);
    }

    @Produces
    @ApplicationScoped
    HybridCaseRetriever caseRetriever() {
        return new HybridCaseRetriever(client, effectiveEmbeddingModel(),
            resolveSparseEmbedder(), resolveTenantGuard(),
            resolveReranker(), config);
    }
}
