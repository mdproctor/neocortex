package io.casehub.neocortex.memory.mem0;

import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryCapability;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.MemoryOrder;
import io.casehub.neocortex.memory.MemoryPermissions;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.neocortex.memory.StoreAllResult;
import io.casehub.neocortex.memory.StoreFailure;
import io.casehub.neocortex.memory.mem0.dto.Mem0AddRequest;
import io.casehub.neocortex.memory.mem0.dto.Mem0Memory;
import io.casehub.neocortex.memory.mem0.dto.Mem0SearchRequest;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.micrometer.core.annotation.Timed;
import io.quarkus.arc.Arc;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Alternative
@Priority(1)
@ApplicationScoped
public class Mem0CaseMemoryStore implements CaseMemoryStore {

    private static final Logger LOG = Logger.getLogger(Mem0CaseMemoryStore.class);

    static final String SEP = "::";

    @Inject @RestClient Mem0Client client;
    @Inject Mem0Config config;
    @Inject CurrentPrincipal principal;

    private boolean requestContextActive() {
        var c = Arc.container();
        return c == null || c.requestContext().isActive();
    }

    @Override
    public Set<MemoryCapability> capabilities() {
        return Set.of(
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

        List<String> stored = new ArrayList<>();
        List<StoreFailure> failures = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            try {
                stored.add(sendAdd(inputs.get(i)));
            } catch (RuntimeException e) {
                failures.add(new StoreFailure(i, inputs.get(i), e));
            }
        }
        return new StoreAllResult(stored, failures);
    }

    private String sendAdd(MemoryInput input) {
        var request = new Mem0AddRequest(
            List.of(new Mem0AddRequest.Mem0Message("user", input.text())),
            compoundUserId(input.tenantId(), input.entityId()),
            input.domain().name(),
            input.caseId(),
            config.infer(),
            new HashMap<>(input.attributes())
        );
        try {
            var response = client.add(request);
            if (response.results() == null || response.results().isEmpty()) {
                throw new Mem0StoreException("store produced no result for: " + input.entityId());
            }
            return response.results().get(0).id();
        } catch (WebApplicationException e) {
            throw toStoreException(e);
        }
    }

    @Timed(value = "casehub.memory.mem0", histogram = true, extraTags = {"operation", "query"})
    @Override
    public List<Memory> query(MemoryQuery query) {
        MemoryPermissions.assertTenant(query.tenantId(), principal, requestContextActive());

        boolean relevanceWithQuestion =
            query.order() == MemoryOrder.RELEVANCE && query.question() != null;

        List<Mem0Memory> all = new ArrayList<>();
        for (String entityId : query.entityIds()) {
            all.addAll(fetchForEntity(query, entityId, relevanceWithQuestion));
        }

        Instant barrier = query.since();
        Comparator<Mem0Memory> order = relevanceWithQuestion
            ? null
            : Comparator.<Mem0Memory, Instant>comparing(
                m -> parseCreatedAt(m.createdAt()), Comparator.reverseOrder());

        var stream = all.stream()
            .filter(m -> barrier == null || isAfterSince(m, barrier));
        if (order != null) stream = stream.sorted(order);
        return stream
            .limit(query.limit())
            .map(m -> toMemory(m, query.tenantId()))
            .collect(Collectors.toList());
    }

    private List<Mem0Memory> fetchForEntity(MemoryQuery query, String entityId, boolean search) {
        String userId = compoundUserId(query.tenantId(), entityId);
        try {
            if (search) {
                int topK = query.since() != null ? config.sinceSearchTopK() : query.limit();
                var req = new Mem0SearchRequest(
                    query.question(), userId, query.domain().name(), query.caseId(),
                    topK, config.searchThreshold()
                );
                var r = client.search(req);
                return r.results() != null ? r.results() : List.of();
            } else {
                var r = client.list(userId, query.domain().name(), query.caseId());
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
        String userId = compoundUserId(request.tenantId(), request.entityId());
        try {
            var listed = client.list(userId, request.domain().name(), request.caseId());
            int count = listed.results() != null ? listed.results().size() : 0;
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
        String expectedUserId = compoundUserId(tenantId, entityId);
        try {
            var existing = client.getById(memoryId);
            if (!expectedUserId.equals(existing.userId())) return;
            client.deleteById(memoryId);
        } catch (WebApplicationException e) {
            if (e.getResponse() != null && e.getResponse().getStatus() == 404) return;
            throw toStoreException(e);
        }
    }

    @Timed(value = "casehub.memory.mem0", histogram = true, extraTags = {"operation", "eraseEntity"})
    @Override
    public int eraseEntity(String entityId, String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        String userId = compoundUserId(tenantId, entityId);
        try {
            var listed = client.list(userId, null, null);
            int count = listed.results() != null ? listed.results().size() : 0;
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
        int total = 0;
        for (String tenantId : tenantIds) {
            String userId = compoundUserId(tenantId, entityId);
            try {
                var listed = client.list(userId, null, null);
                int count = listed.results() != null ? listed.results().size() : 0;
                client.deleteAll(userId, null, null);
                total += count;
            } catch (WebApplicationException e) {
                throw toStoreException(e);
            }
        }
        return total;
    }

    static String compoundUserId(String tenantId, String entityId) {
        return tenantId + SEP + entityId;
    }

    static String extractEntityId(String userId) {
        if (userId == null) return "";
        int idx = userId.indexOf(SEP);
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
        int status = e.getResponse() != null ? e.getResponse().getStatus() : -1;
        String body = "";
        try {
            if (e.getResponse() != null) body = e.getResponse().readEntity(String.class);
        } catch (Exception bodyReadFailed) {
            LOG.debug("Could not read Mem0 error response body", bodyReadFailed);
        }
        return new Mem0StoreException(status, body, e);
    }
}
