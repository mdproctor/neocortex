package io.casehub.neocortex.memory.mem0;

import io.casehub.neocortex.memory.*;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.casehub.neocortex.memory.testing.CaseMemoryStoreContractTest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;

import java.util.Set;

/**
 * Full {@link CaseMemoryStoreContractTest} suite against a real Mem0 OSS server.
 *
 * <p><strong>Currently disabled</strong> — requires Ollama in CI to serve the
 * {@code nomic-embed-text} embedding model. Mem0 OSS computes vector embeddings at write
 * time even when {@code infer:false}; without an embedding backend the container fails to start.
 *
 * <p><strong>To enable:</strong>
 * <ol>
 *   <li>Ensure Docker (or Podman with {@code DOCKER_HOST} set) is available in the CI runner.</li>
 *   <li>Ensure the runner has ≥ 4 GB RAM free for the Ollama + Mem0 containers.</li>
 *   <li>Remove the {@code @Disabled} annotation below.</li>
 *   <li>Run with {@code mvn verify -pl memory-mem0} — the failsafe plugin picks up {@code *IT.java}.</li>
 * </ol>
 *
 * <p>Tagged {@code @Tag("integration")} so IDEs and CI can filter it independently.
 * Excluded from default {@code mvn test} (surefire) — runs only under {@code mvn verify} (failsafe).
 */
@Disabled("Blocked: requires Ollama embedding backend in CI. Remove when platform#65 infra is ready.")
@Tag("integration")
@QuarkusTest
@ActivateRequestContext
// @QuarkusTestResource(Mem0ContainerResource.class)
// ↑ Uncomment when enabling — left commented because Quarkus eagerly starts @QuarkusTestResource
// annotations from ALL test classes, including @Disabled ones, which breaks the WireMock suite.
class CaseMemoryStoreContractIT extends CaseMemoryStoreContractTest {

    @Inject Mem0CaseMemoryStore mem0Store;
    @Inject FixedCurrentPrincipal principal;

    @BeforeEach
    void setup() {
        principal.setTenancyId(TENANT);
        principal.setCrossTenantAdmin(false);
        // Clean up data from the previous test. Mem0 has no transaction rollback;
        // we wipe all known entities across both tenants used by the contract suite.
        principal.setCrossTenantAdmin(true);
        mem0Store.eraseEntityAcrossTenants("entity-1", Set.of(TENANT, OTHER_TENANT));
        mem0Store.eraseEntityAcrossTenants("entity-2", Set.of(TENANT, OTHER_TENANT));
        // Restore non-admin state for the test body.
        principal.setCrossTenantAdmin(false);
    }

    @Override
    protected CaseMemoryStore store() {
        return mem0Store;
    }

    // ── Mem0-specific additional assertions ───────────────────────────────────

    // The contract suite covers the full CaseMemoryStore SPI.
    // Add Mem0-specific assertions here as the adapter gains capabilities
    // not expressible in the adapter-agnostic contract (e.g. RELEVANCE ordering
    // with real semantic search, infer:false text fidelity).
    //
    // For now the contract suite is sufficient — the WireMock unit tests (Mem0CaseMemoryStoreTest)
    // verify the HTTP protocol in detail; this class verifies stateful round-trip behaviour.
}
