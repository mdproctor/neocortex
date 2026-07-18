package io.casehub.neocortex.memory.graphiti;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.GraphMemoryQuery;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryAttributeKeys;
import io.casehub.neocortex.memory.MemoryCapability;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.MemoryOrder;
import io.casehub.neocortex.memory.MemoryPermissions;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.neocortex.memory.ReactiveGraphCaseMemoryStore;
import io.casehub.neocortex.memory.StoreAllResult;
import io.casehub.neocortex.memory.graphiti.dto.AddMessage;
import io.casehub.neocortex.memory.graphiti.dto.AddMessagesRequest;
import io.casehub.neocortex.memory.graphiti.dto.FactResult;
import io.casehub.neocortex.memory.graphiti.dto.GraphitiEpisodicNode;
import io.casehub.neocortex.memory.graphiti.dto.GraphitiSearchRequest;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.micrometer.core.annotation.Timed;
import io.quarkus.arc.Arc;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
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
public class ReactiveGraphitiCaseMemoryStore implements ReactiveGraphCaseMemoryStore {

    private static final Logger LOG = Logger.getLogger(ReactiveGraphitiCaseMemoryStore.class);
    private static final int MAX_EPISODES_FOR_COUNT = 10_000;

    static final String SEP = "::";

    @Inject @RestClient ReactiveGraphitiClient client;
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

    // ── store ─────────────────────────────────────────────────────────────────

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "store"})
    @Override
    public Uni<String> store(MemoryInput input) {
        MemoryPermissions.assertTenant(input.tenantId(), principal, requestContextActive());
        String episodeUuid = UUID.randomUUID().toString();
        return sendAdd(input, episodeUuid).replaceWith(episodeUuid);
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "storeAll"})
    @Override
    public Uni<StoreAllResult> storeAll(List<MemoryInput> inputs) {
        if (inputs.isEmpty()) return Uni.createFrom().item(StoreAllResult.empty());
        inputs.forEach(i -> MemoryPermissions.assertTenant(i.tenantId(), principal, requestContextActive()));
        return Multi.createFrom().iterable(inputs)
            .onItem().transformToUniAndConcatenate(input -> {
                String uuid = UUID.randomUUID().toString();
                return sendAdd(input, uuid).replaceWith(uuid);
            })
            .collect().asList()
            .map(ids -> new StoreAllResult(List.copyOf(ids), List.of()));
    }

    private Uni<Void> sendAdd(MemoryInput input, String episodeUuid) {
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
        return client.addMessages(request)
            .replaceWithVoid()
            .onFailure(WebApplicationException.class)
                .transform(e -> GraphitiStoreException.from((WebApplicationException) e));
    }

    // ── query ─────────────────────────────────────────────────────────────────

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "query"})
    @Override
    public Uni<List<Memory>> query(MemoryQuery query) {
        MemoryPermissions.assertTenant(query.tenantId(), principal, requestContextActive());

        boolean relevanceWithQuestion =
            query.order() == MemoryOrder.RELEVANCE && query.question() != null;

        List<Uni<List<Memory>>> entityUnis = new ArrayList<>();
        for (String entityId : query.entityIds()) {
            if (relevanceWithQuestion) {
                entityUnis.add(searchForEntity(query, entityId));
            } else {
                entityUnis.add(episodesForEntity(query, entityId));
            }
        }

        return Uni.join().all(entityUnis).andFailFast()
            .map(entityResults -> {
                List<Memory> all = new ArrayList<>();
                for (List<Memory> batch : entityResults) {
                    all.addAll(batch);
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
            });
    }

    private Uni<List<Memory>> searchForEntity(MemoryQuery query, String entityId) {
        var req = new GraphitiSearchRequest(
            List.of(compoundGroupId(query.tenantId(), entityId, query.domain().name())),
            query.question(),
            query.limit()
        );
        return client.search(req)
            .map(resp -> {
                if (resp.facts() == null) return List.<Memory>of();
                return resp.facts().stream()
                    .map(f -> factToMemory(f, entityId, query.domain(), query.tenantId()))
                    .collect(Collectors.toList());
            })
            .onFailure(WebApplicationException.class)
                .transform(e -> GraphitiStoreException.from((WebApplicationException) e));
    }

    private Uni<List<Memory>> episodesForEntity(MemoryQuery query, String entityId) {
        int lastN = query.limit() * query.entityIds().size();
        String groupId = compoundGroupId(query.tenantId(), entityId, query.domain().name());
        return client.getEpisodes(groupId, lastN)
            .map(episodes -> {
                if (episodes == null) return List.<Memory>of();
                return episodes.stream()
                    .map(ep -> episodeToMemory(ep, query.domain(), query.tenantId()))
                    .collect(Collectors.toList());
            })
            .onFailure(WebApplicationException.class)
                .transform(e -> GraphitiStoreException.from((WebApplicationException) e));
    }

    // ── graphQuery ────────────────────────────────────────────────────────────

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "graphQuery"})
    @Override
    public Uni<List<Memory>> graphQuery(GraphMemoryQuery query) {
        MemoryPermissions.assertTenant(query.tenantId(), principal, requestContextActive());

        requireCapability(MemoryCapability.FACT_SEARCH);
        if (query.validAt() != null)      requireCapability(MemoryCapability.TEMPORAL_GRAPH);
        if (query.entityTypes() != null)  requireCapability(MemoryCapability.ENTITY_TYPE_FILTER);

        List<Uni<List<Memory>>> entityUnis = new ArrayList<>();
        for (String entityId : query.entityIds()) {
            var req = new GraphitiSearchRequest(
                List.of(compoundGroupId(query.tenantId(), entityId, query.domain().name())),
                query.question(),
                query.limit()
            );
            entityUnis.add(
                client.search(req)
                    .map(resp -> {
                        if (resp.facts() == null) return List.<Memory>of();
                        return resp.facts().stream()
                            .map(f -> factToMemory(f, entityId, query.domain(), query.tenantId()))
                            .collect(Collectors.toList());
                    })
                    .onFailure(WebApplicationException.class)
                        .transform(e -> GraphitiStoreException.from((WebApplicationException) e))
            );
        }

        return Uni.join().all(entityUnis).andFailFast()
            .map(entityResults -> {
                List<Memory> all = new ArrayList<>();
                for (List<Memory> batch : entityResults) {
                    all.addAll(batch);
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
            });
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

    // ── erase ─────────────────────────────────────────────────────────────────

    private Uni<Integer> eraseGroup(String groupId) {
        return client.getEpisodes(groupId, MAX_EPISODES_FOR_COUNT)
            .map(episodes -> episodes != null ? episodes.size() : 0)
            .chain(count -> client.deleteGroup(groupId).replaceWith(count))
            .onFailure(WebApplicationException.class).recoverWithUni(e -> {
                if (((WebApplicationException) e).getResponse() != null
                    && ((WebApplicationException) e).getResponse().getStatus() == 404) {
                    return Uni.createFrom().item(0);
                }
                return Uni.createFrom().failure(GraphitiStoreException.from((WebApplicationException) e));
            });
    }

    private Uni<List<GraphitiEpisodicNode>> getEpisodesOrEmpty(String groupId) {
        return client.getEpisodes(groupId, MAX_EPISODES_FOR_COUNT)
            .map(eps -> eps != null ? eps : List.<GraphitiEpisodicNode>of())
            .onFailure(WebApplicationException.class).recoverWithUni(e -> {
                if (((WebApplicationException) e).getResponse() != null
                    && ((WebApplicationException) e).getResponse().getStatus() == 404) {
                    return Uni.createFrom().item(List.of());
                }
                return Uni.createFrom().failure(GraphitiStoreException.from((WebApplicationException) e));
            });
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "erase"})
    @Override
    public Uni<Integer> erase(EraseRequest request) {
        MemoryPermissions.assertTenant(request.tenantId(), principal, requestContextActive());
        String groupId = compoundGroupId(
            request.tenantId(), request.entityId(), request.domain().name());

        if (request.caseId() == null) {
            return eraseGroup(groupId);
        }

        return getEpisodesOrEmpty(groupId)
            .chain(episodes -> {
                List<Uni<Integer>> deletes = new ArrayList<>();
                for (GraphitiEpisodicNode ep : episodes) {
                    if (!matchesCaseId(ep.sourceDescription(), request.caseId())) continue;
                    deletes.add(
                        client.deleteEpisode(ep.uuid())
                            .replaceWith(1)
                            .onFailure(WebApplicationException.class).recoverWithUni(e -> {
                                if (((WebApplicationException) e).getResponse() != null
                                    && ((WebApplicationException) e).getResponse().getStatus() == 404) {
                                    return Uni.createFrom().item(1);
                                }
                                return Uni.createFrom().failure(
                                    GraphitiStoreException.from((WebApplicationException) e));
                            })
                    );
                }
                if (deletes.isEmpty()) return Uni.createFrom().item(0);
                return Uni.join().all(deletes).andFailFast()
                    .map(counts -> counts.stream().mapToInt(Integer::intValue).sum());
            });
    }

    @Override
    public Uni<Void> eraseById(String memoryId, String entityId, String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        requireCapability(MemoryCapability.ERASE_BY_ID);
        return Uni.createFrom().voidItem();
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "eraseEntity"})
    @Override
    public Uni<Integer> eraseEntity(String entityId, String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        List<String> domains = config.knownDomains().orElse(List.of());
        if (domains.isEmpty()) {
            return Uni.createFrom().failure(
                new io.casehub.neocortex.memory.MemoryCapabilityException(
                    MemoryCapability.ERASE_ENTITY, getClass()));
        }
        return Multi.createFrom().iterable(domains)
            .onItem().transformToUniAndConcatenate(domain ->
                eraseGroup(compoundGroupId(tenantId, entityId, domain)))
            .collect().asList()
            .map(counts -> counts.stream().mapToInt(Integer::intValue).sum());
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "eraseEntityAcrossTenants"})
    @Override
    public Uni<Integer> eraseEntityAcrossTenants(String entityId, Set<String> tenantIds) {
        MemoryPermissions.assertCrossTenantAdmin(principal);
        List<String> domains = config.knownDomains().orElse(List.of());
        if (domains.isEmpty()) {
            return Uni.createFrom().failure(
                new io.casehub.neocortex.memory.MemoryCapabilityException(
                    MemoryCapability.CROSS_TENANT_ERASE, getClass()));
        }
        return Multi.createFrom().iterable(tenantIds)
            .onItem().transformToUniAndConcatenate(tenantId ->
                Multi.createFrom().iterable(domains)
                    .onItem().transformToUniAndConcatenate(domain ->
                        eraseGroup(compoundGroupId(tenantId, entityId, domain)))
                    .collect().asList()
                    .map(counts -> counts.stream().mapToInt(Integer::intValue).sum())
            )
            .collect().asList()
            .map(counts -> counts.stream().mapToInt(Integer::intValue).sum());
    }

    // ── mapping helpers ───────────────────────────────────────────────────────

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

    // ── static helpers ────────────────────────────────────────────────────────

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
