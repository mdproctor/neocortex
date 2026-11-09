package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.cognitive.RecurringThoughtConfig;
import io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryScanRequest;
import io.casehub.neocortex.memory.experience.ExperienceAttributeKeys;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.casehub.neocortex.mindmap.AttentionSignal;
import io.casehub.neocortex.mindmap.ConsolidationArtifact;
import io.casehub.neocortex.mindmap.ContinuumPosition;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.RecurringThoughtAssessment;
import io.casehub.neocortex.mindmap.SignalCategory;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.intelligence.RecurringThoughtScorer;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Consolidation phase that scores recurring thought patterns per agent and emits
 * attention signals when topics cross continuum thresholds.
 *
 * <p>Signal significance: FIXATION=0.4, OBSESSION=0.7, RUMINATION=1.0 — monotonically
 * increasing with clinical severity. Calibrated empirically.
 *
 * @see RecurringThoughtScorer
 */
@Priority(25)
public class RecurringThoughtPhase implements ConsolidationPhase {

    private final CaseMemoryStore memoryStore;
    private final MindMapStore mindMapStore;
    private final CognitiveDefaultsRegistry registry;
    private final List<AttentionSignal> pendingSignals = new ArrayList<>();
    private final List<ConsolidationArtifact> pendingArtifacts = new ArrayList<>();
    private final ConcurrentHashMap<String, List<RecurringThoughtAssessment>> cache = new ConcurrentHashMap<>();

    @jakarta.inject.Inject
    public RecurringThoughtPhase(Instance<CaseMemoryStore> memoryStoreInstance,
                                  Instance<MindMapStore> mindMapStoreInstance,
                                  Instance<CognitiveDefaultsRegistry> registryInstance) {
        this.memoryStore = memoryStoreInstance.isResolvable() ? memoryStoreInstance.get() : null;
        this.mindMapStore = mindMapStoreInstance.isResolvable() ? mindMapStoreInstance.get() : null;
        this.registry = registryInstance.isResolvable() ? registryInstance.get() : null;
    }

    RecurringThoughtPhase(CaseMemoryStore memoryStore, MindMapStore mindMapStore,
                           CognitiveDefaultsRegistry registry) {
        this.memoryStore = memoryStore;
        this.mindMapStore = mindMapStore;
        this.registry = registry;
    }

    @Override
    public String name() { return "recurring-thought"; }

    @Override
    public void beginTick() {
        pendingSignals.clear();
        pendingArtifacts.clear();
    }

    @Override
    public void run(String tenantId, List<String> subgraphPriority) {
        if (memoryStore == null || mindMapStore == null) return;

        var scorer = new RecurringThoughtScorer(mindMapStore, memoryStore);

        RecurringThoughtConfig defaultConfig = RecurringThoughtConfig.defaults();

        Map<String, List<MindMapNode>> byAgent = discoverAgents(mindMapStore, tenantId);

        for (var entry : byAgent.entrySet()) {
            String agentId = entry.getKey();
            RecurringThoughtConfig config = resolveConfig(registry, agentId, defaultConfig);

            Set<String> topicNodeIds = resolveTopics(memoryStore, mindMapStore, agentId, tenantId, config);

            List<RecurringThoughtAssessment> assessments = new ArrayList<>();
            for (String topicNodeId : topicNodeIds) {
                var assessment = scorer.score(topicNodeId, tenantId, config, null, null);
                if (assessment.position() != ContinuumPosition.HEALTHY) {
                    assessments.add(assessment);
                    emitSignal(agentId, tenantId, assessment);
                    emitArtifact(agentId, tenantId, assessment);
                }
            }

            cache.put(agentId + ":" + tenantId, List.copyOf(assessments));
        }
    }

    @Override
    public List<AttentionSignal> signals() {
        var result = List.copyOf(pendingSignals);
        pendingSignals.clear();
        return result;
    }

    @Override
    public List<ConsolidationArtifact> artifacts() {
        var result = List.copyOf(pendingArtifacts);
        pendingArtifacts.clear();
        return result;
    }

    public List<RecurringThoughtAssessment> getAssessments(String agentId, String tenantId) {
        return cache.getOrDefault(agentId + ":" + tenantId, List.of());
    }

    private Map<String, List<MindMapNode>> discoverAgents(MindMapStore store, String tenantId) {
        var cognitiveNodes = store.search(
                io.casehub.neocortex.mindmap.MindMapQuery.of(tenantId, 200));

        return cognitiveNodes.stream()
                .filter(n -> n.property("agent-id").isPresent())
                .collect(Collectors.groupingBy(n -> n.property("agent-id").orElseThrow()));
    }

    private Set<String> resolveTopics(CaseMemoryStore memoryStore, MindMapStore mindMapStore,
                                       String agentId, String tenantId,
                                       RecurringThoughtConfig config) {
        var memories = memoryStore.scan(new MemoryScanRequest(
                tenantId, ExperienceEvents.DOMAIN.name(), null, null, 200, null));

        Set<String> topicNames = new HashSet<>();
        for (var memory : memories) {
            var subject = memory.attributes().get(ExperienceAttributeKeys.SUBJECT);
            if (subject != null && !subject.isBlank()) {
                topicNames.add(subject);
            }
        }

        Set<String> nodeIds = new HashSet<>();
        for (String name : topicNames) {
            var found = mindMapStore.search(
                    io.casehub.neocortex.mindmap.MindMapQuery.of(tenantId, 5).withText(name));
            if (!found.isEmpty()) {
                nodeIds.add(found.getFirst().id());
            }
        }

        return nodeIds;
    }

    private RecurringThoughtConfig resolveConfig(CognitiveDefaultsRegistry registry,
                                                  String agentId,
                                                  RecurringThoughtConfig defaultConfig) {
        if (registry == null) return defaultConfig;
        var defaults = registry.forAgentOrDefaults(agentId);
        return defaults.recurringThoughtConfig() != null ? defaults.recurringThoughtConfig() : defaultConfig;
    }

    private void emitSignal(String agentId, String tenantId, RecurringThoughtAssessment assessment) {
        double significance = switch (assessment.position()) {
            case FIXATION -> 0.4;
            case OBSESSION -> 0.7;
            case RUMINATION -> 1.0;
            case HEALTHY -> 0.0;
        };

        pendingSignals.add(new AttentionSignal(
                agentId, tenantId, SignalCategory.THOUGHT_RECURRENCE,
                assessment.topicNodeId(), null, significance,
                assessment.position().name() + " on topic " + assessment.topicNodeId()));
    }

    private void emitArtifact(String agentId, String tenantId, RecurringThoughtAssessment assessment) {
        pendingArtifacts.add(new ConsolidationArtifact.RecurringThoughtDetected(
                tenantId, agentId, assessment, Instant.now()));
    }
}
