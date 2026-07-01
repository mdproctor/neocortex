package io.casehub.neocortex.memory.cbr.qdrant;

import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.testing.CbrCaseMemoryStoreContractTest;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Testcontainers
class QdrantCbrCaseMemoryStoreTest extends CbrCaseMemoryStoreContractTest {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:v1.18.0")
        .withExposedPorts(6334);

    /** Counter for unique collection prefixes per test to avoid cross-test pollution. */
    private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

    private QdrantCbrCaseMemoryStore qdrantStore;

    @Override
    protected CbrCaseMemoryStore store() {
        if (qdrantStore == null) {
            QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder(
                    qdrant.getHost(),
                    qdrant.getMappedPort(6334),
                    false
                ).build()
            );

            int testId = TEST_COUNTER.incrementAndGet();
            QdrantCbrConfig config = new QdrantCbrConfig() {
                @Override public String host() { return qdrant.getHost(); }
                @Override public int port() { return qdrant.getMappedPort(6334); }
                @Override public Optional<String> apiKey() { return Optional.empty(); }
                @Override public boolean useTls() { return false; }
                @Override public String collectionPrefix() { return "cbr_test_" + testId; }
                @Override public String denseVectorName() { return "dense"; }
                @Override public int maxRetries() { return 3; }
            };

            CbrCollectionManager collectionManager = new CbrCollectionManager(client, config);

            // No embedding model — payload-filter-only mode
            // No delegate — Qdrant-only mode for contract test
            qdrantStore = new QdrantCbrCaseMemoryStore(collectionManager, null, config, null);
        }
        return qdrantStore;
    }
}
