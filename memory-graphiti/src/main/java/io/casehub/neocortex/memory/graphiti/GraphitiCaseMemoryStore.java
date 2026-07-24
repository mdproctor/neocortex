package io.casehub.neocortex.memory.graphiti;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.GraphCaseMemoryStore;
import io.casehub.neocortex.memory.GraphMemoryQuery;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryAttributeKeys;
import io.casehub.neocortex.memory.MemoryCapability;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.MemoryOrder;
import io.casehub.neocortex.memory.MemoryPermissions;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.neocortex.memory.StoreAllResult;
import io.casehub.neocortex.memory.graphiti.dto.AddMessage;
import io.casehub.neocortex.memory.graphiti.dto.AddMessagesRequest;
import io.casehub.neocortex.memory.graphiti.dto.FactResult;
import io.casehub.neocortex.memory.graphiti.dto.GraphitiEpisodicNode;
import io.casehub.neocortex.memory.graphiti.dto.GraphitiSearchRequest;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Alternative
@Priority(2)
@ApplicationScoped
public class GraphitiCaseMemoryStore implements GraphCaseMemoryStore {

    private static final Logger LOG = Logger.getLogger(GraphitiCaseMemoryStore.class);
    private static final int MAX_EPISODES_FOR_COUNT = 10_000;

    static final String SEP = "::";

    @Inject @RestClient GraphitiClient client;
    @Inject CurrentPrincipal principal;
    @Inject GraphitiConfig config;

    private boolean requestContextActive() {
        var c = Arc.container();
        return c == null || c.requestContext().isActive();
    }

    @Override
    public Set<MemoryCapability> capabilities() {
        var caps = new HashSet<>(Set.of(
            MemoryCapability.CHRONOLOGICAL_ORDER,
            MemoryCapability.SINCE_FILTER,
            MemoryCapability.BATCH_STORE,
            MemoryCapability.SEMANTIC_SEARCH,
            MemoryCapability.TEMPORAL_GRAPH,
            MemoryCapability.FACT_SEARCH,
            MemoryCapability.DOMAIN_SCOPED,
            MemoryCapability.ERASE_DOMAIN_CASE
        ));
        if (!config.knownDomains().orElse(List.of()).isEmpty()) {
            caps.add(MemoryCapability.ERASE_ENTITY);
            caps.add(MemoryCapability.CROSS_TENANT_ERASE);
        }
        return Set.copyOf(caps);
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "store"})
    @Override
    public String store(MemoryInput input) {
        MemoryPermissions.assertTenant(input.tenantId(), principal, requestContextActive());
        String episodeUuid = UUID.randomUUID().toString();
        sendAdd(input, episodeUuid);
        return episodeUuid;
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "storeAll"})
    @Override
    public StoreAllResult storeAll(List<MemoryInput> inputs) {
        if (inputs.isEmpty()) return StoreAllResult.empty();
        inputs.forEach(i -> MemoryPermissions.assertTenant(i.tenantId(), principal, requestContextActive()));
        List<String> ids = new ArrayList<>();
        for (MemoryInput input : inputs) {
            String uuid = UUID.randomUUID().toString();
            sendAdd(input, uuid);
            ids.add(uuid);
        }
        return new StoreAllResult(List.copyOf(ids), List.of());
    }

    private void sendAdd(MemoryInput input, String episodeUuid) {
        var message = new AddMessage(
            input.text(),
            episodeUuid,
            input.entityId(),
            "user",
            null,
            Instant.now(),
            sourceDescription(input)
        );
        var request = new AddMessagesRequest(
            compoundGroupId(input.tenantId(), input.entityId(), input.domain().name()),
            List.of(message)
        );
        try {
            client.addMessages(request);
        } catch (WebApplicationException e) {
            throw GraphitiStoreException.from(e);
        }
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "query"})
    @Override
    public List<Memory> query(MemoryQuery query) {
        MemoryPermissions.assertTenant(query.tenantId(), principal, requestContextActive());

        boolean relevanceWithQuestion =
            query.order() == MemoryOrder.RELEVANCE && query.question() != null;

        List<Memory> all = new ArrayList<>();
        for (String entityId : query.entityIds()) {
            if (relevanceWithQuestion) {
                all.addAll(searchForEntity(query, entityId));
            } else {
                all.addAll(episodesForEntity(query, entityId));
            }
        }

        var stream = all.stream();
        if (query.since() != null) {
            Instant barrier = query.since();
            stream = stream.filter(m -> !m.createdAt().isBefore(barrier));
        }
        if (!relevanceWithQuestion) {
            stream = stream.sorted(Comparator.<Memory, Instant>comparing(Memory::createdAt).reversed());
        }
        return stream.limit(query.limit()).collect(Collectors.toList());
    }

    private List<Memory> searchForEntity(MemoryQuery query, String entityId) {
        var req = new GraphitiSearchRequest(
            List.of(compoundGroupId(query.tenantId(), entityId, query.domain().name())),
            query.question(),
            query.limit()
        );
        try {
            var resp = client.search(req);
            if (resp.facts() == null) return List.of();
            return resp.facts().stream()
                .map(f -> factToMemory(f, entityId, query.domain(), query.tenantId()))
                .collect(Collectors.toList());
        } catch (WebApplicationException e) {
            throw GraphitiStoreException.from(e);
        }
    }

    private List<Memory> episodesForEntity(MemoryQuery query, String entityId) {
        int lastN = query.limit() * query.entityIds().size();
        String groupId = compoundGroupId(query.tenantId(), entityId, query.domain().name());
        try {
            var episodes = client.getEpisodes(groupId, lastN);
            if (episodes == null) return List.of();
            return episodes.stream()
                .map(ep -> episodeToMemory(ep, query.domain(), query.tenantId()))
                .collect(Collectors.toList());
        } catch (WebApplicationException e) {
            throw GraphitiStoreException.from(e);
        }
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "graphQuery"})
    @Override
    public List<Memory> graphQuery(GraphMemoryQuery query) {
        MemoryPermissions.assertTenant(query.tenantId(), principal, requestContextActive());

        requireCapability(MemoryCapability.FACT_SEARCH);
        if (query.validAt() != null)      requireCapability(MemoryCapability.TEMPORAL_GRAPH);
        if (query.entityTypes() != null)  requireCapability(MemoryCapability.ENTITY_TYPE_FILTER);

        List<Memory> all = new ArrayList<>();
        for (String entityId : query.entityIds()) {
            var req = new GraphitiSearchRequest(
                List.of(compoundGroupId(query.tenantId(), entityId, query.domain().name())),
                query.question(),
                query.limit()
            );
            try {
                var resp = client.search(req);
                if (resp.facts() != null) {
                    resp.facts().stream()
                        .map(f -> factToMemory(f, entityId, query.domain(), query.tenantId()))
                        .forEach(all::add);
                }
            } catch (WebApplicationException e) {
                throw GraphitiStoreException.from(e);
            }
        }

        var stream = all.stream();
        if (query.since() != null) {
            Instant barrier = query.since();
            stream = stream.filter(m -> !m.createdAt().isBefore(barrier));
        }
        if (query.validAt() != null) {
            Instant at = query.validAt();
            stream = stream.filter(m -> isValidAt(m, at));
        }
        return stream.limit(query.limit()).collect(Collectors.toList());
    }

    private static boolean isValidAt(Memory m, Instant at) {
        String validFrom = m.attributes().get(MemoryAttributeKeys.VALID_FROM);
        String validUntil = m.attributes().get(MemoryAttributeKeys.VALID_UNTIL);
        if (validFrom == null) return true;
        Instant from = Instant.parse(validFrom);
        if (at.isBefore(from)) return false;
        if (validUntil != null) {
            Instant until = Instant.parse(validUntil);
            if (!at.isBefore(until)) return false;
        }
        return true;
    }

    private int eraseGroup(String groupId) {
        try {
            var episodes = client.getEpisodes(groupId, MAX_EPISODES_FOR_COUNT);
            int count = episodes != null ? episodes.size() : 0;
            client.deleteGroup(groupId);
            return count;
        } catch (WebApplicationException e) {
            if (e.getResponse() != null && e.getResponse().getStatus() == 404) return 0;
            throw GraphitiStoreException.from(e);
        }
    }

    private List<GraphitiEpisodicNode> getEpisodesOrEmpty(String groupId) {
        try {
            var eps = client.getEpisodes(groupId, MAX_EPISODES_FOR_COUNT);
            return eps != null ? eps : List.of();
        } catch (WebApplicationException e) {
            if (e.getResponse() != null && e.getResponse().getStatus() == 404) return List.of();
            throw GraphitiStoreException.from(e);
        }
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "erase"})
    @Override
    public int erase(EraseRequest request) {
        MemoryPermissions.assertTenant(request.tenantId(), principal, requestContextActive());
        String groupId = compoundGroupId(
            request.tenantId(), request.entityId(), request.domain().name());

        if (request.caseId() == null) {
            return eraseGroup(groupId);
        }

        var episodes = getEpisodesOrEmpty(groupId);
        int deleted = 0;
        for (GraphitiEpisodicNode ep : episodes) {
            if (!matchesCaseId(ep.sourceDescription(), request.caseId())) continue;
            try {
                client.deleteEpisode(ep.uuid());
                deleted++;
            } catch (WebApplicationException e) {
                if (e.getResponse() == null || e.getResponse().getStatus() != 404) {
                    throw GraphitiStoreException.from(e);
                }
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public void eraseById(String memoryId, String entityId, String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        requireCapability(MemoryCapability.ERASE_BY_ID);
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "eraseEntity"})
    @Override
    public int eraseEntity(String entityId, String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        List<String> domains = config.knownDomains().orElse(List.of());
        if (domains.isEmpty()) {
            throw new io.casehub.neocortex.memory.MemoryCapabilityException(
                MemoryCapability.ERASE_ENTITY, getClass());
        }
        int total = 0;
        for (String domain : domains) {
            total += eraseGroup(compoundGroupId(tenantId, entityId, domain));
        }
        return total;
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "eraseEntityAcrossTenants"})
    @Override
    public int eraseEntityAcrossTenants(String entityId, Set<String> tenantIds) {
        MemoryPermissions.assertCrossTenantAdmin(principal);
        List<String> domains = config.knownDomains().orElse(List.of());
        if (domains.isEmpty()) {
            throw new io.casehub.neocortex.memory.MemoryCapabilityException(
                MemoryCapability.CROSS_TENANT_ERASE, getClass());
        }
        int total = 0;
        for (String tenantId : tenantIds) {
            for (String domain : domains) {
                total += eraseGroup(compoundGroupId(tenantId, entityId, domain));
            }
        }
        return total;
    }

    private static Memory factToMemory(FactResult f, String entityId,
                                       MemoryDomain domain, String tenantId) {
        var attrs = new HashMap<String, String>();
        if (f.validAt() != null)   attrs.put(MemoryAttributeKeys.VALID_FROM, f.validAt().toString());
        if (f.invalidAt() != null) attrs.put(MemoryAttributeKeys.VALID_UNTIL, f.invalidAt().toString());
        return new Memory(
            f.uuid(), entityId, domain, tenantId, null,
            f.fact() != null ? f.fact() : "",
            Map.copyOf(attrs),
            f.createdAt() != null ? f.createdAt() : Instant.EPOCH
        );
    }

    private static Memory episodeToMemory(GraphitiEpisodicNode ep, MemoryDomain domain,
                                          String tenantId) {
        String entityId = extractEntityId(ep.groupId(), tenantId);
        var attrs = new HashMap<String, String>();
        if (ep.validAt() != null) attrs.put(MemoryAttributeKeys.VALID_FROM, ep.validAt().toString());
        return new Memory(
            ep.uuid(), entityId, domain, tenantId, null,
            ep.content() != null ? ep.content() : "",
            Map.copyOf(attrs),
            ep.createdAt() != null ? ep.createdAt() : Instant.EPOCH
        );
    }

    static String compoundGroupId(String tenantId, String entityId, String domain) {
        return tenantId + SEP + entityId + SEP + domain;
    }

    static String extractEntityId(String groupId, String tenantId) {
        if (groupId == null) return "";
        String prefix = tenantId + SEP;
        if (!groupId.startsWith(prefix)) return groupId;
        String afterTenant = groupId.substring(prefix.length());
        int sepIdx = afterTenant.indexOf(SEP);
        return sepIdx < 0 ? afterTenant : afterTenant.substring(0, sepIdx);
    }

    private static String sourceDescription(MemoryInput input) {
        String base = "domain=" + input.domain().name();
        return input.caseId() != null ? base + ";caseId=" + input.caseId() : base;
    }

    private static boolean matchesCaseId(String sourceDescription, String caseId) {
        if (sourceDescription == null) return false;
        for (String part : sourceDescription.split(";")) {
            if (part.equals("caseId=" + caseId)) return true;
        }
        return false;
    }
}
