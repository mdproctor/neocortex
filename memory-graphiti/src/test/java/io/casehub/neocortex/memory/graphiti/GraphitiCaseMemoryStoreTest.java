package io.casehub.neocortex.memory.graphiti;

import io.casehub.neocortex.memory.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@ActivateRequestContext
class GraphitiCaseMemoryStoreTest {

    static WireMockServer wireMock;

    static final String TENANT      = "tenant-1";
    static final String ENTITY      = "actor-1";
    static final MemoryDomain DOMAIN = new MemoryDomain("investigation");
    static final String GROUP_ID    = TENANT + "::" + ENTITY + "::" + DOMAIN.name();

    @Inject GraphCaseMemoryStore store;
    @Inject FixedCurrentPrincipal principal;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().port(39200));
        wireMock.start();
        WireMock.configureFor("localhost", 39200);
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

    private MemoryInput input(final String text) {
        return new MemoryInput(ENTITY, DOMAIN, TENANT, null, text, Map.of());
    }

    // ── store ─────────────────────────────────────────────────────────────────

    @Test
    void store_posts_to_messages_and_returns_uuid() {
        wireMock.stubFor(post(urlEqualTo("/messages"))
            .willReturn(aResponse().withStatus(202)));

        final String id = store.store(input("Alice reviewed the fraud report"));

        assertFalse(id.isEmpty(), "memoryId must be non-empty");
        wireMock.verify(1, postRequestedFor(urlEqualTo("/messages")));
    }

    @Test
    void store_sends_correct_group_id_and_role_type() {
        wireMock.stubFor(post(urlEqualTo("/messages"))
            .willReturn(aResponse().withStatus(202)));

        store.store(input("some text"));

        wireMock.verify(postRequestedFor(urlEqualTo("/messages"))
            .withRequestBody(matchingJsonPath("$.group_id", equalTo(GROUP_ID)))
            .withRequestBody(matchingJsonPath("$.messages[0].role_type", equalTo("user"))));
    }

    @Test
    void store_sends_source_description_with_domain() {
        wireMock.stubFor(post(urlEqualTo("/messages"))
            .willReturn(aResponse().withStatus(202)));

        store.store(input("some text"));

        wireMock.verify(postRequestedFor(urlEqualTo("/messages"))
            .withRequestBody(matchingJsonPath("$.messages[0].source_description",
                equalTo("domain=investigation"))));
    }

    @Test
    void store_sends_source_description_with_domain_and_caseId_when_caseId_present() {
        wireMock.stubFor(post(urlEqualTo("/messages"))
            .willReturn(aResponse().withStatus(202)));

        final var withCase = new MemoryInput(ENTITY, DOMAIN, TENANT, "case-99", "text", Map.of());
        store.store(withCase);

        wireMock.verify(postRequestedFor(urlEqualTo("/messages"))
            .withRequestBody(matchingJsonPath("$.messages[0].source_description",
                equalTo("domain=investigation;caseId=case-99"))));
    }

    @Test
    void store_timestamp_is_serialized_as_ISO8601_string_not_epoch_millis() {
        wireMock.stubFor(post(urlEqualTo("/messages"))
            .willReturn(aResponse().withStatus(202)));

        store.store(input("some text"));

        // Timestamp must be an ISO-8601 string like "2026-06-08T..." not a numeric epoch value.
        // @JsonFormat(shape=STRING) on AddMessage.timestamp ensures this.
        wireMock.verify(postRequestedFor(urlEqualTo("/messages"))
            .withRequestBody(matchingJsonPath("$.messages[0].timestamp",
                matching("\\d{4}-\\d{2}-\\d{2}T.*"))));
    }

    @Test
    void store_sends_message_uuid_matching_returned_id() {
        wireMock.stubFor(post(urlEqualTo("/messages"))
            .willReturn(aResponse().withStatus(202)));

        final String id = store.store(input("text"));

        wireMock.verify(postRequestedFor(urlEqualTo("/messages"))
            .withRequestBody(matchingJsonPath("$.messages[0].uuid", equalTo(id))));
    }

    @Test
    void store_tenant_mismatch_throws_before_http_call() {
        final var bad = new MemoryInput(ENTITY, DOMAIN, "wrong-tenant", null, "x", Map.of());
        assertThrows(SecurityException.class, () -> store.store(bad));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/messages")));
    }

    @Test
    void store_http_error_wraps_in_GraphitiStoreException() {
        wireMock.stubFor(post(urlEqualTo("/messages"))
            .willReturn(aResponse().withStatus(500).withBody("server error")));

        assertThrows(GraphitiStoreException.class, () -> store.store(input("text")));
    }

    // ── storeAll ──────────────────────────────────────────────────────────────

    @Test
    void storeAll_sends_sequential_posts_returns_ids_in_order() {
        wireMock.stubFor(post(urlEqualTo("/messages"))
            .willReturn(aResponse().withStatus(202)));

        final var a = input("fact a");
        final var b = new MemoryInput("actor-2", DOMAIN, TENANT, null, "fact b", Map.of());
        final var result = store.storeAll(List.of(a, b));

        assertTrue(result.allSucceeded());
        assertEquals(2, result.stored().size());
        assertFalse(result.stored().get(0).isEmpty());
        assertFalse(result.stored().get(1).isEmpty());
        assertNotEquals(result.stored().get(0), result.stored().get(1));
        wireMock.verify(2, postRequestedFor(urlEqualTo("/messages")));
    }

    @Test
    void storeAll_preflight_all_bad_throws_before_any_http() {
        final var bad = new MemoryInput(ENTITY, DOMAIN, "wrong-tenant", null, "x", Map.of());
        assertThrows(SecurityException.class, () -> store.storeAll(List.of(bad)));
        wireMock.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void storeAll_preflight_good_then_bad_throws_before_any_http() {
        final var good = input("ok");
        final var bad  = new MemoryInput(ENTITY, DOMAIN, "wrong-tenant", null, "x", Map.of());
        assertThrows(SecurityException.class, () -> store.storeAll(List.of(good, bad)));
        // pre-flight checks ALL before starting any POST
        wireMock.verify(0, postRequestedFor(anyUrl()));
    }

    // ── query (CHRONOLOGICAL) ─────────────────────────────────────────────────

    @Test
    void query_chronological_calls_episodes_endpoint() {
        wireMock.stubFor(get(urlPathEqualTo("/episodes/" + GROUP_ID))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    [{"uuid":"ep-1","group_id":"%s","content":"(user): hello",
                      "created_at":"2026-06-01T10:00:00Z"}]
                    """.formatted(GROUP_ID))));

        final var q = MemoryQuery.forEntity(ENTITY, DOMAIN, TENANT)
            .withOrder(MemoryOrder.CHRONOLOGICAL);
        final List<Memory> results = store.query(q);

        assertEquals(1, results.size());
        assertEquals("ep-1", results.get(0).memoryId());
        assertEquals(ENTITY, results.get(0).entityId());
        assertEquals(TENANT, results.get(0).tenantId());
        assertEquals(DOMAIN, results.get(0).domain());
        assertEquals("(user): hello", results.get(0).text());
    }

    @Test
    void query_chronological_last_n_equals_limit_times_entity_count() {
        wireMock.stubFor(get(urlPathMatching("/episodes/.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[]")));

        store.query(MemoryQuery.forEntities(List.of(ENTITY, "actor-2"), DOMAIN, TENANT)
            .withLimit(5));

        // last_n = 5 * 2 = 10
        wireMock.verify(getRequestedFor(urlPathEqualTo("/episodes/" + GROUP_ID))
            .withQueryParam("last_n", equalTo("10")));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/episodes/tenant-1::actor-2::investigation"))
            .withQueryParam("last_n", equalTo("10")));
    }

    @Test
    void query_chronological_since_filter_applied_client_side() {
        final String old = "2026-01-01T00:00:00Z";
        final String recent = "2026-06-01T10:00:00Z";
        wireMock.stubFor(get(urlPathEqualTo("/episodes/" + GROUP_ID))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    [{"uuid":"old","group_id":"%s","content":"old fact",
                      "created_at":"%s"},
                     {"uuid":"new","group_id":"%s","content":"new fact",
                      "created_at":"%s"}]
                    """.formatted(GROUP_ID, old, GROUP_ID, recent))));

        final var barrier = Instant.parse("2026-03-01T00:00:00Z");
        final var results = store.query(MemoryQuery.forEntity(ENTITY, DOMAIN, TENANT)
            .withSince(barrier));

        assertEquals(1, results.size());
        assertEquals("new", results.get(0).memoryId());
    }

    @Test
    void query_chronological_populates_VALID_FROM_from_episode_valid_at() {
        wireMock.stubFor(get(urlPathEqualTo("/episodes/" + GROUP_ID))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    [{"uuid":"ep-1","group_id":"%s","content":"(user): text",
                      "valid_at":"2026-05-01T00:00:00Z",
                      "created_at":"2026-06-01T10:00:00Z"}]
                    """.formatted(GROUP_ID))));

        final var results = store.query(MemoryQuery.forEntity(ENTITY, DOMAIN, TENANT)
            .withOrder(MemoryOrder.CHRONOLOGICAL));

        assertEquals(1, results.size());
        assertEquals("2026-05-01T00:00:00Z",
            results.get(0).attributes().get(MemoryAttributeKeys.VALID_FROM),
            "VALID_FROM must be populated from EpisodicNode.valid_at");
    }

    @Test
    void query_tenant_mismatch_throws() {
        assertThrows(SecurityException.class, () ->
            store.query(MemoryQuery.forEntity(ENTITY, DOMAIN, "wrong-tenant")));
    }

    // ── query (RELEVANCE) ─────────────────────────────────────────────────────

    @Test
    void query_relevance_calls_search_endpoint_per_entity() {
        wireMock.stubFor(post(urlEqualTo("/search"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"facts":[{"uuid":"f-1","fact":"Alice approved the report",
                      "valid_at":"2026-05-01T00:00:00Z","created_at":"2026-06-01T10:00:00Z"}]}
                    """)));

        final var q = MemoryQuery.forEntity(ENTITY, DOMAIN, TENANT)
            .withOrder(MemoryOrder.RELEVANCE)
            .withQuestion("who approved?");
        final List<Memory> results = store.query(q);

        assertEquals(1, results.size());
        assertEquals("f-1", results.get(0).memoryId());
        assertEquals(ENTITY, results.get(0).entityId());
        assertEquals(DOMAIN, results.get(0).domain());
        assertEquals("Alice approved the report", results.get(0).text());
        // VALID_FROM attribute populated
        assertNotNull(results.get(0).attributes().get(MemoryAttributeKeys.VALID_FROM));
    }

    @Test
    void query_relevance_sends_correct_group_id_in_search_request() {
        wireMock.stubFor(post(urlEqualTo("/search"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"facts\":[]}")));

        store.query(MemoryQuery.forEntity(ENTITY, DOMAIN, TENANT)
            .withOrder(MemoryOrder.RELEVANCE)
            .withQuestion("test"));

        wireMock.verify(postRequestedFor(urlEqualTo("/search"))
            .withRequestBody(matchingJsonPath("$.group_ids[0]", equalTo(GROUP_ID))));
    }

    // ── graphQuery ────────────────────────────────────────────────────────────

    @Test
    void graphQuery_calls_search_per_entity_and_maps_domain_from_query() {
        wireMock.stubFor(post(urlEqualTo("/search"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"facts":[{"uuid":"g-1","fact":"fact text",
                      "created_at":"2026-06-01T10:00:00Z"}]}
                    """)));

        final var q = GraphMemoryQuery.forEntity(ENTITY, DOMAIN, TENANT, "question?");
        final List<Memory> results = store.graphQuery(q);

        assertEquals(1, results.size());
        assertEquals(DOMAIN, results.get(0).domain());
        assertEquals(ENTITY, results.get(0).entityId());
    }

    @Test
    void graphQuery_validAt_filter_removes_out_of_window_facts() {
        wireMock.stubFor(post(urlEqualTo("/search"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"facts":[
                      {"uuid":"current","fact":"current fact",
                       "valid_at":"2026-01-01T00:00:00Z",
                       "created_at":"2026-06-01T10:00:00Z"},
                      {"uuid":"expired","fact":"expired fact",
                       "valid_at":"2026-01-01T00:00:00Z",
                       "invalid_at":"2026-03-01T00:00:00Z",
                       "created_at":"2026-06-01T10:00:00Z"}
                    ]}
                    """)));

        final var validAt = Instant.parse("2026-06-01T00:00:00Z");
        final var q = GraphMemoryQuery.forEntity(ENTITY, DOMAIN, TENANT, "q?")
            .withValidAt(validAt);
        final List<Memory> results = store.graphQuery(q);

        assertEquals(1, results.size());
        assertEquals("current", results.get(0).memoryId());
    }

    @Test
    void graphQuery_since_filter_excludes_facts_before_barrier() {
        wireMock.stubFor(post(urlEqualTo("/search"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"facts":[
                      {"uuid":"old","fact":"old fact","created_at":"2026-01-01T00:00:00Z"},
                      {"uuid":"new","fact":"new fact","created_at":"2026-06-01T00:00:00Z"}
                    ]}
                    """)));

        final var barrier = Instant.parse("2026-03-01T00:00:00Z");
        final var q = GraphMemoryQuery.forEntity(ENTITY, DOMAIN, TENANT, "question?")
            .withSince(barrier);
        final List<Memory> results = store.graphQuery(q);

        assertEquals(1, results.size());
        assertEquals("new", results.get(0).memoryId());
    }

    @Test
    void graphQuery_multi_entity_issues_one_search_per_entity_entity_order_concat() {
        // entity-1 returns f1; entity-2 returns f2
        wireMock.stubFor(post(urlEqualTo("/search"))
            .withRequestBody(matchingJsonPath("$.group_ids[0]", equalTo(GROUP_ID)))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"facts\":[{\"uuid\":\"f1\",\"fact\":\"e1 fact\",\"created_at\":\"2026-06-01T10:00:00Z\"}]}")));
        wireMock.stubFor(post(urlEqualTo("/search"))
            .withRequestBody(matchingJsonPath("$.group_ids[0]", equalTo("tenant-1::actor-2::investigation")))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"facts\":[{\"uuid\":\"f2\",\"fact\":\"e2 fact\",\"created_at\":\"2026-06-01T09:00:00Z\"}]}")));

        final var q = new GraphMemoryQuery(TENANT, List.of(ENTITY, "actor-2"), DOMAIN, "question?",
                10, null, null, null, MemoryResultType.DEFAULT);
        final List<Memory> results = store.graphQuery(q);

        assertEquals(2, results.size());
        // entity-order concatenation: entity-1's result first, entity-2's result second
        assertEquals("f1", results.get(0).memoryId());
        assertEquals(ENTITY, results.get(0).entityId());
        assertEquals("f2", results.get(1).memoryId());
        assertEquals("actor-2", results.get(1).entityId());
        // two search calls made
        wireMock.verify(2, postRequestedFor(urlEqualTo("/search")));
    }

    @Test
    void graphQuery_entityTypes_requires_ENTITY_TYPE_FILTER_capability() {
        final var q = GraphMemoryQuery.forEntity(ENTITY, DOMAIN, TENANT, "q?")
            .withEntityTypes(java.util.Set.of("Person"));
        assertThrows(MemoryCapabilityException.class, () -> store.graphQuery(q));
        wireMock.verify(0, postRequestedFor(anyUrl()));
    }

    // ── eraseEntity ───────────────────────────────────────────────────────────

    @Test
    void eraseEntity_tenant_mismatch_throws_SecurityException() {
        assertThrows(SecurityException.class, () -> store.eraseEntity(ENTITY, "wrong-tenant"));
        wireMock.verify(0, deleteRequestedFor(anyUrl()));
        wireMock.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void eraseEntity_without_known_domains_throws_MemoryCapabilityException() {
        // No casehub.memory.graphiti.known-domains configured → capability absent → exception.
        final var ex = assertThrows(MemoryCapabilityException.class,
            () -> store.eraseEntity(ENTITY, TENANT));
        assertEquals(MemoryCapability.ERASE_ENTITY, ex.required());
        wireMock.verify(0, getRequestedFor(anyUrl()));
        wireMock.verify(0, deleteRequestedFor(anyUrl()));
    }

    @Test
    void eraseEntityAcrossTenants_without_known_domains_throws_MemoryCapabilityException() {
        principal.setCrossTenantAdmin(true);
        assertThrows(MemoryCapabilityException.class,
            () -> store.eraseEntityAcrossTenants(ENTITY, java.util.Set.of(TENANT)));
    }

    // ── eraseById ─────────────────────────────────────────────────────────────

    @Test
    void eraseById_throws_MemoryCapabilityException_no_http_call() {
        // Graphiti DELETE /episode/{uuid} only removes the EpisodicNode; derived entity/
        // relationship facts persist. ERASE_BY_ID cannot guarantee GDPR Art.17 completeness.
        final var ex = assertThrows(MemoryCapabilityException.class,
            () -> store.eraseById("ep-uuid-123", ENTITY, TENANT));
        assertEquals(MemoryCapability.ERASE_BY_ID, ex.required());
        wireMock.verify(0, deleteRequestedFor(anyUrl()));
    }

    @Test
    void eraseById_tenant_mismatch_throws_SecurityException_before_capability_check() {
        assertThrows(SecurityException.class, () ->
            store.eraseById("any-id", ENTITY, "wrong-tenant"));
        wireMock.verify(0, deleteRequestedFor(anyUrl()));
    }

    // ── erase(EraseRequest) ───────────────────────────────────────────────────

    @Test
    void erase_domain_only_deletes_group_and_returns_count() {
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

        final int count = store.erase(new EraseRequest(ENTITY, DOMAIN, TENANT, null));
        assertEquals(3, count);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/episodes/" + GROUP_ID)));
        wireMock.verify(deleteRequestedFor(urlEqualTo("/group/" + GROUP_ID)));
    }

    @Test
    void erase_domain_only_tenant_mismatch_throws_before_http() {
        assertThrows(SecurityException.class,
            () -> store.erase(new EraseRequest(ENTITY, DOMAIN, "wrong-tenant", null)));
        wireMock.verify(0, getRequestedFor(anyUrl()));
        wireMock.verify(0, deleteRequestedFor(anyUrl()));
    }

    @Test
    void erase_with_caseId_deletes_only_matching_episodes() {
        wireMock.stubFor(get(urlPathEqualTo("/episodes/" + GROUP_ID))
            .willReturn(okJson("""
                [
                  {"uuid":"ep-1","content":"a","created_at":"2026-01-01T00:00:00Z",
                   "group_id":"%s","source_description":"domain=investigation;caseId=case-99"},
                  {"uuid":"ep-2","content":"b","created_at":"2026-01-02T00:00:00Z",
                   "group_id":"%s","source_description":"domain=investigation;caseId=case-99"},
                  {"uuid":"ep-3","content":"c","created_at":"2026-01-03T00:00:00Z",
                   "group_id":"%s","source_description":"domain=investigation;caseId=other-case"}
                ]
                """.formatted(GROUP_ID, GROUP_ID, GROUP_ID))));
        wireMock.stubFor(delete(urlMatching("/episode/ep-\\d+"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":true}")));

        final int count = store.erase(new EraseRequest(ENTITY, DOMAIN, TENANT, "case-99"));
        assertEquals(2, count);
        wireMock.verify(deleteRequestedFor(urlEqualTo("/episode/ep-1")));
        wireMock.verify(deleteRequestedFor(urlEqualTo("/episode/ep-2")));
        wireMock.verify(0, deleteRequestedFor(urlEqualTo("/episode/ep-3")));
    }

    @Test
    void erase_with_caseId_no_matches_returns_zero() {
        wireMock.stubFor(get(urlPathEqualTo("/episodes/" + GROUP_ID))
            .willReturn(okJson("""
                [
                  {"uuid":"ep-1","content":"a","created_at":"2026-01-01T00:00:00Z",
                   "group_id":"%s","source_description":"domain=investigation;caseId=other-case"}
                ]
                """.formatted(GROUP_ID))));

        final int count = store.erase(new EraseRequest(ENTITY, DOMAIN, TENANT, "case-99"));
        assertEquals(0, count);
        wireMock.verify(0, deleteRequestedFor(anyUrl()));
    }

    // ── capabilities ──────────────────────────────────────────────────────────

    @Test
    void capabilities_includes_expected_set() {
        final var caps = store.capabilities();
        assertTrue(caps.contains(MemoryCapability.CHRONOLOGICAL_ORDER));
        assertTrue(caps.contains(MemoryCapability.SEMANTIC_SEARCH));
        assertTrue(caps.contains(MemoryCapability.TEMPORAL_GRAPH));
        assertTrue(caps.contains(MemoryCapability.FACT_SEARCH));
        assertTrue(caps.contains(MemoryCapability.DOMAIN_SCOPED));        // added
        assertTrue(caps.contains(MemoryCapability.ERASE_DOMAIN_CASE));    // added
        assertFalse(caps.contains(MemoryCapability.ERASE_ENTITY));        // removed (no known-domains configured)
        assertFalse(caps.contains(MemoryCapability.CASE_SCOPED));
        assertFalse(caps.contains(MemoryCapability.ENTITY_TYPE_FILTER));
        assertFalse(caps.contains(MemoryCapability.ERASE_BY_ID));
    }
}
