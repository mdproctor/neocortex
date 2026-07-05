package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.inference.MatryoshkaMultiModalEmbedder;
import io.casehub.neocortex.inference.MultiModalEmbedder;
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
    @Inject MultiModalEmbedder embedder;
    @Inject Instance<CurrentPrincipal> currentPrincipalInstance;

    private MultiModalEmbedder effectiveEmbedder() {
        return MatryoshkaMultiModalEmbedder.wrapIfNeeded(embedder,
            config.matryoshka().dimension());
    }

    private TenantGuard resolveTenantGuard() {
        CurrentPrincipal principal = currentPrincipalInstance.isResolvable()
            ? currentPrincipalInstance.get() : null;
        return TenantGuard.of(principal);
    }

    @Produces
    @ApplicationScoped
    QdrantEmbeddingIngestor corpusStore() {
        return new QdrantEmbeddingIngestor(client, effectiveEmbedder(),
            resolveTenantGuard(), config);
    }

    @Produces
    @ApplicationScoped
    HybridCaseRetriever caseRetriever() {
        return new HybridCaseRetriever(client, effectiveEmbedder(),
            resolveTenantGuard(), config);
    }
}
