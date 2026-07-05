package io.casehub.neocortex.memory.cbr.qdrant;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class QdrantCbrBeanProducer {

    private final QdrantCbrConfig config;
    private volatile QdrantClient client;

    @Inject
    public QdrantCbrBeanProducer(QdrantCbrConfig config) {
        this.config = config;
    }

    @Produces
    @ApplicationScoped
    CbrCollectionManager cbrCollectionManager() {
        var grpcBuilder = QdrantGrpcClient.newBuilder(
            config.host(), config.port(), config.useTls());
        config.apiKey().ifPresent(grpcBuilder::withApiKey);
        client = new QdrantClient(grpcBuilder.build());
        return new CbrCollectionManager(client, config);
    }

    @PreDestroy
    void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }
}
