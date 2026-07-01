package io.casehub.neocortex.memory.graphiti;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.neocortex.memory.*;
import io.casehub.neocortex.memory.graphiti.dto.*;
import io.quarkus.arc.Arc;
import io.micrometer.core.annotation.Timed;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Alternative
@Priority(2)
@ApplicationScoped
public class GraphitiCaseMemoryStore implements GraphCaseMemoryStore {

    private static final Logger LOG = Logger.getLogger(GraphitiCaseMemoryStore.class);

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
        // TEMPORAL_GRAPH: client-side filtering on validAt/invalidAt returned per fact.
        // ERASE_BY_ID absent: DELETE /episode/{uuid} only removes EpisodicNode;
        //   derived EntityNode/EntityEdge persist (getzep/graphiti#1083, platform#74).
        // ERASE_ENTITY: declared only when casehub.memory.graphiti.known-domains is configured.
        //   Without it, cross-domain entity wipes are unsupported (no Graphiti group listing endpoint).
        // ERASE_DOMAIN_CASE: domain-level (caseId=null) is cascading and complete;
        //   case-level (caseId!=null) bounded to MAX_EPISODES_FOR_COUNT (see erase() Javadoc).
        final var caps = new HashSet<>(Set.of(
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
    public String store(final MemoryInput input) {
        MemoryPermissions.assertTenant(input.tenantId(), principal, requestContextActive());
        final String episodeUuid = UUID.randomUUID().toString();
        sendAdd(input, episodeUuid);
        return episodeUuid;
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "storeAll"})
    @Override
    public StoreAllResult storeAll(final List<MemoryInput> inputs) {
        if (inputs.isEmpty()) return StoreAllResult.empty();
        // Pre-flight: verify all tenant assertions before any REST call
        inputs.forEach(i -> MemoryPermissions.assertTenant(i.tenantId(), principal, requestContextActive()));
        final var ids = new ArrayList<String>(inputs.size());
        for (final MemoryInput input : inputs) {
            final String uuid = UUID.randomUUID().toString();
            sendAdd(input, uuid);
            ids.add(uuid);
        }
        return new StoreAllResult(List.copyOf(ids), List.of());
    }

    private void sendAdd(final MemoryInput input, final String episodeUuid) {
        final var message = new AddMessage(
            input.text(),
            episodeUuid,
            input.entityId(),
            "user",
            null,
            Instant.now(),
            sourceDescription(input)
        );
        final var request = new AddMessagesRequest(
            compoundGroupId(input.tenantId(), input.entityId(), input.domain().name()),
            List.of(message)
        );
        try {
            client.addMessages(request);
        } catch (final WebApplicationException e) {
            throw GraphitiStoreException.from(e);
        }
    }

    // ── query ─────────────────────────────────────────────────────────────────

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "query"})
    @Override
    public List<Memory> query(final MemoryQuery query) {
        MemoryPermissions.assertTenant(query.tenantId(), principal, requestContextActive());

        final boolean relevanceWithQuestion =
            query.order() == MemoryOrder.RELEVANCE && query.question() != null;

        final List<Memory> all = new ArrayList<>();
        for (final String entityId : query.entityIds()) {
            if (relevanceWithQuestion) {
                all.addAll(searchForEntity(query, entityId));
            } else {
                all.addAll(episodesForEntity(query, entityId));
            }
        }

        var stream = all.stream();
        if (query.since() != null) {
            final Instant barrier = query.since();
            stream = stream.filter(m -> !m.createdAt().isBefore(barrier));
        }
        if (!relevanceWithQuestion) {
            // CHRONOLOGICAL: merge across entities, newest first
            stream = stream.sorted(Comparator.<Memory, Instant>comparing(Memory::createdAt).reversed());
        }
        // RELEVANCE: entity-order concatenation — order already preserved per entity
        return stream.limit(query.limit()).collect(Collectors.toList());
    }

    private List<Memory> searchForEntity(final MemoryQuery query, final String entityId) {
        final var req = new GraphitiSearchRequest(
            List.of(compoundGroupId(query.tenantId(), entityId, query.domain().name())),
            query.question(),
            query.limit()
        );
        try {
            final GraphitiSearchResponse resp = client.search(req);
            if (resp.facts() == null) return List.of();
            return resp.facts().stream()
                .map(f -> factToMemory(f, entityId, query.domain(), query.tenantId()))
                .collect(Collectors.toList());
        } catch (final WebApplicationException e) {
            throw GraphitiStoreException.from(e);
        }
    }

    private List<Memory> episodesForEntity(final MemoryQuery query, final String entityId) {
        final int lastN = query.limit() * query.entityIds().size();
        final String groupId = compoundGroupId(query.tenantId(), entityId, query.domain().name());
        try {
            final List<GraphitiEpisodicNode> episodes = client.getEpisodes(groupId, lastN);
            if (episodes == null) return List.of();
            return episodes.stream()
                .map(ep -> episodeToMemory(ep, query.domain(), query.tenantId()))
                .collect(Collectors.toList());
        } catch (final WebApplicationException e) {
            throw GraphitiStoreException.from(e);
        }
    }

    // ── graphQuery ────────────────────────────────────────────────────────────

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "graphQuery"})
    @Override
    public List<Memory> graphQuery(final GraphMemoryQuery query) {
        MemoryPermissions.assertTenant(query.tenantId(), principal, requestContextActive());

        // Capability checks — always require FACT_SEARCH first
        requireCapability(MemoryCapability.FACT_SEARCH);
        if (query.validAt() != null)      requireCapability(MemoryCapability.TEMPORAL_GRAPH);
        if (query.entityTypes() != null)  requireCapability(MemoryCapability.ENTITY_TYPE_FILTER);

        final List<Memory> all = new ArrayList<>();
        for (final String entityId : query.entityIds()) {
            final var req = new GraphitiSearchRequest(
                List.of(compoundGroupId(query.tenantId(), entityId, query.domain().name())),
                query.question(),
                query.limit()
            );
            try {
                final GraphitiSearchResponse resp = client.search(req);
                if (resp.facts() == null) continue;
                resp.facts().stream()
                    .map(f -> factToMemory(f, entityId, query.domain(), query.tenantId()))
                    .forEach(all::add);
            } catch (final WebApplicationException e) {
                throw GraphitiStoreException.from(e);
            }
        }

        var stream = all.stream();
        if (query.since() != null) {
            final Instant barrier = query.since();
            stream = stream.filter(m -> !m.createdAt().isBefore(barrier));
        }
        if (query.validAt() != null) {
            final Instant at = query.validAt();
            stream = stream.filter(m -> isValidAt(m, at));
        }
        // Entity-order concatenation — order already preserved per entity
        return stream.limit(query.limit()).collect(Collectors.toList());
    }

    /**
     * Returns true if the memory is temporally valid at the given instant.
     * Uses VALID_FROM / VALID_UNTIL attributes populated by factToMemory().
     */
    private static boolean isValidAt(final Memory m, final Instant at) {
        final String validFrom = m.attributes().get(MemoryAttributeKeys.VALID_FROM);
        final String validUntil = m.attributes().get(MemoryAttributeKeys.VALID_UNTIL);
        if (validFrom == null) return true; // no temporal data → include
        final Instant from = Instant.parse(validFrom);
        if (at.isBefore(from)) return false;
        if (validUntil != null) {
            final Instant until = Instant.parse(validUntil);
            if (!at.isBefore(until)) return false; // at >= until → fact expired
        }
        return true;
    }

    // ── erase ─────────────────────────────────────────────────────────────────

    /**
     * Fetches episode count then performs cascading DELETE of all episodes, entities,
     * and facts for a group. Returns 0 if the group has never been created (404).
     * Non-404 errors propagate as {@link GraphitiStoreException}.
     */
    private int eraseGroup(final String groupId) {
        try {
            final List<GraphitiEpisodicNode> episodes = client.getEpisodes(groupId, MAX_EPISODES_FOR_COUNT);
            final int count = episodes != null ? episodes.size() : 0;
            client.deleteGroup(groupId);
            return count;
        } catch (final WebApplicationException e) {
            if (e.getResponse() != null && e.getResponse().getStatus() == 404) {
                return 0; // group never existed — erasure satisfied
            }
            throw GraphitiStoreException.from(e);
        }
    }

    /**
     * Returns episodes for a group, or an empty list if the group does not exist (404).
     * Unlike {@link #eraseGroup}, this helper does NOT call deleteGroup — it cannot
     * distinguish "group absent" from "group exists with 0 episodes" and must not skip
     * deletion of empty-but-existing groups.
     */
    private List<GraphitiEpisodicNode> getEpisodesOrEmpty(final String groupId) {
        try {
            final List<GraphitiEpisodicNode> eps = client.getEpisodes(groupId, MAX_EPISODES_FOR_COUNT);
            return eps != null ? eps : List.of();
        } catch (final WebApplicationException e) {
            if (e.getResponse() != null && e.getResponse().getStatus() == 404) {
                return List.of(); // group never existed — no episodes to filter
            }
            throw GraphitiStoreException.from(e);
        }
    }

    /**
     * Erases memories for an entity within a specific domain (and optionally a specific case).
     *
     * <p><b>Branch 1 — domain-level ({@code caseId == null}):</b> calls
     * {@link #eraseGroup} which performs a cascading {@code DELETE /group/{groupId}}.
     * Complete: removes episodes, extracted entities, and relationship facts. Count is
     * capped at {@link #MAX_EPISODES_FOR_COUNT} — this is a count-only limitation;
     * the deletion itself is always complete.
     *
     * <p><b>Branch 2 — case-level ({@code caseId != null}):</b> fetches episodes via
     * {@link #getEpisodesOrEmpty}, filters by {@code source_description}, and issues a
     * {@code DELETE /episode/{uuid}} per match. <b>Best-effort only:</b> episode nodes
     * are removed but LLM-extracted entity/relationship facts may persist pending
     * getzep/graphiti#1083. Bounded to {@link #MAX_EPISODES_FOR_COUNT} — domain groups
     * exceeding this cap may have unmatched case episodes. For strict GDPR Art.17
     * compliance at case granularity, prefer Branch 1 ({@code caseId=null}).
     *
     * @return count of episodes erased (for GDPR Art.5(2) audit logging)
     */
    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "erase"})
    @Override
    public int erase(final EraseRequest request) {
        MemoryPermissions.assertTenant(request.tenantId(), principal, requestContextActive());
        final String groupId = compoundGroupId(
            request.tenantId(), request.entityId(), request.domain().name());

        if (request.caseId() == null) {
            // Branch 1: domain-level — cascading complete deletion
            return eraseGroup(groupId);
        }

        // Branch 2: case-level — episode-by-episode (best-effort; see Javadoc)
        final List<GraphitiEpisodicNode> episodes = getEpisodesOrEmpty(groupId);
        int count = 0;
        for (final GraphitiEpisodicNode ep : episodes) {
            if (!matchesCaseId(ep.sourceDescription(), request.caseId())) continue;
            try {
                client.deleteEpisode(ep.uuid());
                count++;
            } catch (final WebApplicationException e) {
                if (e.getResponse() != null && e.getResponse().getStatus() == 404) {
                    count++; // concurrently deleted between GET and DELETE — erasure satisfied
                } else {
                    throw GraphitiStoreException.from(e);
                }
            }
        }
        return count;
    }

    /** Maximum episodes fetched for count; actual deletion is always complete. */
    private static final int MAX_EPISODES_FOR_COUNT = 10_000;

    /**
     * Erases all data for an entity across all configured domains.
     *
     * <p>Requires {@code casehub.memory.graphiti.known-domains} to be set. Without it,
     * throws {@link MemoryCapabilityException} — Graphiti REST has no group enumeration
     * endpoint, making cross-domain entity wipes impossible without a declared domain list.
     *
     * <p>Each domain group is deleted via cascading {@code DELETE /group/{groupId}}.
     * 404 responses (entity never had data in that domain) are treated as no-ops returning 0.
     *
     * <p><b>Mid-loop failure:</b> if {@link #eraseGroup} throws a {@link GraphitiStoreException}
     * for domain N, the loop aborts — domains 0..N-1 are erased, N..end are not.
     * Retry is safe: already-erased groups return 0 via 404 handling and the loop
     * continues from where it failed.
     *
     * @return sum of episode counts across all deleted domain groups
     */
    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "eraseEntity"})
    @Override
    public int eraseEntity(final String entityId, final String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        final List<String> domains = config.knownDomains().orElse(List.of());
        if (domains.isEmpty()) {
            throw new MemoryCapabilityException(MemoryCapability.ERASE_ENTITY, getClass());
        }
        int total = 0;
        for (final String domain : domains) {
            total += eraseGroup(compoundGroupId(tenantId, entityId, domain));
        }
        return total;
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "eraseEntityAcrossTenants"})
    @Override
    public int eraseEntityAcrossTenants(final String entityId, final Set<String> tenantIds) {
        MemoryPermissions.assertCrossTenantAdmin(principal);
        final List<String> domains = config.knownDomains().orElse(List.of());
        if (domains.isEmpty())
            throw new MemoryCapabilityException(MemoryCapability.CROSS_TENANT_ERASE, getClass());
        // Sequential: simplicity + retry-is-safe. eraseGroup handles 404 as no-op.
        int total = 0;
        for (final String tenantId : tenantIds)
            for (final String domain : domains)
                total += eraseGroup(compoundGroupId(tenantId, entityId, domain));
        return total;
    }

    /**
     * Not supported — Graphiti's DELETE /episode/{uuid} removes the source EpisodicNode only;
     * LLM-extracted EntityNode and EntityEdge records derived from that episode persist in the
     * graph. GDPR Art.17 complete erasure cannot be guaranteed. Use {@link #eraseEntity} for
     * full removal of all data for an entityId.
     *
     * @throws MemoryCapabilityException always — re-declare ERASE_BY_ID in capabilities() once
     *     cascade support is available upstream (getzep/graphiti, platform#74)
     */
    @Override
    public void eraseById(final String memoryId, final String entityId, final String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        requireCapability(MemoryCapability.ERASE_BY_ID);
    }

    // ── mapping helpers ───────────────────────────────────────────────────────

    private static Memory factToMemory(
            final FactResult f,
            final String entityId,
            final MemoryDomain domain,
            final String tenantId) {
        final var attrs = new HashMap<String, String>();
        if (f.validAt() != null)   attrs.put(MemoryAttributeKeys.VALID_FROM,  f.validAt().toString());
        if (f.invalidAt() != null) attrs.put(MemoryAttributeKeys.VALID_UNTIL, f.invalidAt().toString());
        return new Memory(
            f.uuid(),
            entityId,
            domain,
            tenantId,
            null,
            f.fact() != null ? f.fact() : "",
            Map.copyOf(attrs),
            f.createdAt() != null ? f.createdAt() : Instant.EPOCH
        );
    }

    private static Memory episodeToMemory(
            final GraphitiEpisodicNode ep,
            final MemoryDomain domain,
            final String tenantId) {
        final String entityId = extractEntityId(ep.groupId(), tenantId);
        final var attrs = new HashMap<String, String>();
        // EpisodicNode.valid_at ≈ store timestamp (not LLM-extracted temporal).
        // Still surfaced as VALID_FROM so callers can see when the episode was recorded.
        if (ep.validAt() != null) attrs.put(MemoryAttributeKeys.VALID_FROM, ep.validAt().toString());
        return new Memory(
            ep.uuid(),
            entityId,
            domain,
            tenantId,
            null,
            ep.content() != null ? ep.content() : "",
            Map.copyOf(attrs),
            ep.createdAt() != null ? ep.createdAt() : Instant.EPOCH
        );
    }

    // ── static helpers ────────────────────────────────────────────────────────

    static String compoundGroupId(final String tenantId, final String entityId, final String domain) {
        return tenantId + SEP + entityId + SEP + domain;
    }

    static String extractEntityId(final String groupId, final String tenantId) {
        if (groupId == null) return "";
        final String prefix = tenantId + SEP;
        if (!groupId.startsWith(prefix)) return groupId;
        final String afterTenant = groupId.substring(prefix.length()); // "{entityId}::{domain}"
        final int sepIdx = afterTenant.indexOf(SEP);
        return sepIdx < 0 ? afterTenant : afterTenant.substring(0, sepIdx);
    }

    private static String sourceDescription(final MemoryInput input) {
        final String base = "domain=" + input.domain().name();
        return input.caseId() != null ? base + ";caseId=" + input.caseId() : base;
    }

    private static boolean matchesCaseId(final String sourceDescription, final String caseId) {
        if (sourceDescription == null) return false;
        for (final String part : sourceDescription.split(";")) {
            if (part.equals("caseId=" + caseId)) return true;
        }
        return false;
    }
}
