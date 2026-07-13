package io.casehub.neocortex.memory.cbr.qdrant;

import io.casehub.neocortex.memory.*;
import io.casehub.neocortex.memory.cbr.*;
import static io.casehub.neocortex.memory.cbr.FeatureValue.*;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CbrReconciliationServiceTest {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:v1.18.0")
        .withExposedPorts(6334);

    private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
    private static final MemoryDomain CBR = new MemoryDomain("cbr");
    private static final String TENANT = "test-tenant";
    private static final String ENTITY = "test-entity";

    private QdrantCbrCaseMemoryStore cbrStore;
    private InMemoryDelegateStore delegate;
    private CbrReconciliationService reconciler;
    private CbrCollectionManager collectionManager;
    private QdrantCbrConfig config;

    @BeforeEach
    void setUp() {
        int testId = TEST_COUNTER.incrementAndGet();
        config = testConfig(testId);
        QdrantClient client = new QdrantClient(
            QdrantGrpcClient.newBuilder(qdrant.getHost(), qdrant.getMappedPort(6334), false).build());
        collectionManager = new CbrCollectionManager(client, config);
        delegate = new InMemoryDelegateStore();
        cbrStore = new QdrantCbrCaseMemoryStore(collectionManager, null, config, delegate);
        reconciler = new CbrReconciliationService(collectionManager, null, config, delegate, null);
    }

    @AfterEach
    void tearDown() {
        if (collectionManager != null) {
            try { collectionManager.client().close(); } catch (Exception ignored) {}
        }
    }

    @Test
    void reconcile_noDelegate_returnsNoOp() {
        var service = new CbrReconciliationService(collectionManager,
            (dev.langchain4j.model.embedding.EmbeddingModel) null, config,
            (io.casehub.neocortex.memory.CaseMemoryStore) null, null);
        var result = service.reconcile("test-type", TENANT);
        assertThat(result.orphansRemoved()).isZero();
        assertThat(result.entriesReindexed()).isZero();
    }

    @Test
    void reconcile_delegateWithoutScan_returnsNoOp() {
        var noScanDelegate = new NoScanDelegateStore();
        var service = new CbrReconciliationService(collectionManager, null, config, noScanDelegate, null);
        var result = service.reconcile("test-type", TENANT);
        assertThat(result.orphansRemoved()).isZero();
        assertThat(result.entriesReindexed()).isZero();
    }

    @Test
    void reconcile_consistentState_noChanges() {
        cbrStore.registerSchema(CbrFeatureSchema.of("game",
            FeatureField.categorical("race")));
        cbrStore.store(new FeatureVectorCbrCase("p1", "s1", null, null,
            Map.of("race", string("Zerg"))), "game", ENTITY, CBR, TENANT, "case-1");
        cbrStore.store(new FeatureVectorCbrCase("p2", "s2", null, null,
            Map.of("race", string("Protoss"))), "game", ENTITY, CBR, TENANT, "case-2");

        var result = reconciler.reconcile("game", TENANT);
        assertThat(result.orphansRemoved()).isZero();
        assertThat(result.entriesReindexed()).isZero();
        assertThat(result.errors()).isZero();
    }

    @Test
    void reconcile_missingFromQdrant_reindexes() {
        // Store via cbrStore (writes to both delegate and Qdrant)
        cbrStore.registerSchema(CbrFeatureSchema.of("reindex-type",
            FeatureField.categorical("cat")));
        cbrStore.store(new FeatureVectorCbrCase("p1", "s1", null, null,
            Map.of("cat", string("A"))), "reindex-type", ENTITY, CBR, TENANT, "case-1");

        // Delete the Qdrant collection to simulate data loss
        String collection = collectionManager.collectionName("reindex-type");
        try {
            collectionManager.client().deleteCollectionAsync(collection).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        var result = reconciler.reconcile("reindex-type", TENANT);
        assertThat(result.entriesReindexed()).isEqualTo(1);
        assertThat(result.orphansRemoved()).isZero();

        // Verify the point is back in Qdrant
        var retrieved = cbrStore.retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "reindex-type", Map.of("cat", string("A")), 5),
            FeatureVectorCbrCase.class);
        assertThat(retrieved).hasSize(1);
    }

    @Test
    void reconcile_orphanInQdrant_removes() {
        cbrStore.registerSchema(CbrFeatureSchema.of("orphan-type",
            FeatureField.categorical("cat")));
        cbrStore.store(new FeatureVectorCbrCase("p1", "s1", null, null,
            Map.of("cat", string("A"))), "orphan-type", ENTITY, CBR, TENANT, "case-1");

        // Remove from delegate (simulating delegate erasure without Qdrant cleanup)
        delegate.eraseAll();

        var result = reconciler.reconcile("orphan-type", TENANT);
        assertThat(result.orphansRemoved()).isEqualTo(1);
        assertThat(result.entriesReindexed()).isZero();

        // Verify Qdrant is empty
        var retrieved = cbrStore.retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "orphan-type", Map.of("cat", string("A")), 5),
            FeatureVectorCbrCase.class);
        assertThat(retrieved).isEmpty();
    }

    @Test
    void reconcile_emptyCollection_reindexesAll() {
        // Store entries only in delegate, not in Qdrant
        delegate.storeDirectly("case-1", ENTITY, CBR, TENANT,
            new FeatureVectorCbrCase("p1", "s1", null, null, Map.of("cat", string("A"))), "reindex-all");

        var result = reconciler.reconcile("reindex-all", TENANT);
        assertThat(result.entriesReindexed()).isEqualTo(1);
    }

    @Test
    void reconcile_deserializationFailure_incrementsErrorCount() {
        // Store a memory directly in delegate with missing 'solution' attribute
        delegate.storeRaw("case-err", ENTITY, CBR, TENANT, "problem text",
            Map.of(CbrAttributeKeys.CBR_CASE_TYPE, "err-type",
                   CbrAttributeKeys.CBR_TYPE, FeatureVectorCbrCase.CBR_TYPE));
        // Missing solution → CbrMemoryDeserializer returns empty

        var result = reconciler.reconcile("err-type", TENANT);
        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.entriesReindexed()).isZero();
    }

    @Test
    void discoverTenants_returnsTenantsFromDelegate() {
        cbrStore.registerSchema(CbrFeatureSchema.of("disc-type", FeatureField.categorical("cat")));
        cbrStore.store(new FeatureVectorCbrCase("p1", "s1", null, null,
            Map.of("cat", string("A"))), "disc-type", ENTITY, CBR, "tenant-x", "case-1");
        cbrStore.store(new FeatureVectorCbrCase("p2", "s2", null, null,
            Map.of("cat", string("B"))), "disc-type", ENTITY, CBR, "tenant-y", "case-2");

        Set<String> tenants = reconciler.discoverTenants("disc-type");
        assertThat(tenants).containsExactlyInAnyOrder("tenant-x", "tenant-y");
    }

    @Test
    void discoverTenants_noDelegate_throwsIllegalState() {
        var noDelegate = new CbrReconciliationService(collectionManager,
            (dev.langchain4j.model.embedding.EmbeddingModel) null, config,
            (io.casehub.neocortex.memory.CaseMemoryStore) null, null);
        assertThatThrownBy(() -> noDelegate.discoverTenants("type"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void discoverTenants_delegateWithoutCapability_throwsMemoryCapabilityException() {
        var noDiscoverDelegate = new NoScanDelegateStore();
        var service = new CbrReconciliationService(collectionManager, null, config, noDiscoverDelegate, null);
        assertThatThrownBy(() -> service.discoverTenants("type"))
            .isInstanceOf(MemoryCapabilityException.class)
            .hasMessageContaining("DISCOVER_TENANTS");
    }

    @Test
    void reconcileAll_reconcilesMultipleTenants() {
        cbrStore.registerSchema(CbrFeatureSchema.of("multi-type", FeatureField.categorical("cat")));
        cbrStore.store(new FeatureVectorCbrCase("p1", "s1", null, null,
            Map.of("cat", string("A"))), "multi-type", ENTITY, CBR, "t1", "case-1");
        cbrStore.store(new FeatureVectorCbrCase("p2", "s2", null, null,
            Map.of("cat", string("B"))), "multi-type", ENTITY, CBR, "t2", "case-2");

        // Delete collection to force reindex
        String collection = collectionManager.collectionName("multi-type");
        try { collectionManager.client().deleteCollectionAsync(collection).get(); } catch (Exception e) { throw new RuntimeException(e); }

        var results = reconciler.reconcileAll("multi-type", Set.of("t1", "t2"));
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r -> r.entriesReindexed() > 0 && r.errors() == 0);
    }

    @Test
    void reconcileAll_autoDiscovery() {
        cbrStore.registerSchema(CbrFeatureSchema.of("auto-type", FeatureField.categorical("cat")));
        cbrStore.store(new FeatureVectorCbrCase("p1", "s1", null, null,
            Map.of("cat", string("A"))), "auto-type", ENTITY, CBR, "auto-t1", "case-1");
        cbrStore.store(new FeatureVectorCbrCase("p2", "s2", null, null,
            Map.of("cat", string("B"))), "auto-type", ENTITY, CBR, "auto-t2", "case-2");

        // Delete collection
        String collection = collectionManager.collectionName("auto-type");
        try { collectionManager.client().deleteCollectionAsync(collection).get(); } catch (Exception e) { throw new RuntimeException(e); }

        var results = reconciler.reconcileAll("auto-type");
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r -> r.entriesReindexed() > 0);
    }

    @Test
    void reconcileAll_partialFailure_capturesErrorsAndSuccesses() {
        cbrStore.registerSchema(CbrFeatureSchema.of("partial-type", FeatureField.categorical("cat")));
        cbrStore.store(new FeatureVectorCbrCase("p1", "s1", null, null,
            Map.of("cat", string("A"))), "partial-type", ENTITY, CBR, "good-tenant", "case-1");

        // Store invalid memory for bad tenant (missing 'solution' attribute)
        delegate.storeRaw("case-bad", ENTITY, CBR, "bad-tenant", "problem text",
            Map.of(CbrAttributeKeys.CBR_CASE_TYPE, "partial-type",
                   CbrAttributeKeys.CBR_TYPE, FeatureVectorCbrCase.CBR_TYPE));
        // Missing solution → CbrMemoryDeserializer returns empty → reconciliation error

        // Delete collection to force reindex for good-tenant
        String collection = collectionManager.collectionName("partial-type");
        try { collectionManager.client().deleteCollectionAsync(collection).get(); } catch (Exception e) { throw new RuntimeException(e); }

        var results = reconciler.reconcileAll("partial-type", Set.of("good-tenant", "bad-tenant"));
        assertThat(results).hasSize(2);

        // One tenant should have reindexed successfully
        assertThat(results).anyMatch(r -> r.entriesReindexed() > 0 && r.errors() == 0);
        // One tenant should have an error
        assertThat(results).anyMatch(r -> r.errors() > 0);
    }

    // --- Test doubles ---

    private QdrantCbrConfig testConfig(int testId) {
        return new QdrantCbrConfig() {
            @Override public String host() { return qdrant.getHost(); }
            @Override public int port() { return qdrant.getMappedPort(6334); }
            @Override public Optional<String> apiKey() { return Optional.empty(); }
            @Override public boolean useTls() { return false; }
            @Override public String collectionPrefix() { return "cbr_recon_" + testId; }
            @Override public String denseVectorName() { return "dense"; }
            @Override public int maxRetries() { return 3; }
            @Override public boolean allowDimensionMigration() { return false; }
            @Override public int oversampleFactor() { return 3; }
            @Override public int overFetchLimit() { return 200; }
        };
    }

    /**
     * In-memory CaseMemoryStore that supports SCAN and DISCOVER_TENANTS for testing reconciliation.
     */
    static class InMemoryDelegateStore implements CaseMemoryStore {
        private final List<Memory> entries = new ArrayList<>();

        @Override public String store(MemoryInput input) {
            String id = UUID.randomUUID().toString();
            entries.add(new Memory(id, input.entityId(), input.domain(), input.tenantId(),
                input.caseId(), input.text(), input.attributes(), Instant.now()));
            return id;
        }
        @Override public List<Memory> query(MemoryQuery q) { return List.of(); }
        @Override public int erase(EraseRequest r) { return 0; }

        @Override
        public Set<MemoryCapability> capabilities() {
            return Set.of(MemoryCapability.SCAN, MemoryCapability.DISCOVER_TENANTS);
        }

        @Override
        public List<Memory> scan(MemoryScanRequest request) {
            return entries.stream()
                .filter(m -> m.tenantId().equals(request.tenantId()))
                .filter(m -> request.domain() == null || m.domain().name().equals(request.domain()))
                .filter(m -> request.attributeKey() == null
                    || request.attributeValue().equals(m.attributes().get(request.attributeKey())))
                .filter(m -> request.afterMemoryId() == null
                    || m.memoryId().compareTo(request.afterMemoryId()) > 0)
                .sorted(Comparator.comparing(Memory::memoryId))
                .limit(request.limit())
                .toList();
        }

        @Override
        public Set<String> discoverTenants(String attributeKey, String attributeValue) {
            return entries.stream()
                .filter(m -> attributeKey == null
                    || attributeValue.equals(m.attributes().get(attributeKey)))
                .map(Memory::tenantId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        void storeDirectly(String caseId, String entityId, MemoryDomain domain,
                           String tenantId, CbrCase cbrCase, String caseType) {
            MemoryInput input = CbrMemorySerializer.serialize(
                cbrCase, entityId, domain, tenantId, caseId, caseType);
            store(input);
        }

        void storeRaw(String caseId, String entityId, MemoryDomain domain,
                      String tenantId, String text, Map<String, String> attributes) {
            String id = UUID.randomUUID().toString();
            entries.add(new Memory(id, entityId, domain, tenantId, caseId,
                text, attributes, Instant.now()));
        }

        void eraseAll() { entries.clear(); }
    }

    static class NoScanDelegateStore implements CaseMemoryStore {
        @Override public String store(MemoryInput i) { return ""; }
        @Override public List<Memory> query(MemoryQuery q) { return List.of(); }
        @Override public int erase(EraseRequest r) { return 0; }
    }
}
