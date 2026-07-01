package io.casehub.neocortex.memory.mem0;

import io.casehub.neocortex.memory.*;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.neocortex.memory.mem0.dto.*;
import io.quarkus.arc.Arc;
import io.micrometer.core.annotation.Timed;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Alternative
@Priority(1)
@ApplicationScoped
public class Mem0CaseMemoryStore implements CaseMemoryStore {

    @Override
    public java.util.Set<MemoryCapability> capabilities() {
        return java.util.Set.of(
            MemoryCapability.CHRONOLOGICAL_ORDER,
            MemoryCapability.DOMAIN_SCOPED,
            MemoryCapability.CASE_SCOPED,
            MemoryCapability.SINCE_FILTER,
            MemoryCapability.BATCH_STORE,
            MemoryCapability.SEMANTIC_SEARCH,
            MemoryCapability.ERASE_BY_ID,
            MemoryCapability.ERASE_ENTITY,
            MemoryCapability.ERASE_DOMAIN_CASE,
            MemoryCapability.CROSS_TENANT_ERASE
        );
    }

    private static final Logger LOG = Logger.getLogger(Mem0CaseMemoryStore.class);

    static final String SEP = "::";

    @Inject @RestClient Mem0Client client;
    @Inject Mem0Config config;
    @Inject CurrentPrincipal principal;

    private boolean requestContextActive() {
        var c = Arc.container();
        return c == null || c.requestContext().isActive();
    }

    @Timed(value = "casehub.memory.mem0", histogram = true, extraTags = {"operation", "store"})
    @Override
    public String store(MemoryInput input) {
        MemoryPermissions.assertTenant(input.tenantId(), principal, requestContextActive());
        return sendAdd(input);
    }

    @Timed(value = "casehub.memory.mem0", histogram = true, extraTags = {"operation", "storeAll"})
    @Override
    public StoreAllResult storeAll(List<MemoryInput> inputs) {
        if (inputs.isEmpty()) return StoreAllResult.empty();
        inputs.forEach(i -> MemoryPermissions.assertTenant(i.tenantId(), principal, requestContextActive()));
        final int cap = Math.max(1, Math.min(config.storeAllConcurrency(), inputs.size()));
        final var sem = new Semaphore(cap);
        // Each Uni produces either the stored ID (success) or null (failure captured in failureSlots).
        // We can't throw inside the Uni and still get ordered results, so failures are tracked via
        // a concurrent list; each StoreFailure carries its inputIndex for caller ordering.
        final var failureSlots = new CopyOnWriteArrayList<StoreFailure>();
        final List<Uni<String>> unis = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            final int idx = i;
            final MemoryInput input = inputs.get(i);
            unis.add(Uni.createFrom().<String>item(() -> {
                sem.acquireUninterruptibly();
                try { return sendAdd(input); }
                catch (RuntimeException e) {
                    failureSlots.add(new StoreFailure(idx, input, e));
                    return null; // slot will be null in the ordered result list
                }
                finally { sem.release(); }
            }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()));
        }
        // andFailFast() will not throw because all RuntimeExceptions are caught above.
        // Mutiny guarantees output order matches input order.
        List<String> rawIds = Uni.join().all(unis).andFailFast().await().indefinitely();
        List<String> stored = rawIds.stream().filter(Objects::nonNull).collect(Collectors.toList());
        return new StoreAllResult(stored, List.copyOf(failureSlots));
    }

    private String sendAdd(MemoryInput input) {
        final var request = new Mem0AddRequest(
            List.of(new Mem0AddRequest.Mem0Message("user", input.text())),
            compoundUserId(input.tenantId(), input.entityId()),
            input.domain().name(),
            input.caseId(),
            config.infer(),
            new HashMap<>(input.attributes())
        );
        final Mem0AddResponse response;
        try {
            response = client.add(request);
        } catch (WebApplicationException e) {
            throw toStoreException(e);
        }
        if (response.results() == null || response.results().isEmpty()) {
            throw new Mem0StoreException("store produced no result for: " + input.entityId());
        }
        return response.results().get(0).id();
    }

    @Timed(value = "casehub.memory.mem0", histogram = true, extraTags = {"operation", "query"})
    @Override
    public List<Memory> query(MemoryQuery query) {
        MemoryPermissions.assertTenant(query.tenantId(), principal, requestContextActive());

        final boolean relevanceWithQuestion =
            query.order() == MemoryOrder.RELEVANCE && query.question() != null;

        final List<Mem0Memory> all = new ArrayList<>();
        for (final String entityId : query.entityIds()) {
            all.addAll(fetchForEntity(query, entityId, relevanceWithQuestion));
        }

        final Instant barrier = query.since(); // null when no since filter

        // CHRONOLOGICAL: newest first. Also the fallback for RELEVANCE + null question.
        // For RELEVANCE + question: results are per-entity score-ordered (entity-order concat).
        // Scores are NOT cross-comparable across calls (variable max_possible in Mem0 scoring).
        final Comparator<Mem0Memory> order = relevanceWithQuestion
            ? null // preserve fan-out order; per-entity score ordering already applied by Mem0
            : Comparator.<Mem0Memory, Instant>comparing(m -> parseCreatedAt(m.createdAt()), Comparator.reverseOrder());

        final String tenantId = query.tenantId();
        var stream = all.stream()
            .filter(m -> barrier == null || isAfterSince(m, barrier));
        if (order != null) stream = stream.sorted(order);
        return stream
            .limit(query.limit())
            .map(m -> toMemory(m, tenantId))
            .collect(Collectors.toList());
    }

    private List<Mem0Memory> fetchForEntity(MemoryQuery query, String entityId, boolean search) {
        final String userId = compoundUserId(query.tenantId(), entityId);
        try {
            if (search) {
                final int topK = query.since() != null ? config.sinceSearchTopK() : query.limit();
                final var req = new Mem0SearchRequest(
                    query.question(), userId, query.domain().name(), query.caseId(),
                    topK, config.searchThreshold()
                );
                final Mem0ListResponse r = client.search(req);
                return r.results() != null ? r.results() : List.of();
            } else {
                final Mem0ListResponse r = client.list(userId, query.domain().name(), query.caseId());
                return r.results() != null ? r.results() : List.of();
            }
        } catch (WebApplicationException e) {
            throw toStoreException(e);
        }
    }

    @Timed(value = "casehub.memory.mem0", histogram = true, extraTags = {"operation", "erase"})
    @Override
    public int erase(EraseRequest request) {
        MemoryPermissions.assertTenant(request.tenantId(), principal, requestContextActive());
        // Pre-list for count — same race caveat as eraseEntity(): writes arriving between
        // list() and deleteAll() may cause the returned count to understate actual deletion.
        try {
            final String userId = compoundUserId(request.tenantId(), request.entityId());
            final Mem0ListResponse listed = client.list(userId, request.domain().name(), request.caseId());
            final int count = listed.results() != null ? listed.results().size() : 0;
            client.deleteAll(userId, request.domain().name(), request.caseId());
            return count;
        } catch (WebApplicationException e) {
            throw toStoreException(e);
        }
    }

    @Timed(value = "casehub.memory.mem0", histogram = true, extraTags = {"operation", "eraseById"})
    @Override
    public void eraseById(String memoryId, String entityId, String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        // Preflight: verify ownership before DELETE.
        // entityId mismatch → silent no-op (no information leak about cross-entity existence).
        // null userId on 200 → treat as mismatch (unattributable memory).
        final Mem0Memory existing;
        try {
            existing = client.getById(memoryId);
        } catch (WebApplicationException e) {
            if (e.getResponse() != null && e.getResponse().getStatus() == 404) {
                return; // already absent — erasure satisfied
            }
            throw toStoreException(e);
        }
        final String expectedUserId = compoundUserId(tenantId, entityId);
        if (!expectedUserId.equals(existing.userId())) {
            return; // wrong entity — silent no-op
        }
        try {
            client.deleteById(memoryId);
        } catch (WebApplicationException e) {
            if (e.getResponse() != null && e.getResponse().getStatus() == 404) {
                return; // concurrently deleted — erasure satisfied
            }
            throw toStoreException(e);
        }
    }

    @Timed(value = "casehub.memory.mem0", histogram = true, extraTags = {"operation", "eraseEntity"})
    @Override
    public int eraseEntity(String entityId, String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        // No agent_id → all domains. No run_id → all cases. GDPR Art.17 wipe.
        // Count is best-effort: new writes arriving between list() and deleteAll()
        // may cause the returned count to understate the actual deletion.
        final String userId = compoundUserId(tenantId, entityId);
        try {
            final Mem0ListResponse listed = client.list(userId, null, null);
            final int count = listed.results() != null ? listed.results().size() : 0;
            client.deleteAll(userId, null, null);
            return count;
        } catch (WebApplicationException e) {
            throw toStoreException(e);
        }
    }

    @Timed(value = "casehub.memory.mem0", histogram = true, extraTags = {"operation", "eraseEntityAcrossTenants"})
    @Override
    public int eraseEntityAcrossTenants(String entityId, Set<String> tenantIds) {
        MemoryPermissions.assertCrossTenantAdmin(principal);
        // Sequential: simplicity + retry-is-safe. deleteAll is idempotent — already-erased
        // tenants return an empty list on retry, so re-invoking after partial failure converges.
        int total = 0;
        for (String tenantId : tenantIds) {
            final String userId = compoundUserId(tenantId, entityId);
            try {
                final Mem0ListResponse listed = client.list(userId, null, null);
                total += listed.results() != null ? listed.results().size() : 0;
                client.deleteAll(userId, null, null);
            } catch (WebApplicationException e) {
                throw toStoreException(e);
            }
        }
        return total;
    }

    // ── shared helpers ────────────────────────────────────────────────────────

    static String compoundUserId(String tenantId, String entityId) {
        return tenantId + SEP + entityId;
    }

    static String extractEntityId(String userId) {
        if (userId == null) return "";
        final int idx = userId.indexOf(SEP);
        return idx < 0 ? userId : userId.substring(idx + SEP.length());
    }

    private boolean isAfterSince(Mem0Memory m, Instant since) {
        if (m.createdAt() == null) return true;
        try {
            return !Instant.parse(m.createdAt()).isBefore(since);
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    private Instant parseCreatedAt(String s) {
        if (s == null) return Instant.EPOCH;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    private Memory toMemory(Mem0Memory m, String tenantId) {
        return new Memory(
            m.id(),
            extractEntityId(m.userId()),
            new MemoryDomain(m.agentId() != null ? m.agentId() : ""),
            tenantId,
            m.runId(),
            m.memory() != null ? m.memory() : "",
            m.metadata() != null ? m.metadata() : Map.of(),
            parseCreatedAt(m.createdAt())
        );
    }

    private Mem0StoreException toStoreException(WebApplicationException e) {
        final int status = e.getResponse() != null ? e.getResponse().getStatus() : -1;
        String body = "";
        try {
            if (e.getResponse() != null) body = e.getResponse().readEntity(String.class);
        } catch (Exception bodyReadFailed) {
            LOG.debug("Could not read Mem0 error response body", bodyReadFailed);
        }
        return new Mem0StoreException(status, body, e);
    }
}
