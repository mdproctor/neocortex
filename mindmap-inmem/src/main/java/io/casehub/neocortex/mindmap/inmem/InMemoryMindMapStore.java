package io.casehub.neocortex.mindmap.inmem;

import io.casehub.neocortex.mindmap.*;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(2)
@ApplicationScoped
public class InMemoryMindMapStore implements MindMapStore {

    private final Map<String, StoredNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, MindMapSubgraph> subgraphs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> aliasesByTenant = new ConcurrentHashMap<>();
    private final Map<String, String> canonicalEdgeTypes = new ConcurrentHashMap<>();
    private final Map<String, EdgeTypeDefinition> edgeTypeDefinitions = new ConcurrentHashMap<>();
    private final Map<String, StoredEdge> edges = new ConcurrentHashMap<>();

    public void clearAll() {
        nodes.clear();
        subgraphs.clear();
        aliasesByTenant.clear();
        canonicalEdgeTypes.clear();
        edgeTypeDefinitions.clear();
        edges.clear();
    }

    @Override
    public void registerVocabulary(MindMapVocabulary vocabulary) {
        for (EdgeTypeDefinition def : vocabulary.edgeTypes()) {
            String canonical = def.canonical();
            if (canonicalEdgeTypes.containsKey(canonical)
                && !edgeTypeDefinitions.containsKey(canonical)) {
                throw new VocabularyConflictException(
                    "'" + canonical + "' is already registered as an alias of another canonical type");
            }

            for (String alias : def.aliases()) {
                String existingCanonical = canonicalEdgeTypes.get(alias);
                if (existingCanonical != null && !existingCanonical.equals(canonical)) {
                    throw new VocabularyConflictException(
                        "'" + alias + "' is already mapped to canonical '" + existingCanonical + "'");
                }
                if (edgeTypeDefinitions.containsKey(alias) && !alias.equals(canonical)) {
                    throw new VocabularyConflictException(
                        "'" + alias + "' is already a canonical type and cannot be an alias of '" + canonical + "'");
                }
            }

            edgeTypeDefinitions.put(canonical, def);
            canonicalEdgeTypes.put(canonical, canonical);
            for (String alias : def.aliases()) {
                canonicalEdgeTypes.put(alias, canonical);
            }
        }
    }

    @Override
    public String addNode(NodeInput input, String tenantId) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        double confidence = input.confidence() != null
            ? input.confidence()
            : input.confidenceOrigin().initialConfidence();

        StoredNode node = new StoredNode(id, input.name(), input.subgraphId(),
            input.confidenceOrigin(), confidence, input.provenance(),
            now, now, now,
            input.validFrom(), input.validUntil(),
            new HashSet<>(input.traits()), new HashSet<>(input.refs()),
            input.pleasure(), input.arousal(), input.dominance(),
            new HashMap<>(input.properties()), tenantId,
            null, null, null, null);
        nodes.put(id, node);
        return id;
    }

    @Override
    public MindMapNode getNode(String nodeId, String tenantId) {
        StoredNode node = nodes.get(nodeId);
        if (node == null || !node.tenantId.equals(tenantId)) return null;
        return node;
    }

    @Override
    public void updateNode(String nodeId, NodeUpdate update, String tenantId) {
        StoredNode node = nodes.get(nodeId);
        if (node == null || !node.tenantId.equals(tenantId))
            throw new IllegalArgumentException("Node not found: " + nodeId);

        if (update.name() != null) node.name = update.name();
        if (update.confidenceOrigin() != null) node.confidenceOrigin = update.confidenceOrigin();
        if (update.confirmedAt() != null) {
            node.confirmedAt = update.confirmedAt();
            if (update.confidence() != null) {
                node.confidence = update.confidence();
            } else {
                node.confidence = 1.0;
            }
        } else if (update.confidence() != null) {
            node.confidence = update.confidence();
        }
        if (update.validFrom() != null) node.validFrom = update.validFrom();
        if (update.validUntil() != null) node.validUntil = update.validUntil();
        if (update.pleasure() != null) node.pleasure = update.pleasure();
        if (update.arousal() != null) node.arousal = update.arousal();
        if (update.dominance() != null) node.dominance = update.dominance();

        node.traits.addAll(update.traitsToAdd());
        node.traits.removeAll(update.traitsToRemove());
        node.refs.addAll(update.refsToAdd());
        node.refs.removeAll(update.refsToRemove());
        update.propertiesToSet().forEach((k, v) -> node.properties.put(k, v));
        update.propertiesToRemove().forEach(k -> node.properties.remove(k));

        node.updatedAt = Instant.now();
    }

    @Override
    public String createSubgraph(SubgraphInput input, String tenantId) {
        String id = UUID.randomUUID().toString();
        subgraphs.put(id, new MindMapSubgraph(id, input.name(), input.type(),
            input.rootNodeId(), tenantId, Instant.now()));
        return id;
    }

    @Override
    public MindMapSubgraph getSubgraph(String subgraphId, String tenantId) {
        MindMapSubgraph sg = subgraphs.get(subgraphId);
        if (sg == null || !sg.tenantId().equals(tenantId)) return null;
        return sg;
    }

    @Override
    public void updateSubgraph(String subgraphId, String rootNodeId, String tenantId) {
        MindMapSubgraph sg = subgraphs.get(subgraphId);
        if (sg == null || !sg.tenantId().equals(tenantId))
            throw new IllegalArgumentException("Subgraph not found: " + subgraphId);
        subgraphs.put(subgraphId, new MindMapSubgraph(sg.id(), sg.name(), sg.type(),
            rootNodeId, sg.tenantId(), sg.createdAt()));
    }

    @Override
    public List<MindMapNode> nodesIn(String subgraphId, String tenantId) {
        return nodes.values().stream()
            .filter(n -> n.tenantId.equals(tenantId))
            .filter(n -> n.subgraphId.equals(subgraphId))
            .filter(n -> !n.isSuperseded())
            .map(n -> (MindMapNode) n)
            .toList();
    }

    @Override
    public void addAlias(String nodeId, String alias, String tenantId) {
        StoredNode node = nodes.get(nodeId);
        if (node == null || !node.tenantId.equals(tenantId))
            throw new IllegalArgumentException("Node not found: " + nodeId);
        aliasesByTenant.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
            .put(alias.toLowerCase(), nodeId);
    }

    @Override
    public void removeAlias(String nodeId, String alias, String tenantId) {
        Map<String, String> aliases = aliasesByTenant.get(tenantId);
        if (aliases != null) {
            aliases.remove(alias.toLowerCase());
        }
    }

    @Override
    public MindMapNode resolveNode(String nameOrAlias, String subgraphId, String tenantId) {
        Map<String, String> aliases = aliasesByTenant.get(tenantId);
        if (aliases != null) {
            String nodeId = aliases.get(nameOrAlias.toLowerCase());
            if (nodeId != null) {
                StoredNode node = nodes.get(nodeId);
                if (node != null && node.tenantId.equals(tenantId)) {
                    if (subgraphId == null || node.subgraphId.equals(subgraphId)) {
                        return node;
                    }
                }
            }
        }
        return nodes.values().stream()
            .filter(n -> n.tenantId.equals(tenantId))
            .filter(n -> n.name.equalsIgnoreCase(nameOrAlias))
            .filter(n -> subgraphId == null || n.subgraphId.equals(subgraphId))
            .findFirst()
            .map(n -> (MindMapNode) n)
            .orElse(null);
    }

    // --- Edge operations (Batch 3) ---

    @Override
    public String addEdge(EdgeInput input, String tenantId) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String resolvedType = canonicalEdgeTypes.getOrDefault(input.edgeType(), input.edgeType());
        ValidationTier tier = edgeTypeDefinitions.containsKey(resolvedType)
            ? ValidationTier.REGISTERED : ValidationTier.UNVALIDATED;
        double confidence = input.confidence() != null
            ? input.confidence()
            : input.confidenceOrigin().initialConfidence();

        StoredEdge edge = new StoredEdge(id, input.sourceNodeId(), input.targetNodeId(),
            resolvedType, tier, input.confidenceOrigin(), confidence,
            input.provenance(), now, now,
            input.validFrom(), input.validUntil(),
            input.pleasure(), input.arousal(), input.dominance(),
            new HashMap<>(input.properties()), tenantId);
        edges.put(id, edge);
        return id;
    }

    @Override
    public MindMapEdge getEdge(String edgeId, String tenantId) {
        StoredEdge edge = edges.get(edgeId);
        if (edge == null || !edge.tenantId.equals(tenantId)) return null;
        return edge;
    }

    @Override
    public void removeEdge(String edgeId, String tenantId) {
        StoredEdge edge = edges.get(edgeId);
        if (edge != null && edge.tenantId.equals(tenantId)) {
            edges.remove(edgeId);
        }
    }

    // --- Traversal (Batch 3) ---

    @Override
    public List<MindMapEdge> neighbors(String nodeId, String tenantId) {
        return edges.values().stream()
            .filter(e -> e.tenantId.equals(tenantId))
            .filter(e -> e.sourceNodeId.equals(nodeId) || e.targetNodeId.equals(nodeId))
            .map(e -> (MindMapEdge) e)
            .toList();
    }

    @Override
    public List<MindMapEdge> neighbors(String nodeId, String edgeType, String tenantId) {
        String resolved = canonicalEdgeTypes.getOrDefault(edgeType, edgeType);
        return edges.values().stream()
            .filter(e -> e.tenantId.equals(tenantId))
            .filter(e -> e.sourceNodeId.equals(nodeId) || e.targetNodeId.equals(nodeId))
            .filter(e -> e.edgeType.equals(resolved))
            .map(e -> (MindMapEdge) e)
            .toList();
    }

    @Override
    public List<MindMapEdge> bridgeEdges(String subgraphId, String tenantId) {
        Set<String> nodesInSg = new HashSet<>();
        nodes.values().stream()
            .filter(n -> n.tenantId.equals(tenantId) && n.subgraphId.equals(subgraphId))
            .forEach(n -> nodesInSg.add(n.id));

        return edges.values().stream()
            .filter(e -> e.tenantId.equals(tenantId))
            .filter(e -> {
                boolean srcIn = nodesInSg.contains(e.sourceNodeId);
                boolean tgtIn = nodesInSg.contains(e.targetNodeId);
                return (srcIn && !tgtIn) || (!srcIn && tgtIn);
            })
            .map(e -> (MindMapEdge) e)
            .toList();
    }

    @Override
    public List<MindMapNode> search(MindMapQuery query) {
        Set<String> connectedByEdgeType = null;
        if (query.edgeType() != null) {
            String resolved = canonicalEdgeTypes.getOrDefault(query.edgeType(), query.edgeType());
            connectedByEdgeType = new HashSet<>();
            for (StoredEdge e : edges.values()) {
                if (e.tenantId.equals(query.tenantId()) && e.edgeType.equals(resolved)) {
                    connectedByEdgeType.add(e.sourceNodeId);
                    connectedByEdgeType.add(e.targetNodeId);
                }
            }
        }
        Set<String> edgeTypeNodes = connectedByEdgeType;

        return nodes.values().stream()
            .filter(n -> n.tenantId.equals(query.tenantId()))
            .filter(n -> !n.isSuperseded() || query.includeSuperseded())
            .filter(n -> query.subgraphId() == null || n.subgraphId.equals(query.subgraphId()))
            .filter(n -> query.text() == null || matchesText(n, query.text()))
            .filter(n -> query.traits() == null || n.traits.containsAll(query.traits()))
            .filter(n -> query.minConfidence() == null || n.confidence >= query.minConfidence())
            .filter(n -> query.confidenceOrigin() == null || n.confidenceOrigin == query.confidenceOrigin())
            .filter(n -> edgeTypeNodes == null || edgeTypeNodes.contains(n.id))
            .limit(query.limit())
            .map(n -> (MindMapNode) n)
            .toList();
    }

    private boolean matchesText(StoredNode node, String text) {
        String lower = text.toLowerCase();
        if (node.name.toLowerCase().contains(lower)) return true;
        return node.properties.values().stream()
            .anyMatch(v -> v.toLowerCase().contains(lower));
    }

    // --- Merge (Batch 4) ---

    @Override
    public MergeResult mergeNodes(String keepNodeId, String removeNodeId, String tenantId) {
        StoredNode keep = nodes.get(keepNodeId);
        StoredNode remove = nodes.get(removeNodeId);
        if (keep == null || !keep.tenantId.equals(tenantId))
            throw new IllegalArgumentException("Keep node not found: " + keepNodeId);
        if (remove == null || !remove.tenantId.equals(tenantId))
            throw new IllegalArgumentException("Remove node not found: " + removeNodeId);

        int aliasesMerged = 0;
        Map<String, String> aliases = aliasesByTenant.get(tenantId);
        if (aliases != null) {
            List<String> toRepoint = new ArrayList<>();
            for (Map.Entry<String, String> entry : aliases.entrySet()) {
                if (entry.getValue().equals(removeNodeId)) {
                    toRepoint.add(entry.getKey());
                }
            }
            for (String alias : toRepoint) {
                aliases.put(alias, keepNodeId);
                aliasesMerged++;
            }
        }

        keep.refs.addAll(remove.refs);

        Set<String> mergedTraits = new HashSet<>(remove.traits);
        mergedTraits.removeAll(keep.traits);
        keep.traits.addAll(remove.traits);

        List<MergeConflict> propertyConflicts = new ArrayList<>();
        for (Map.Entry<String, String> entry : remove.properties.entrySet()) {
            String key = entry.getKey();
            if (keep.properties.containsKey(key)) {
                String keptVal = keep.properties.get(key);
                String discardedVal = entry.getValue();
                if (!keptVal.equals(discardedVal)) {
                    if (remove.updatedAt.isAfter(keep.updatedAt)) {
                        keep.properties.put(key, discardedVal);
                        propertyConflicts.add(new MergeConflict(key, discardedVal, keptVal));
                    } else {
                        propertyConflicts.add(new MergeConflict(key, keptVal, discardedVal));
                    }
                }
            } else {
                keep.properties.put(key, entry.getValue());
            }
        }

        int edgesRepointed = 0;
        int duplicateEdgesRemoved = 0;

        for (StoredEdge e : new ArrayList<>(edges.values())) {
            if (!e.tenantId.equals(tenantId)) continue;
            if (!e.sourceNodeId.equals(removeNodeId) && !e.targetNodeId.equals(removeNodeId)) continue;

            String newSrc = e.sourceNodeId.equals(removeNodeId) ? keepNodeId : e.sourceNodeId;
            String newTgt = e.targetNodeId.equals(removeNodeId) ? keepNodeId : e.targetNodeId;
            e.sourceNodeId = newSrc;
            e.targetNodeId = newTgt;
            edgesRepointed++;
        }

        Map<String, StoredEdge> edgeSignatures = new HashMap<>();
        for (StoredEdge e : new ArrayList<>(edges.values())) {
            if (!e.tenantId.equals(tenantId)) continue;
            if (!e.sourceNodeId.equals(keepNodeId) && !e.targetNodeId.equals(keepNodeId)) continue;

            String sig = e.sourceNodeId + "|" + e.targetNodeId + "|" + e.edgeType;
            StoredEdge existing = edgeSignatures.get(sig);
            if (existing != null) {
                if (e.updatedAt.isAfter(existing.updatedAt) || e.updatedAt.equals(existing.updatedAt)) {
                    edges.remove(existing.id);
                    edgeSignatures.put(sig, e);
                } else {
                    edges.remove(e.id);
                }
                duplicateEdgesRemoved++;
            } else {
                edgeSignatures.put(sig, e);
            }
        }

        nodes.remove(removeNodeId);
        keep.updatedAt = Instant.now();

        return new MergeResult(keepNodeId, edgesRepointed, aliasesMerged,
            duplicateEdgesRemoved, mergedTraits, propertyConflicts);
    }

    // --- Supersession (Batch 4) ---

    @Override
    public void supersede(String targetId, String supersedingId, String reason, String tenantId) {
        StoredNode node = nodes.get(targetId);
        if (node == null || !node.tenantId.equals(tenantId))
            throw new IllegalArgumentException("Node not found: " + targetId);
        node.supersededAt = Instant.now();
        node.supersedingId = supersedingId;
        node.supersessionReason = reason;
        node.reinstatedAt = null;
    }

    @Override
    public void reinstate(String targetId, String tenantId) {
        StoredNode node = nodes.get(targetId);
        if (node == null || !node.tenantId.equals(tenantId))
            throw new IllegalArgumentException("Node not found: " + targetId);
        node.reinstatedAt = Instant.now();
    }

    @Override
    public SupersessionStatus getSupersessionStatus(String targetId, String tenantId) {
        StoredNode node = nodes.get(targetId);
        if (node == null || !node.tenantId.equals(tenantId))
            throw new IllegalArgumentException("Node not found: " + targetId);
        if (node.supersededAt == null) return SupersessionStatus.NOT_SUPERSEDED;
        return new SupersessionStatus(targetId, node.reinstatedAt == null,
            node.supersededAt, node.supersedingId, node.supersessionReason,
            node.reinstatedAt);
    }

    // --- Erasure (Batch 4) ---

    @Override
    public int eraseNode(String nodeId, String tenantId) {
        StoredNode node = nodes.get(nodeId);
        if (node == null || !node.tenantId.equals(tenantId)) return 0;

        int count = 1;
        nodes.remove(nodeId);

        List<String> edgesToRemove = edges.values().stream()
            .filter(e -> e.tenantId.equals(tenantId))
            .filter(e -> e.sourceNodeId.equals(nodeId) || e.targetNodeId.equals(nodeId))
            .map(e -> e.id)
            .toList();
        edgesToRemove.forEach(edges::remove);
        count += edgesToRemove.size();

        Map<String, String> aliases = aliasesByTenant.get(tenantId);
        if (aliases != null) {
            List<String> aliasesToRemove = aliases.entrySet().stream()
                .filter(e -> e.getValue().equals(nodeId))
                .map(Map.Entry::getKey)
                .toList();
            aliasesToRemove.forEach(aliases::remove);
            count += aliasesToRemove.size();
        }

        return count;
    }

    @Override
    public int eraseSubgraph(String subgraphId, String tenantId) {
        MindMapSubgraph sg = subgraphs.get(subgraphId);
        if (sg == null || !sg.tenantId().equals(tenantId)) return 0;

        List<String> nodeIds = nodes.values().stream()
            .filter(n -> n.tenantId.equals(tenantId) && n.subgraphId.equals(subgraphId))
            .map(n -> n.id)
            .toList();

        int count = 0;
        for (String nodeId : nodeIds) {
            count += eraseNode(nodeId, tenantId);
        }
        subgraphs.remove(subgraphId);
        count++;
        return count;
    }

    @Override
    public int eraseEntity(String entityName, String tenantId) {
        List<String> nodeIds = nodes.values().stream()
            .filter(n -> n.tenantId.equals(tenantId))
            .filter(n -> n.name.equalsIgnoreCase(entityName) || hasAlias(n.id, entityName, tenantId))
            .map(n -> n.id)
            .toList();

        int count = 0;
        for (String nodeId : nodeIds) {
            count += eraseNode(nodeId, tenantId);
        }
        return count;
    }

    @Override
    public int eraseEntityAcrossTenants(String entityName, Set<String> tenantIds) {
        int count = 0;
        for (String tid : tenantIds) {
            count += eraseEntity(entityName, tid);
        }
        return count;
    }

    private boolean hasAlias(String nodeId, String name, String tenantId) {
        Map<String, String> aliases = aliasesByTenant.get(tenantId);
        if (aliases == null) return false;
        String mapped = aliases.get(name.toLowerCase());
        return nodeId.equals(mapped);
    }

    @Override
    public Set<MindMapCapability> capabilities() {
        return EnumSet.allOf(MindMapCapability.class);
    }

    // --- Internal storage types ---

    static class StoredNode implements MindMapNode {
        final String id;
        String name;
        final String subgraphId;
        ConfidenceOrigin confidenceOrigin;
        double confidence;
        final String provenance;
        final Instant createdAt;
        Instant updatedAt;
        Instant confirmedAt;
        Instant validFrom;
        Instant validUntil;
        final Set<String> traits;
        final Set<NodeRef> refs;
        Double pleasure;
        Double arousal;
        Double dominance;
        final Map<String, String> properties;
        final String tenantId;
        Instant supersededAt;
        String supersedingId;
        String supersessionReason;
        Instant reinstatedAt;

        StoredNode(String id, String name, String subgraphId,
                   ConfidenceOrigin confidenceOrigin, double confidence, String provenance,
                   Instant createdAt, Instant updatedAt, Instant confirmedAt,
                   Instant validFrom, Instant validUntil,
                   Set<String> traits, Set<NodeRef> refs,
                   Double pleasure, Double arousal, Double dominance,
                   Map<String, String> properties, String tenantId,
                   Instant supersededAt, String supersedingId,
                   String supersessionReason, Instant reinstatedAt) {
            this.id = id;
            this.name = name;
            this.subgraphId = subgraphId;
            this.confidenceOrigin = confidenceOrigin;
            this.confidence = confidence;
            this.provenance = provenance;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.confirmedAt = confirmedAt;
            this.validFrom = validFrom;
            this.validUntil = validUntil;
            this.traits = traits;
            this.refs = refs;
            this.pleasure = pleasure;
            this.arousal = arousal;
            this.dominance = dominance;
            this.properties = properties;
            this.tenantId = tenantId;
            this.supersededAt = supersededAt;
            this.supersedingId = supersedingId;
            this.supersessionReason = supersessionReason;
            this.reinstatedAt = reinstatedAt;
        }

        boolean isSuperseded() {
            return supersededAt != null && reinstatedAt == null;
        }

        @Override public String id() { return id; }
        @Override public String name() { return name; }
        @Override public String subgraphId() { return subgraphId; }
        @Override public ConfidenceOrigin confidenceOrigin() { return confidenceOrigin; }
        @Override public double confidence() { return confidence; }
        @Override public String provenance() { return provenance; }
        @Override public Instant createdAt() { return createdAt; }
        @Override public Instant updatedAt() { return updatedAt; }
        @Override public Instant confirmedAt() { return confirmedAt; }
        @Override public Instant validFrom() { return validFrom; }
        @Override public Instant validUntil() { return validUntil; }
        @Override public Set<String> traits() { return Set.copyOf(traits); }
        @Override public Set<NodeRef> refs() { return Set.copyOf(refs); }
        @Override public Double pleasure() { return pleasure; }
        @Override public Double arousal() { return arousal; }
        @Override public Double dominance() { return dominance; }

        @Override
        public Optional<String> property(String key) {
            return Optional.ofNullable(properties.get(key));
        }

        @Override
        public Map<String, String> properties() {
            return Map.copyOf(properties);
        }
    }

    static class StoredEdge implements MindMapEdge {
        final String id;
        String sourceNodeId;
        String targetNodeId;
        final String edgeType;
        final ValidationTier tier;
        final ConfidenceOrigin confidenceOrigin;
        final double confidence;
        final String provenance;
        final Instant createdAt;
        Instant updatedAt;
        final Instant validFrom;
        final Instant validUntil;
        final Double pleasure;
        final Double arousal;
        final Double dominance;
        final Map<String, String> properties;
        final String tenantId;

        StoredEdge(String id, String sourceNodeId, String targetNodeId,
                   String edgeType, ValidationTier tier,
                   ConfidenceOrigin confidenceOrigin, double confidence,
                   String provenance, Instant createdAt, Instant updatedAt,
                   Instant validFrom, Instant validUntil,
                   Double pleasure, Double arousal, Double dominance,
                   Map<String, String> properties, String tenantId) {
            this.id = id;
            this.sourceNodeId = sourceNodeId;
            this.targetNodeId = targetNodeId;
            this.edgeType = edgeType;
            this.tier = tier;
            this.confidenceOrigin = confidenceOrigin;
            this.confidence = confidence;
            this.provenance = provenance;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.validFrom = validFrom;
            this.validUntil = validUntil;
            this.pleasure = pleasure;
            this.arousal = arousal;
            this.dominance = dominance;
            this.properties = properties;
            this.tenantId = tenantId;
        }

        @Override public String id() { return id; }
        @Override public String sourceNodeId() { return sourceNodeId; }
        @Override public String targetNodeId() { return targetNodeId; }
        @Override public String edgeType() { return edgeType; }
        @Override public ValidationTier tier() { return tier; }
        @Override public ConfidenceOrigin confidenceOrigin() { return confidenceOrigin; }
        @Override public double confidence() { return confidence; }
        @Override public String provenance() { return provenance; }
        @Override public Instant createdAt() { return createdAt; }
        @Override public Instant updatedAt() { return updatedAt; }
        @Override public Instant validFrom() { return validFrom; }
        @Override public Instant validUntil() { return validUntil; }
        @Override public Double pleasure() { return pleasure; }
        @Override public Double arousal() { return arousal; }
        @Override public Double dominance() { return dominance; }

        @Override
        public Optional<String> property(String key) {
            return Optional.ofNullable(properties.get(key));
        }

        @Override
        public Map<String, String> properties() {
            return Map.copyOf(properties);
        }
    }
}
