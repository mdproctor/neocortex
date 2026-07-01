package io.casehub.rag.runtime;

import io.casehub.inference.MatryoshkaMultiModalEmbedder;
import io.casehub.inference.MultiModalEmbedder;
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
    @Inject MultiModalEmbedder embedder;
    @Inject Instance<CurrentPrincipal> currentPrincipalInstance;

    private MultiModalEmbedder effectiveEmbedder() {
        return config.matryoshka().dimension().isPresent()
            ? new MatryoshkaMultiModalEmbedder(embedder,
                config.matryoshka().dimension().getAsInt())
            : embedder;
    }

    private TenantGuard resolveTenantGuard() {
        CurrentPrincipal principal = currentPrincipalInstance.isResolvable()
            ? currentPrincipalInstance.get() : null;
        return TenantGuard.of(principal);
    }

    @Produces
    @ApplicationScoped
    ReactiveQdrantEmbeddingIngestor corpusStore() {
        return new ReactiveQdrantEmbeddingIngestor(client, effectiveEmbedder(),
            resolveTenantGuard(), config);
    }

    @Produces
    @ApplicationScoped
    ReactiveHybridCaseRetriever caseRetriever() {
        return new ReactiveHybridCaseRetriever(client, effectiveEmbedder(),
            resolveTenantGuard(), config);
    }
}
