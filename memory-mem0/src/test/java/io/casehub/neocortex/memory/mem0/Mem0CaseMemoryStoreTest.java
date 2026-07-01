package io.casehub.neocortex.memory.mem0;

import io.casehub.neocortex.memory.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@ActivateRequestContext
@QuarkusTestResource(Mem0WireMockResource.class)
class Mem0CaseMemoryStoreTest {

    static final String TENANT       = "tenant-1";
    static final String OTHER_TENANT = "tenant-2";
    static final MemoryDomain DOMAIN = new MemoryDomain("d");

    @Inject Mem0CaseMemoryStore store;
    @Inject FixedCurrentPrincipal principal;

    @BeforeEach
    void setup() {
        wireMock().resetAll();
        principal.setTenancyId(TENANT);
        principal.setCrossTenantAdmin(false);
    }

    private WireMockServer wireMock() {
        return Mem0WireMockResource.INSTANCE;
    }

    // ── stub helpers ──────────────────────────────────────────────────────────

    private void stubAddOk(String memoryId) {
        wireMock().stubFor(post(urlEqualTo("/memories"))
            .willReturn(okJson("""
                {"results": [{"id": "%s", "memory": "text", "event": "ADD"}]}
                """.formatted(memoryId))));
    }

    private void stubListOk(String... memoriesJson) {
        final String arr = String.join(",", memoriesJson);
        wireMock().stubFor(get(urlPathEqualTo("/memories"))
            .willReturn(okJson("{\"results\": [" + arr + "]}")));
    }

    private void stubSearchOk(String... memoriesJson) {
        final String arr = String.join(",", memoriesJson);
        wireMock().stubFor(post(urlEqualTo("/search"))
            .willReturn(okJson("{\"results\": [" + arr + "]}")));
    }

    private void stubDeleteByIdOk(String memoryId) {
        wireMock().stubFor(delete(urlEqualTo("/memories/" + memoryId))
            .willReturn(okJson("{\"message\": \"Memory deleted\"}")));
    }

    private void stubDeleteAllOk() {
        wireMock().stubFor(delete(urlPathEqualTo("/memories"))
            .willReturn(okJson("{\"message\": \"All relevant memories deleted\"}")));
    }

    /** Minimal Mem0Memory JSON for inline use in list/search responses (entity-1 default). */
    static String mem0Json(String id, String text, String createdAt) {
        return mem0JsonForEntity(id, text, createdAt, "entity-1");
    }

    /** Minimal Mem0Memory JSON with an explicit entityId encoded in the compound user_id. */
    static String mem0JsonForEntity(String id, String text, String createdAt, String entityId) {
        return """
            {"id":"%s","memory":"%s","metadata":{},"score":null,
             "created_at":"%s","updated_at":null,
             "user_id":"tenant-1::%s","agent_id":"d","run_id":null}
            """.formatted(id, text, createdAt, entityId);
    }

    static String mem0JsonWithScore(String id, String text, float score) {
        return """
            {"id":"%s","memory":"%s","metadata":{},"score":%s,
             "created_at":"2026-06-04T10:00:00Z","updated_at":null,
             "user_id":"tenant-1::entity-1","agent_id":"d","run_id":null}
            """.formatted(id, text, score);
    }

    // ── store ─────────────────────────────────────────────────────────────────

    @Test
    void store_sends_infer_false_in_request_body() {
        stubAddOk("mem-001");
        store.store(new MemoryInput("entity-1", DOMAIN, TENANT, null, "hello", Map.of()));
        wireMock().verify(postRequestedFor(urlEqualTo("/memories"))
            .withRequestBody(matchingJsonPath("$[?(@.infer == false)]")));
    }

    @Test
    void store_compound_user_id_encodes_tenant_and_entity() {
        stubAddOk("mem-001");
        store.store(new MemoryInput("entity-1", DOMAIN, TENANT, null, "hello", Map.of()));
        wireMock().verify(postRequestedFor(urlEqualTo("/memories"))
            .withRequestBody(matchingJsonPath("$.user_id", equalTo("tenant-1::entity-1"))));
    }

    @Test
    void store_field_mapping_all_fields() {
        stubAddOk("mem-002");
        store.store(new MemoryInput("entity-2", DOMAIN, TENANT, "case-99", "fact", Map.of("k", "v")));
        wireMock().verify(postRequestedFor(urlEqualTo("/memories"))
            .withRequestBody(matchingJsonPath("$.agent_id",            equalTo("d")))
            .withRequestBody(matchingJsonPath("$.run_id",              equalTo("case-99")))
            .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("fact")))
            .withRequestBody(matchingJsonPath("$.messages[0].role",    equalTo("user")))
            .withRequestBody(matchingJsonPath("$.metadata.k",          equalTo("v"))));
    }

    @Test
    void store_absent_caseId_omits_run_id() {
        stubAddOk("mem-003");
        store.store(new MemoryInput("entity-1", DOMAIN, TENANT, null, "hello", Map.of()));
        wireMock().verify(postRequestedFor(urlEqualTo("/memories"))
            .withRequestBody(matchingJsonPath("$[?(!(@.run_id))]")));
    }

    @Test
    void store_returns_mem0_memory_id() {
        stubAddOk("returned-id-xyz");
        final String id = store.store(new MemoryInput("entity-1", DOMAIN, TENANT, null, "hi", Map.of()));
        assertEquals("returned-id-xyz", id);
    }

    @Test
    void store_bearer_token_on_every_request() {
        stubAddOk("mem-004");
        store.store(new MemoryInput("entity-1", DOMAIN, TENANT, null, "hello", Map.of()));
        wireMock().verify(postRequestedFor(urlEqualTo("/memories"))
            .withHeader("Authorization", equalTo("Bearer test-key")));
    }

    @Test
    void store_tenant_mismatch_throws_before_http() {
        assertThrows(SecurityException.class, () ->
            store.store(new MemoryInput("entity-1", DOMAIN, OTHER_TENANT, null, "hello", Map.of())));
        wireMock().verify(0, postRequestedFor(urlEqualTo("/memories")));
    }

    @Test
    void store_non_2xx_throws_Mem0StoreException() {
        wireMock().stubFor(post(urlEqualTo("/memories")).willReturn(serverError()));
        assertThrows(Mem0StoreException.class, () ->
            store.store(new MemoryInput("entity-1", DOMAIN, TENANT, null, "hello", Map.of())));
    }

    // ── query — chronological / mapping ──────────────────────────────────────

    @Test
    void query_non_2xx_throws_Mem0StoreException() {
        wireMock().stubFor(get(urlPathEqualTo("/memories")).willReturn(serverError()));
        assertThrows(Mem0StoreException.class, () ->
            store.query(MemoryQuery.forEntity("entity-1", DOMAIN, TENANT)));
    }

    @Test
    void query_chronological_sends_get_without_limit_param() {
        stubListOk();
        store.query(MemoryQuery.forEntity("entity-1", DOMAIN, TENANT));
        wireMock().verify(getRequestedFor(urlPathEqualTo("/memories"))
            .withQueryParam("user_id",  equalTo("tenant-1::entity-1"))
            .withQueryParam("agent_id", equalTo("d"))
            .withoutQueryParam("limit"));
    }

    @Test
    void query_maps_mem0_response_to_memory_records() {
        stubListOk(mem0Json("mid-1", "the fact", "2026-06-04T10:00:00Z"));
        final var results = store.query(MemoryQuery.forEntity("entity-1", DOMAIN, TENANT));
        assertEquals(1, results.size());
        final var m = results.get(0);
        assertEquals("mid-1",                               m.memoryId());
        assertEquals("entity-1",                            m.entityId());
        assertEquals(DOMAIN,                                m.domain());
        assertEquals(TENANT,                                m.tenantId());
        assertEquals("the fact",                            m.text());
        assertEquals(Instant.parse("2026-06-04T10:00:00Z"), m.createdAt());
    }

    @Test
    void query_tenant_mismatch_throws_before_http() {
        assertThrows(SecurityException.class, () ->
            store.query(MemoryQuery.forEntity("entity-1", DOMAIN, OTHER_TENANT)));
        wireMock().verify(0, getRequestedFor(urlPathEqualTo("/memories")));
    }

    @Test
    void query_limit_applied_to_merged_result_set() {
        stubListOk(
            mem0Json("m1", "a", "2026-06-04T12:00:00Z"),
            mem0Json("m2", "b", "2026-06-04T11:00:00Z"),
            mem0Json("m3", "c", "2026-06-04T10:00:00Z")
        );
        final var results = store.query(MemoryQuery.forEntity("entity-1", DOMAIN, TENANT).withLimit(2));
        assertEquals(2, results.size());
    }

    @Test
    void query_chronological_newest_first() {
        stubListOk(
            mem0Json("m1", "older", "2026-06-04T09:00:00Z"),
            mem0Json("m2", "newer", "2026-06-04T11:00:00Z")
        );
        final var results = store.query(MemoryQuery.forEntity("entity-1", DOMAIN, TENANT));
        assertEquals("newer", results.get(0).text());
        assertEquals("older", results.get(1).text());
    }

    // ── query — RELEVANCE ─────────────────────────────────────────────────────

    @Test
    void query_relevance_with_question_sends_search_with_top_k() {
        stubSearchOk();
        store.query(MemoryQuery.forEntity("entity-1", DOMAIN, TENANT)
            .withQuestion("what happened?").withOrder(MemoryOrder.RELEVANCE).withLimit(5));
        wireMock().verify(postRequestedFor(urlEqualTo("/search"))
            .withRequestBody(matchingJsonPath("$.top_k",   equalTo("5")))
            .withRequestBody(matchingJsonPath("$.query",   equalTo("what happened?")))
            .withRequestBody(matchingJsonPath("$.user_id", equalTo("tenant-1::entity-1")))
            .withRequestBody(matchingJsonPath("$.threshold", equalTo("0.1"))));
        wireMock().verify(0, getRequestedFor(urlPathEqualTo("/memories")));
    }

    @Test
    void query_relevance_with_question_and_since_uses_since_search_top_k() {
        stubSearchOk();
        store.query(MemoryQuery.forEntity("entity-1", DOMAIN, TENANT)
            .withQuestion("q").withOrder(MemoryOrder.RELEVANCE)
            .withSince(Instant.parse("2026-01-01T00:00:00Z")).withLimit(5));
        wireMock().verify(postRequestedFor(urlEqualTo("/search"))
            .withRequestBody(matchingJsonPath("$.top_k", equalTo("500"))));
    }

    @Test
    void query_relevance_without_question_falls_back_to_get() {
        stubListOk();
        store.query(MemoryQuery.forEntity("entity-1", DOMAIN, TENANT)
            .withOrder(MemoryOrder.RELEVANCE));
        wireMock().verify(1, getRequestedFor(urlPathEqualTo("/memories")));
        wireMock().verify(0, postRequestedFor(urlEqualTo("/search")));
    }

    @Test
    void query_single_entity_relevance_ordered_by_score() {
        stubSearchOk(
            mem0JsonWithScore("high", "best",  0.9f),
            mem0JsonWithScore("low",  "worst", 0.3f)
        );
        final var results = store.query(MemoryQuery.forEntity("entity-1", DOMAIN, TENANT)
            .withQuestion("q").withOrder(MemoryOrder.RELEVANCE));
        assertEquals("best",  results.get(0).text());
        assertEquals("worst", results.get(1).text());
    }

    // ── query — multi-entity + since ──────────────────────────────────────────

    @Test
    void query_multi_entity_fans_out_one_request_per_entity() {
        wireMock().stubFor(get(urlPathEqualTo("/memories"))
            .withQueryParam("user_id", equalTo("tenant-1::entity-1"))
            .willReturn(okJson("{\"results\": [" + mem0JsonForEntity("m1", "e1-fact", "2026-06-04T10:00:00Z", "entity-1") + "]}")));
        wireMock().stubFor(get(urlPathEqualTo("/memories"))
            .withQueryParam("user_id", equalTo("tenant-1::entity-2"))
            .willReturn(okJson("{\"results\": [" + mem0JsonForEntity("m2", "e2-fact", "2026-06-04T11:00:00Z", "entity-2") + "]}")));

        final var results = store.query(MemoryQuery.forEntities(
            List.of("entity-1", "entity-2"), DOMAIN, TENANT));

        assertEquals(2, results.size());
        // Verify HTTP fan-out
        wireMock().verify(1, getRequestedFor(urlPathEqualTo("/memories"))
            .withQueryParam("user_id", equalTo("tenant-1::entity-1")));
        wireMock().verify(1, getRequestedFor(urlPathEqualTo("/memories"))
            .withQueryParam("user_id", equalTo("tenant-1::entity-2")));
        // Verify entityId round-trip from compound user_id
        assertEquals("entity-1", results.stream().filter(m -> m.memoryId().equals("m1")).findFirst().orElseThrow().entityId());
        assertEquals("entity-2", results.stream().filter(m -> m.memoryId().equals("m2")).findFirst().orElseThrow().entityId());
    }

    @Test
    void query_multi_entity_relevance_concatenates_in_entity_order() {
        wireMock().stubFor(post(urlEqualTo("/search"))
            .withRequestBody(matchingJsonPath("$.user_id", equalTo("tenant-1::entity-1")))
            .willReturn(okJson("{\"results\": [" + mem0JsonWithScore("m1", "e1", 0.4f) + "]}")));
        wireMock().stubFor(post(urlEqualTo("/search"))
            .withRequestBody(matchingJsonPath("$.user_id", equalTo("tenant-1::entity-2")))
            .willReturn(okJson("{\"results\": [" + mem0JsonWithScore("m2", "e2", 0.9f) + "]}")));

        final var results = store.query(MemoryQuery.forEntities(
            List.of("entity-1", "entity-2"), DOMAIN, TENANT)
            .withQuestion("q").withOrder(MemoryOrder.RELEVANCE));

        assertEquals("e1", results.get(0).text()); // entity-1 first (fan-out order)
        assertEquals("e2", results.get(1).text());
    }

    @Test
    void query_since_filters_using_mem0_native_created_at() {
        stubListOk(
            mem0Json("new", "kept",     "2026-06-04T12:00:00Z"),
            mem0Json("old", "excluded", "2026-06-03T12:00:00Z")
        );
        final var results = store.query(
            MemoryQuery.forEntity("entity-1", DOMAIN, TENANT)
                .withSince(Instant.parse("2026-06-04T00:00:00Z")));
        assertEquals(1, results.size());
        assertEquals("kept", results.get(0).text());
    }

    @Test
    void query_null_created_at_entry_kept_defensive() {
        wireMock().stubFor(get(urlPathEqualTo("/memories")).willReturn(okJson("""
            {"results": [{"id":"m1","memory":"kept","metadata":{},"score":null,
              "created_at":null,"updated_at":null,
              "user_id":"tenant-1::entity-1","agent_id":"d","run_id":null}]}
            """)));
        final var results = store.query(
            MemoryQuery.forEntity("entity-1", DOMAIN, TENANT)
                .withSince(Instant.parse("2026-06-04T00:00:00Z")));
        assertEquals(1, results.size());
    }

    @Test
    void query_unparseable_created_at_entry_kept_defensive() {
        wireMock().stubFor(get(urlPathEqualTo("/memories")).willReturn(okJson("""
            {"results": [{"id":"m1","memory":"kept","metadata":{},"score":null,
              "created_at":"NOT-A-DATE","updated_at":null,
              "user_id":"tenant-1::entity-1","agent_id":"d","run_id":null}]}
            """)));
        final var results = store.query(
            MemoryQuery.forEntity("entity-1", DOMAIN, TENANT)
                .withSince(Instant.parse("2026-06-04T00:00:00Z")));
        assertEquals(1, results.size());
    }

    // ── erase ─────────────────────────────────────────────────────────────────

    @Test
    void erase_non_2xx_throws_Mem0StoreException() {
        // Pre-list succeeds; DELETE fails — exception must come from the delete path
        stubListOk();
        wireMock().stubFor(delete(urlPathEqualTo("/memories")).willReturn(serverError()));
        assertThrows(Mem0StoreException.class, () ->
            store.erase(new EraseRequest("entity-1", DOMAIN, TENANT, null)));
    }

    @Test
    void erase_sends_delete_with_query_params() {
        stubListOk();       // pre-list for count (returns empty → count = 0)
        stubDeleteAllOk();
        final int count = store.erase(new EraseRequest("entity-1", DOMAIN, TENANT, "case-1"));
        assertEquals(0, count);
        wireMock().verify(deleteRequestedFor(urlPathEqualTo("/memories"))
            .withQueryParam("user_id",  equalTo("tenant-1::entity-1"))
            .withQueryParam("agent_id", equalTo("d"))
            .withQueryParam("run_id",   equalTo("case-1")));
    }

    @Test
    void erase_null_caseId_omits_run_id() {
        stubListOk();       // pre-list for count (returns empty → count = 0)
        stubDeleteAllOk();
        final int count = store.erase(new EraseRequest("entity-1", DOMAIN, TENANT, null));
        assertEquals(0, count);
        wireMock().verify(deleteRequestedFor(urlPathEqualTo("/memories"))
            .withQueryParam("user_id",  equalTo("tenant-1::entity-1"))
            .withQueryParam("agent_id", equalTo("d"))
            .withoutQueryParam("run_id"));
    }

    @Test
    void erase_tenant_mismatch_throws_before_http() {
        assertThrows(SecurityException.class, () ->
            store.erase(new EraseRequest("entity-1", DOMAIN, OTHER_TENANT, null)));
        wireMock().verify(0, deleteRequestedFor(urlPathEqualTo("/memories")));
    }

    // ── eraseById ─────────────────────────────────────────────────────────────

    private void stubGetByIdOk(String memoryId, String userId) {
        wireMock().stubFor(get(urlEqualTo("/memories/" + memoryId))
            .willReturn(okJson("""
                {"id":"%s","memory":"text","user_id":"%s"}
                """.formatted(memoryId, userId))));
    }

    @Test
    void eraseById_preflight_GET_then_DELETE_when_userId_matches() {
        stubGetByIdOk("mem-xyz", "tenant-1::entity-1");
        stubDeleteByIdOk("mem-xyz");
        store.eraseById("mem-xyz", "entity-1", TENANT);
        wireMock().verify(getRequestedFor(urlEqualTo("/memories/mem-xyz")));
        wireMock().verify(deleteRequestedFor(urlEqualTo("/memories/mem-xyz")));
    }

    @Test
    void eraseById_returns_silently_when_userId_mismatches() {
        // memory belongs to entity-2, not entity-1 — silent no-op
        stubGetByIdOk("mem-xyz", "tenant-1::entity-2");
        store.eraseById("mem-xyz", "entity-1", TENANT);
        wireMock().verify(0, deleteRequestedFor(urlMatching("/memories/.*")));
    }

    @Test
    void eraseById_returns_silently_when_preflight_GET_returns_404() {
        wireMock().stubFor(get(urlEqualTo("/memories/gone"))
            .willReturn(aResponse().withStatus(404)));
        assertDoesNotThrow(() -> store.eraseById("gone", "entity-1", TENANT));
        wireMock().verify(0, deleteRequestedFor(urlMatching("/memories/.*")));
    }

    @Test
    void eraseById_non_2xx_on_preflight_GET_throws_Mem0StoreException() {
        wireMock().stubFor(get(urlEqualTo("/memories/error"))
            .willReturn(serverError()));
        assertThrows(Mem0StoreException.class, () -> store.eraseById("error", "entity-1", TENANT));
    }

    @Test
    void eraseById_non_2xx_on_DELETE_throws_Mem0StoreException() {
        stubGetByIdOk("forbidden", "tenant-1::entity-1");
        wireMock().stubFor(delete(urlEqualTo("/memories/forbidden"))
            .willReturn(aResponse().withStatus(403)));
        assertThrows(Mem0StoreException.class, () -> store.eraseById("forbidden", "entity-1", TENANT));
    }

    @Test
    void eraseById_tenant_mismatch_throws_before_http() {
        assertThrows(SecurityException.class, () ->
            store.eraseById("any-id", "entity-1", OTHER_TENANT));
        wireMock().verify(0, getRequestedFor(urlMatching("/memories/.*")));
        wireMock().verify(0, deleteRequestedFor(urlMatching("/memories/.*")));
    }

    // ── eraseEntity ───────────────────────────────────────────────────────────

    @Test
    void eraseEntity_non_2xx_on_list_throws_Mem0StoreException() {
        wireMock().stubFor(get(urlPathEqualTo("/memories")).willReturn(serverError()));
        assertThrows(Mem0StoreException.class, () ->
            store.eraseEntity("entity-1", TENANT));
    }

    @Test
    void eraseEntity_non_2xx_on_delete_throws_Mem0StoreException() {
        stubListOk(); // list succeeds; delete fails
        wireMock().stubFor(delete(urlPathEqualTo("/memories")).willReturn(serverError()));
        assertThrows(Mem0StoreException.class, () ->
            store.eraseEntity("entity-1", TENANT));
    }

    @Test
    void eraseEntity_sends_list_then_delete_with_compound_user_id_no_agent_id() {
        stubListOk();
        stubDeleteAllOk();
        store.eraseEntity("entity-1", TENANT);
        wireMock().verify(getRequestedFor(urlPathEqualTo("/memories"))
            .withQueryParam("user_id", equalTo("tenant-1::entity-1")));
        wireMock().verify(deleteRequestedFor(urlPathEqualTo("/memories"))
            .withQueryParam("user_id", equalTo("tenant-1::entity-1"))
            .withoutQueryParam("agent_id")
            .withoutQueryParam("run_id"));
    }

    @Test
    void eraseEntity_returns_count_from_list_response() {
        stubListOk(
            "{\"id\":\"m1\",\"memory\":\"a\",\"user_id\":\"tenant-1::entity-1\"}",
            "{\"id\":\"m2\",\"memory\":\"b\",\"user_id\":\"tenant-1::entity-1\"}"
        );
        stubDeleteAllOk();
        assertEquals(2, store.eraseEntity("entity-1", TENANT));
    }

    @Test
    void eraseEntity_tenant_mismatch_throws_before_http() {
        assertThrows(SecurityException.class, () ->
            store.eraseEntity("entity-1", OTHER_TENANT));
        wireMock().verify(0, deleteRequestedFor(urlPathEqualTo("/memories")));
        wireMock().verify(0, getRequestedFor(urlPathEqualTo("/memories")));
    }

    @Test
    void eraseEntity_sends_compound_key_for_correct_tenant_only() {
        stubListOk();
        stubDeleteAllOk();
        store.eraseEntity("entity-1", TENANT);
        wireMock().verify(deleteRequestedFor(urlPathEqualTo("/memories"))
            .withQueryParam("user_id", equalTo("tenant-1::entity-1")));
        wireMock().verify(0, deleteRequestedFor(urlPathEqualTo("/memories"))
            .withQueryParam("user_id", equalTo("tenant-2::entity-1")));
    }

    // ── eraseEntityAcrossTenants ───────────────────────────────────────────────

    @Test
    void eraseEntityAcrossTenants_requires_cross_tenant_admin() {
        // principal.isCrossTenantAdmin() is false (set in @BeforeEach)
        assertThrows(SecurityException.class,
            () -> store.eraseEntityAcrossTenants("entity-1", java.util.Set.of(TENANT)));
    }

    @Test
    void eraseEntityAcrossTenants_calls_list_and_deleteAll_per_tenant() {
        stubListOk(mem0Json("id-1", "data", "2026-01-01T00:00:00Z"));
        stubDeleteAllOk();
        principal.setCrossTenantAdmin(true);
        int count = store.eraseEntityAcrossTenants("entity-1", java.util.Set.of(TENANT, "tenant-b"));
        // list returns 1 item per tenant call, so total = 2 (one per tenant)
        assertEquals(2, count);
    }

    // ── storeAll ──────────────────────────────────────────────────────────────

    @Test
    void storeAll_returns_all_memory_ids_in_order() {
        // Stubs matched by message content so parallel calls each get a deterministic response.
        // Uni.join() guarantees output order matches input position regardless of completion order.
        wireMock().stubFor(post(urlEqualTo("/memories"))
            .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("a")))
            .willReturn(okJson("{\"results\": [{\"id\":\"mem-aaa\",\"memory\":\"a\",\"event\":\"ADD\"}]}")));
        wireMock().stubFor(post(urlEqualTo("/memories"))
            .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("b")))
            .willReturn(okJson("{\"results\": [{\"id\":\"mem-bbb\",\"memory\":\"b\",\"event\":\"ADD\"}]}")));

        final var result = store.storeAll(List.of(
            new MemoryInput("entity-1", DOMAIN, TENANT, null, "a", Map.of()),
            new MemoryInput("entity-1", DOMAIN, TENANT, null, "b", Map.of())
        ));

        assertTrue(result.allSucceeded());
        assertEquals(List.of("mem-aaa", "mem-bbb"), result.stored());
        wireMock().verify(2, postRequestedFor(urlEqualTo("/memories")));
    }

    @Test
    void storeAll_empty_returns_empty_no_http() {
        assertTrue(store.storeAll(List.of()).stored().isEmpty());
        wireMock().verify(0, postRequestedFor(urlEqualTo("/memories")));
    }

    @Test
    void storeAll_any_tenant_mismatch_fires_zero_http_calls() {
        assertThrows(SecurityException.class, () ->
            store.storeAll(List.of(
                new MemoryInput("entity-1", DOMAIN, TENANT,       null, "ok",  Map.of()),
                new MemoryInput("entity-2", DOMAIN, OTHER_TENANT, null, "bad", Map.of())
            )));
        wireMock().verify(0, postRequestedFor(urlEqualTo("/memories")));
    }

    @Test
    void storeAll_http_failure_collected_in_result() {
        // Backend failures are now collected in StoreAllResult.failures() instead of thrown.
        // All HTTP calls are still made; failures carry Mem0StoreException as cause.
        wireMock().stubFor(post(urlEqualTo("/memories")).willReturn(serverError()));
        var result = store.storeAll(List.of(
            new MemoryInput("entity-1", DOMAIN, TENANT, null, "a", Map.of()),
            new MemoryInput("entity-1", DOMAIN, TENANT, null, "b", Map.of())
        ));
        wireMock().verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/memories")));
        assertFalse(result.allSucceeded(), "all stores failed — result must not be all-succeeded");
        assertTrue(result.stored().isEmpty(), "no successful stores");
        assertFalse(result.failures().isEmpty(), "at least one failure must be recorded");
        result.failures().forEach(f ->
            assertInstanceOf(Mem0StoreException.class, f.cause(),
                "failure cause must be Mem0StoreException"));
    }

    // ── storeAll parallel ──────────────────────────────────────────────────────

    @Test
    void storeAll_fires_requests_concurrently() {
        // 8 requests with 300ms delay each. cap=4 (default) → ceil(8/4)*300 = 600ms.
        // Sequential would be 8*300 = 2400ms. Assert elapsed < 1200ms (2x headroom).
        wireMock().stubFor(post(urlEqualTo("/memories"))
            .willReturn(okJson("{\"results\":[{\"id\":\"x\",\"memory\":\"t\",\"event\":\"ADD\"}]}")
                .withFixedDelay(300)));
        principal.setTenancyId(TENANT);
        var inputs = IntStream.range(0, 8)
            .mapToObj(i -> new MemoryInput("e" + i, DOMAIN, TENANT, null, "text", Map.of()))
            .collect(Collectors.toList());

        long start = System.currentTimeMillis();
        var result = store.storeAll(inputs);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(8, result.stored().size());
        assertTrue(elapsed < 1200,
            "storeAll must execute in parallel; elapsed=" + elapsed + "ms (expected < 1200ms)");
    }

    @Test
    void storeAll_preserves_output_size_and_non_empty_ids() {
        wireMock().stubFor(post(urlEqualTo("/memories"))
            .willReturn(okJson("{\"results\":[{\"id\":\"mem-x\",\"memory\":\"t\",\"event\":\"ADD\"}]}")));
        principal.setTenancyId(TENANT);
        var inputs = List.of(
            new MemoryInput("e1", DOMAIN, TENANT, null, "a", Map.of()),
            new MemoryInput("e2", DOMAIN, TENANT, null, "b", Map.of()),
            new MemoryInput("e3", DOMAIN, TENANT, null, "c", Map.of())
        );
        var result = store.storeAll(inputs);
        assertTrue(result.allSucceeded());
        assertEquals(3, result.stored().size());
        result.stored().forEach(id -> assertFalse(id.isEmpty(), "ID must not be empty"));
    }

    @Test
    void storeAll_backend_failures_collected_per_item() {
        wireMock().stubFor(post(urlEqualTo("/memories"))
            .willReturn(serverError().withBody("{\"detail\":\"server error\"}")));
        principal.setTenancyId(TENANT);
        var inputs = List.of(
            new MemoryInput("e1", DOMAIN, TENANT, null, "a", Map.of()),
            new MemoryInput("e2", DOMAIN, TENANT, null, "b", Map.of())
        );
        var result = store.storeAll(inputs);
        assertFalse(result.allSucceeded());
        assertTrue(result.stored().isEmpty());
        assertEquals(2, result.failures().size());
        result.failures().forEach(f ->
            assertInstanceOf(Mem0StoreException.class, f.cause()));
        // Verify inputIndex is populated for retry correlation
        var indexes = result.failures().stream().map(f -> f.inputIndex()).sorted().toList();
        assertEquals(List.of(0, 1), indexes);
    }

    @Test
    void storeAll_single_item_uses_cap_of_one() {
        // With 1 input and config=4: Math.max(1, Math.min(4,1)) = 1. Semaphore(1) — no deadlock.
        stubAddOk("safe-id");
        principal.setTenancyId(TENANT);
        var result = store.storeAll(List.of(new MemoryInput("e1", DOMAIN, TENANT, null, "x", Map.of())));
        assertTrue(result.allSucceeded());
        assertEquals(List.of("safe-id"), result.stored());
    }
}
