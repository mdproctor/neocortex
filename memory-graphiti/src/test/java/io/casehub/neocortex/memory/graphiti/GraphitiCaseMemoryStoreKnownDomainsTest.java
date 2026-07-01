package io.casehub.neocortex.memory.graphiti;

import io.casehub.neocortex.memory.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@ActivateRequestContext
@TestProfile(KnownDomainsTestProfile.class)
class GraphitiCaseMemoryStoreKnownDomainsTest {

    static WireMockServer wireMock;

    static final String TENANT   = "tenant-1";
    static final String ENTITY   = "actor-1";
    static final MemoryDomain DOMAIN = new MemoryDomain("investigation");
    static final String GROUP_ID = TENANT + "::" + ENTITY + "::" + DOMAIN.name();

    @Inject GraphCaseMemoryStore store;
    @Inject FixedCurrentPrincipal principal;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().port(39201));
        wireMock.start();
        WireMock.configureFor("localhost", 39201);
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        principal.setTenancyId(TENANT);
        principal.setCrossTenantAdmin(false);
    }

    @Test
    void eraseEntity_with_known_domains_deletes_each_domain_group() {
        wireMock.stubFor(get(urlPathEqualTo("/episodes/" + GROUP_ID))
            .willReturn(okJson("""
                [
                  {"uuid":"ep-1","content":"a","created_at":"2026-01-01T00:00:00Z","group_id":"%s"},
                  {"uuid":"ep-2","content":"b","created_at":"2026-01-02T00:00:00Z","group_id":"%s"},
                  {"uuid":"ep-3","content":"c","created_at":"2026-01-03T00:00:00Z","group_id":"%s"}
                ]
                """.formatted(GROUP_ID, GROUP_ID, GROUP_ID))));
        wireMock.stubFor(delete(urlEqualTo("/group/" + GROUP_ID))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":true,\"message\":\"deleted\"}")));

        final int count = store.eraseEntity(ENTITY, TENANT);

        assertEquals(3, count);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/episodes/" + GROUP_ID)));
        wireMock.verify(deleteRequestedFor(urlEqualTo("/group/" + GROUP_ID)));
    }

    @Test
    void eraseEntity_with_known_domains_getEpisodes_404_returns_zero() {
        // No stubs — WireMock returns 404 by default for all requests.
        // eraseGroup() catches GET 404 → returns 0; deleteGroup() is never called.
        final int count = store.eraseEntity(ENTITY, TENANT);
        assertEquals(0, count);
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/episodes/" + GROUP_ID)));
        wireMock.verify(0, deleteRequestedFor(anyUrl()));
    }

    @Test
    void eraseEntity_with_known_domains_deleteGroup_404_returns_zero() {
        // Group exists (GET returns 200 with 2 episodes) but DELETE /group returns 404.
        // eraseGroup() catches DELETE 404 → returns 0 (not the episode count).
        wireMock.stubFor(get(urlPathEqualTo("/episodes/" + GROUP_ID))
            .willReturn(okJson("""
                [
                  {"uuid":"ep-1","content":"a","created_at":"2026-01-01T00:00:00Z","group_id":"%s"},
                  {"uuid":"ep-2","content":"b","created_at":"2026-01-02T00:00:00Z","group_id":"%s"}
                ]
                """.formatted(GROUP_ID, GROUP_ID))));
        wireMock.stubFor(delete(urlEqualTo("/group/" + GROUP_ID))
            .willReturn(aResponse().withStatus(404)));

        final int count = store.eraseEntity(ENTITY, TENANT);

        assertEquals(0, count);  // DELETE 404 → catch fires → returns 0, not 2
        wireMock.verify(deleteRequestedFor(urlEqualTo("/group/" + GROUP_ID)));
    }

    @Test
    void capabilities_includes_ERASE_ENTITY_when_known_domains_configured() {
        assertTrue(store.capabilities().contains(MemoryCapability.ERASE_ENTITY));
    }

    // ── eraseEntityAcrossTenants ──────────────────────────────────────────────

    @Test
    void eraseEntityAcrossTenants_requires_cross_tenant_admin() {
        // principal.isCrossTenantAdmin() is false (set in @BeforeEach)
        assertThrows(SecurityException.class,
            () -> store.eraseEntityAcrossTenants(ENTITY, java.util.Set.of(TENANT)));
    }

    @Test
    void eraseEntityAcrossTenants_with_known_domains_does_not_throw() {
        // Stub: GET /episodes returns empty list, DELETE /group returns 200
        wireMock.stubFor(WireMock.get(WireMock.urlPathMatching("/episodes/.*"))
            .willReturn(WireMock.okJson("[]")));
        wireMock.stubFor(WireMock.delete(WireMock.urlMatching("/group/.*"))
            .willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":true,\"message\":\"deleted\"}")));

        principal.setCrossTenantAdmin(true);
        // The method will call eraseGroup for each domain × tenant combination
        int count = store.eraseEntityAcrossTenants(ENTITY, java.util.Set.of(TENANT));
        assertTrue(count >= 0);
        assertTrue(store.capabilities().contains(MemoryCapability.CROSS_TENANT_ERASE));
    }
}
