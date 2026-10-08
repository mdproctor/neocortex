package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.cognitive.Confidence;

import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryScanRequest;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.casehub.neocortex.memory.experience.SubThoughtAttributeKeys;
import io.casehub.neocortex.mindmap.AttentionSignal;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.EdgeInput;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SignalCategory;
import io.casehub.neocortex.mindmap.NodeUpdate;
import io.casehub.neocortex.mindmap.NodeRef;
import io.casehub.neocortex.mindmap.SubThoughtRef;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.intelligence.SubgraphUtils;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

@ApplicationScoped
@Priority(16)
public class SubThoughtConsolidationPhase implements ConsolidationPhase {

    private static final Logger LOG = Logger.getLogger(SubThoughtConsolidationPhase.class.getName());
    private static final int DEFAULT_THRESHOLD = 3;
    private static final int DEFAULT_CONCERN_THRESHOLD = 3;
    private static final int MAX_PER_PASS = 50;
    private static final String SENTINEL_NAME = "_sub-thought-consolidation-cursor";
    private static final String CURSOR_PROPERTY = "graduation-cursor";
    private static final String ACCUM_PREFIX = "accum.";
    private static final Set<String> NEGATIVE_TYPES = Set.of("concern", "evaluative");
    private static final Set<String> POSITIVE_TYPES = Set.of("affect-observation");

    private final CaseMemoryStore memoryStore;
    private final MindMapStore mindMapStore;
    private final int graduationThreshold;
    private final int concernThreshold;
    private final List<AttentionSignal> pendingSignals = new ArrayList<>();
    private final List<String> unresolvedIntentions = new ArrayList<>();
    private final Map<String, Boolean> lastAffectPolarity = new HashMap<>();
    private final Set<String> previousAccumKeys = new HashSet<>();

    @Inject
    public SubThoughtConsolidationPhase(Instance<CaseMemoryStore> memoryStore,
                                        Instance<MindMapStore> mindMapStore) {
        this.memoryStore = memoryStore.isResolvable() ? memoryStore.get() : null;
        this.mindMapStore = mindMapStore.isResolvable() ? mindMapStore.get() : null;
        this.graduationThreshold = DEFAULT_THRESHOLD;
        this.concernThreshold = DEFAULT_CONCERN_THRESHOLD;
    }

    SubThoughtConsolidationPhase(CaseMemoryStore memoryStore,
                                 MindMapStore mindMapStore,
                                 int graduationThreshold) {
        this.memoryStore = memoryStore;
        this.mindMapStore = mindMapStore;
        this.graduationThreshold = graduationThreshold;
        this.concernThreshold = DEFAULT_CONCERN_THRESHOLD;
    }

    @Override
    public String name() { return "sub-thought-graduation"; }

    @Override
    public void beginTick() {
        pendingSignals.clear();
        unresolvedIntentions.clear();
    }

    @Override
    public List<AttentionSignal> signals() { return List.copyOf(pendingSignals); }

    public List<String> unresolvedIntentions() { return List.copyOf(unresolvedIntentions); }

    @Override
    public void run(String tenantId, List<String> subgraphPriority) {
        if (memoryStore == null || mindMapStore == null) return;

        String cursor = loadCursor(tenantId);

        List<Memory> memories = memoryStore.scan(
            new MemoryScanRequest(tenantId, ExperienceEvents.DOMAIN.name(),
                null, null, MAX_PER_PASS, cursor));

        if (memories.isEmpty()) return;

        Map<EntityTypePair, List<SubThoughtSource>> accumulation = loadAccumulation(tenantId);
        String lastProcessedId = cursor;

        for (Memory memory : memories) {
            String countStr = memory.attributes().get(SubThoughtAttributeKeys.COUNT);
            if (countStr == null) { lastProcessedId = memory.memoryId(); continue; }
            if ("biographical-import".equals(memory.attributes().get("provenance"))) {
                lastProcessedId = memory.memoryId();
                continue;
            }

            int count;
            try { count = Integer.parseInt(countStr); } catch (NumberFormatException e) {
                lastProcessedId = memory.memoryId();
                continue;
            }
            for (int i = 0; i < count; i++) {
                if ("true".equals(memory.attributes().get(SubThoughtAttributeKeys.graduated(i)))) continue;

                String entity = memory.attributes().get(SubThoughtAttributeKeys.entity(i));
                String type = memory.attributes().get(SubThoughtAttributeKeys.type(i));
                if (entity == null || type == null) continue;

                var key = new EntityTypePair(entity, type);
                accumulation.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new SubThoughtSource(memory.memoryId(), i));
            }
            lastProcessedId = memory.memoryId();
        }

        var entityTypes = buildEntityTypeIndex(accumulation);
        detectConcernEscalation(accumulation, tenantId);
        detectContradictions(entityTypes, tenantId);
        detectUnresolvedIntentions(accumulation);
        detectAffectPolarityShifts(entityTypes, tenantId);

        var graduated = new ArrayList<EntityTypePair>();
        var graduatedNodeIds = new HashMap<EntityTypePair, String>();
        for (var entry : accumulation.entrySet()) {
            if (entry.getValue().size() >= graduationThreshold) {
                String nodeId = graduateSubThought(entry.getKey(), entry.getValue(), tenantId);
                graduated.add(entry.getKey());
                if (nodeId != null) graduatedNodeIds.put(entry.getKey(), nodeId);
            }
        }
        graduated.forEach(accumulation::remove);

        linkCausalChains(graduatedNodeIds, tenantId);

        saveCursor(tenantId, lastProcessedId);
        saveAccumulation(tenantId, accumulation);
    }

    private void detectConcernEscalation(Map<EntityTypePair, List<SubThoughtSource>> accumulation,
                                          String tenantId) {
        for (var entry : accumulation.entrySet()) {
            if (!"concern".equals(entry.getKey().type())) continue;
            if (entry.getValue().size() >= concernThreshold) {
                pendingSignals.add(new AttentionSignal(
                    null, tenantId, SignalCategory.URGENCY_SPIKE,
                    null, entry.getKey().entity(),
                    Math.min(1.0, entry.getValue().size() * 0.2),
                    "Concern about " + entry.getKey().entity() + " escalating (" + entry.getValue().size() + " instances)"));
            }
        }
    }

    private Map<String, Set<String>> buildEntityTypeIndex(Map<EntityTypePair, List<SubThoughtSource>> accumulation) {
        var index = new HashMap<String, Set<String>>();
        for (var key : accumulation.keySet()) {
            index.computeIfAbsent(key.entity(), k -> new HashSet<>()).add(key.type());
        }
        return index;
    }

    private void detectContradictions(Map<String, Set<String>> entityTypes, String tenantId) {
        for (var entry : entityTypes.entrySet()) {
            boolean hasPositive = entry.getValue().stream().anyMatch(POSITIVE_TYPES::contains);
            boolean hasNegative = entry.getValue().stream().anyMatch(NEGATIVE_TYPES::contains);
            if (hasPositive && hasNegative) {
                pendingSignals.add(new AttentionSignal(
                    null, tenantId, SignalCategory.MERGE_CANDIDATE,
                    null, entry.getKey(), 0.6,
                    "Contradictory sub-thoughts about " + entry.getKey() + " — positive and negative signals coexist"));
            }
        }
    }

    private void detectUnresolvedIntentions(Map<EntityTypePair, List<SubThoughtSource>> accumulation) {
        for (var entry : accumulation.entrySet()) {
            if ("intention".equals(entry.getKey().type()) && entry.getValue().size() >= 2) {
                unresolvedIntentions.add(entry.getKey().entity());
            }
        }
    }

    private void detectAffectPolarityShifts(Map<String, Set<String>> entityTypes, String tenantId) {
        for (var entry : entityTypes.entrySet()) {
            boolean hasPositive = entry.getValue().stream().anyMatch(POSITIVE_TYPES::contains);
            boolean hasNegative = entry.getValue().stream().anyMatch(NEGATIVE_TYPES::contains);
            if (!hasPositive && !hasNegative) continue;

            boolean currentPolarity = hasPositive && !hasNegative;
            String polarityKey = tenantId + ":" + entry.getKey();
            Boolean previousPolarity = lastAffectPolarity.get(polarityKey);

            if (previousPolarity != null && previousPolarity != currentPolarity) {
                String direction = currentPolarity ? "negative → positive" : "positive → negative";
                pendingSignals.add(new AttentionSignal(
                    null, tenantId, SignalCategory.AFFECT_CHANGE,
                    null, entry.getKey(), 0.7,
                    "Affect polarity shift for " + entry.getKey() + ": " + direction));
            }
            lastAffectPolarity.put(polarityKey, currentPolarity);
        }
    }

    private String graduateSubThought(EntityTypePair pair, List<SubThoughtSource> sources,
                                       String tenantId) {
        String subgraphId = SubgraphUtils.ensureSubgraph(
            mindMapStore, SubgraphTypes.COGNITIVE, tenantId);

        Set<NodeRef> refs = new HashSet<>();
        for (var source : sources) {
            refs.add(SubThoughtRef.of(source.memoryId(), source.subThoughtIndex()));
        }

        String nodeId = mindMapStore.addNode(
            NodeInput.of(pair.entity() + " — " + pair.type(), subgraphId)
                .withConfidence(Confidence.inferred(0.7, Instant.now()))
                .withProvenance("sub-thought-graduation")
                .withTraits(Set.of("graduated-sub-thought"))
                .withRefs(refs)
                .withProperties(Map.of(
                    "cognitiveKind", pair.type(),
                    "source-count", String.valueOf(sources.size()))),
            tenantId);

        for (var source : sources) {
            try {
                memoryStore.enrichAttributes(source.memoryId(),
                    Map.of(SubThoughtAttributeKeys.graduated(source.subThoughtIndex()), "true"),
                    tenantId);
            } catch (Exception e) {
                LOG.warning("Could not mark sub-thought graduated: " + source.memoryId()
                    + "#" + source.subThoughtIndex());
            }
        }
        return nodeId;
    }

    private void linkCausalChains(Map<EntityTypePair, String> graduatedNodeIds, String tenantId) {
        var causalEntries = graduatedNodeIds.entrySet().stream()
            .filter(e -> "causal-inference".equals(e.getKey().type()))
            .toList();
        if (causalEntries.size() < 2) return;

        for (int i = 0; i < causalEntries.size() - 1; i++) {
            mindMapStore.addEdge(
                EdgeInput.of(causalEntries.get(i).getValue(), causalEntries.get(i + 1).getValue(), "causal-link")
                    .withProvenance("sub-thought-graduation"),
                tenantId);
        }
    }

    private String loadCursor(String tenantId) {
        return findSentinelNode(tenantId)
            .flatMap(n -> n.property(CURSOR_PROPERTY))
            .orElse(null);
    }

    private void saveCursor(String tenantId, String memoryId) {
        if (memoryId == null) return;
        Optional<MindMapNode> sentinel = findSentinelNode(tenantId);
        if (sentinel.isPresent()) {
            mindMapStore.updateNode(sentinel.get().id(),
                NodeUpdate.empty().withPropertiesToSet(Map.of(CURSOR_PROPERTY, memoryId)),
                tenantId);
        } else {
            String sgId = SubgraphUtils.ensureSubgraph(
                mindMapStore, "Type System", SubgraphTypes.TYPE_SYSTEM, tenantId);
            mindMapStore.addNode(
                NodeInput.of(SENTINEL_NAME, sgId)
                    .withProperties(Map.of(CURSOR_PROPERTY, memoryId))
                    .withProvenance("sub-thought-graduation"),
                tenantId);
        }
    }

    private Map<EntityTypePair, List<SubThoughtSource>> loadAccumulation(String tenantId) {
        var result = new HashMap<EntityTypePair, List<SubThoughtSource>>();
        findSentinelNode(tenantId).ifPresent(sentinel ->
            sentinel.properties().forEach((key, value) -> {
                if (!key.startsWith(ACCUM_PREFIX)) return;
                String pairKey = key.substring(ACCUM_PREFIX.length());
                int sep = pairKey.indexOf(':');
                if (sep <= 0) return;
                var pair = new EntityTypePair(pairKey.substring(0, sep), pairKey.substring(sep + 1));
                int count;
                try { count = Integer.parseInt(value); } catch (NumberFormatException e) { return; }
                var sources = new ArrayList<SubThoughtSource>();
                for (int i = 0; i < count; i++) {
                    sources.add(new SubThoughtSource("persisted", i));
                }
                result.put(pair, sources);
            }));
        return result;
    }

    private void saveAccumulation(String tenantId,
                                   Map<EntityTypePair, List<SubThoughtSource>> accumulation) {
        var sentinel = findSentinelNode(tenantId);
        if (sentinel.isEmpty()) return;

        var currentKeys = new HashSet<String>();
        var props = new HashMap<String, String>();
        for (var entry : accumulation.entrySet()) {
            String key = ACCUM_PREFIX + entry.getKey().entity() + ":" + entry.getKey().type();
            props.put(key, String.valueOf(entry.getValue().size()));
            currentKeys.add(key);
        }

        var staleKeys = new HashSet<>(previousAccumKeys);
        staleKeys.removeAll(currentKeys);
        previousAccumKeys.clear();
        previousAccumKeys.addAll(currentKeys);

        var update = NodeUpdate.empty();
        if (!props.isEmpty()) update = update.withPropertiesToSet(props);
        if (!staleKeys.isEmpty()) update = update.withPropertiesToRemove(staleKeys);
        if (!props.isEmpty() || !staleKeys.isEmpty()) {
            mindMapStore.updateNode(sentinel.get().id(), update, tenantId);
        }
    }

    private Optional<MindMapNode> findSentinelNode(String tenantId) {
        return mindMapStore.search(
                MindMapQuery.of(tenantId, 100).withType(SubgraphTypes.TYPE_SYSTEM))
            .stream()
            .filter(n -> SENTINEL_NAME.equals(n.name()))
            .findFirst();
    }

    record EntityTypePair(String entity, String type) {}
    record SubThoughtSource(String memoryId, int subThoughtIndex) {}
}
