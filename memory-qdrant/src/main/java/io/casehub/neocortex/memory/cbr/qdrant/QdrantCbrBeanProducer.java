package io.casehub.neocortex.memory.cbr.qdrant;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class QdrantCbrBeanProducer {

    private final QdrantCbrConfig config;
    private volatile QdrantClient client;
    private volatile CbrCollectionManager collectionManager;

    @Inject
    Instance<EmbeddingModel> embeddingModelInstance;

    @Inject
    Instance<CaseMemoryStore> delegateInstance;

    @Inject
    public QdrantCbrBeanProducer(QdrantCbrConfig config) {
        this.config = config;
    }

    private CbrCollectionManager collectionManager() {
        if (collectionManager == null) {
            var grpcBuilder = QdrantGrpcClient.newBuilder(
                config.host(), config.port(), config.useTls());
            config.apiKey().ifPresent(grpcBuilder::withApiKey);
            client = new QdrantClient(grpcBuilder.build());
            collectionManager = new CbrCollectionManager(client, config);
        }
        return collectionManager;
    }

    @Produces
    @ApplicationScoped
    CbrCaseMemoryStore cbrCaseMemoryStore() {
        EmbeddingModel embeddingModel = embeddingModelInstance.isResolvable()
            ? embeddingModelInstance.get() : null;
        CaseMemoryStore delegate = delegateInstance.isResolvable()
            ? delegateInstance.get() : null;
        return new QdrantCbrCaseMemoryStore(collectionManager(), embeddingModel, config, delegate);
    }

    @Produces
    @ApplicationScoped
    CbrReconciliationService cbrReconciliationService() {
        EmbeddingModel embeddingModel = embeddingModelInstance.isResolvable()
            ? embeddingModelInstance.get() : null;
        CaseMemoryStore delegate = delegateInstance.isResolvable()
            ? delegateInstance.get() : null;
        return new CbrReconciliationService(collectionManager(), embeddingModel, config, delegate);
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
