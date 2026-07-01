package io.casehub.neocortex.rag.runtime;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class QdrantClientProducer {

    private final RagConfig config;

    @Inject
    public QdrantClientProducer(RagConfig config) {
        this.config = config;
    }

    @Produces
    @ApplicationScoped
    QdrantClient qdrantClient() {
        var grpcBuilder = QdrantGrpcClient.newBuilder(
            config.qdrant().host(),
            config.qdrant().port(),
            config.qdrant().useTls());
        config.qdrant().apiKey().ifPresent(grpcBuilder::withApiKey);
        return new QdrantClient(grpcBuilder.build());
    }

    void close(@Disposes QdrantClient client) {
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }
}
