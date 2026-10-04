package io.casehub.neocortex.mindmap.intelligence.consolidation;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryScanRequest;
import io.casehub.neocortex.memory.experience.ExperienceAttributeKeys;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.casehub.neocortex.memory.experience.GraduationClassifier;
import io.casehub.neocortex.memory.experience.GraduationContext;
import io.casehub.neocortex.memory.experience.GraduationResult;
import io.casehub.neocortex.memory.experience.GraduationScorer;
import io.casehub.neocortex.mindmap.AttentionSignal;
import io.casehub.neocortex.mindmap.MindMapConfidenceDefaults;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.MindMapSubgraph;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.NodeUpdate;
import io.casehub.neocortex.mindmap.SignalCategory;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.intelligence.SubgraphUtils;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
@Priority(15)
public class ExperienceConsolidationPhase implements ConsolidationPhase {

    private static final Logger LOG = Logger.getLogger(
        ExperienceConsolidationPhase.class.getName());
    private static final String SENTINEL_NAME = "_consolidation-state";
    private static final String CURSOR_PROPERTY = "graduation-cursor";

    private final CaseMemoryStore memoryStore;
    private final MindMapStore mindMapStore;
    private final List<AttentionSignal> pendingSignals = new ArrayList<>();
    private final GraduationScorer scorer;
    private final GraduationClassifier classifier;
    private final double threshold;
    private final int maxPerPass;
    private final int minCorroboration;
    private final TextSimilarityCorroborator textSimilarity;


    @Inject
    public ExperienceConsolidationPhase(
            CaseMemoryStore memoryStore,
            MindMapStore mindMapStore,
            Instance<GraduationScorer> scorer,
            Instance<GraduationClassifier> classifier,
            Instance<ExperienceConsolidationConfig> config,
            Instance<EmbeddingModel> embeddingModel) {
        this.memoryStore  = memoryStore;
        this.mindMapStore = mindMapStore;
        this.scorer       = scorer.isResolvable() ? scorer.get() : new DefaultGraduationScorer();
        this.classifier   = classifier.isResolvable() ? classifier.get() : new DefaultGraduationClassifier();
        ExperienceConsolidationConfig c = config.isResolvable() ? config.get() : null;
        this.threshold        = c != null ? c.threshold() : 0.5;
        this.maxPerPass       = c != null ? c.maxPerPass() : 20;
        this.minCorroboration = c != null ? c.minCorroboration() : 3;
        boolean tsEnabled = c != null && c.textSimilarity() != null && c.textSimilarity().enabled();
        if (tsEnabled) {
            double         embThreshold = c.textSimilarity().embeddingThreshold();
            double         kwThreshold  = c.textSimilarity().keywordThreshold();
            EmbeddingModel model        = embeddingModel.isResolvable() ? embeddingModel.get() : null;
            this.textSimilarity = new TextSimilarityCorroborator(model, embThreshold, kwThreshold);
        } else {
            this.textSimilarity = null;
        }
    }

    ExperienceConsolidationPhase(CaseMemoryStore memoryStore,
                                  MindMapStore mindMapStore,
                                  GraduationScorer scorer,
                                  GraduationClassifier classifier,
                                  double threshold,
                                  int maxPerPass) {
        this(memoryStore, mindMapStore, scorer, classifier, threshold, maxPerPass, 3);
    }

    ExperienceConsolidationPhase(CaseMemoryStore memoryStore,
                                 MindMapStore mindMapStore,
                                 GraduationScorer scorer,
                                 GraduationClassifier classifier,
                                 double threshold,
                                 int maxPerPass,
                                 int minCorroboration) {
        this(memoryStore, mindMapStore, scorer, classifier, threshold, maxPerPass, minCorroboration, null);
    }

    ExperienceConsolidationPhase(CaseMemoryStore memoryStore,
                                 MindMapStore mindMapStore,
                                 GraduationScorer scorer,
                                 GraduationClassifier classifier,
                                 double threshold,
                                 int maxPerPass,
                                 int minCorroboration,
                                 TextSimilarityCorroborator textSimilarity) {
        this.memoryStore      = memoryStore;
        this.mindMapStore     = mindMapStore;
        this.scorer           = scorer;
        this.classifier       = classifier;
        this.threshold        = threshold;
        this.maxPerPass       = maxPerPass;
        this.minCorroboration = minCorroboration;
        this.textSimilarity   = textSimilarity;
    }


    @Override
    public String name() {
        return "experience-consolidation";
    }

    @Override
    public void beginTick() {
        pendingSignals.clear();
    }

    @Override
    public List<AttentionSignal> signals() {
        var result = List.copyOf(pendingSignals);
        pendingSignals.clear();
        return result;
    }

    @Override
    public void run(String tenantId, List<String> subgraphPriority) {
        String cursor = loadCursor(tenantId);

        List<Memory> experiences = memoryStore.scan(
                new MemoryScanRequest(tenantId,
                                      ExperienceEvents.DOMAIN.name(),
                                      null, null,
                                      maxPerPass,
                                      cursor));

        if (experiences.isEmpty()) {return;}

        String      subgraphId        = findOrCreateCognitiveSubgraph(tenantId);
        Set<String> existingSourceIds = loadExistingSourceMemoryIds(tenantId);
        Map<String, GraduationContext> corroborationMap =
                buildCorroborationMap(experiences, tenantId);
        String lastProcessedId = cursor;

        List<NodeInput> graduatedInputs = new ArrayList<>();

        for (Memory memory : experiences) {
            try {
                String observed = memory.attributes().get(ExperienceAttributeKeys.SUBJECT);
                GraduationContext context = observed != null
                                            ? corroborationMap.getOrDefault(observed, new GraduationContext(0, tenantId))
                                            : new GraduationContext(Integer.MAX_VALUE, tenantId);
                double score = scorer.score(memory, context);
                if (score < threshold) {
                    boolean corroborationBlocked = observed != null
                                                   && context.corroboratingCount() < minCorroboration;
                    if (!corroborationBlocked) {
                        lastProcessedId = memory.memoryId();
                    }
                    continue;
                }

                if (existingSourceIds.contains(memory.memoryId())) {
                    lastProcessedId = memory.memoryId();
                    continue;
                }

                GraduationResult result = classifier.classify(memory);

                Map<String, String> properties = new HashMap<>(result.properties());
                properties.put("source-memory-id", memory.memoryId());
                properties.put("graduation-score", String.valueOf(score));
                properties.put("event-type",
                               memory.attributes().getOrDefault(
                                       ExperienceAttributeKeys.EVENT_TYPE, "unknown"));
                properties.put("agent-id", memory.subject().id());
                properties.put("cognitiveKind", result.cognitiveKind());

                String name = memory.text().length() > 100
                              ? memory.text().substring(0, 100) + "..."
                              : memory.text();

                NodeInput nodeInput = NodeInput.of(name, subgraphId)
                                               .withConfidence(MindMapConfidenceDefaults.forOrigin(
                                                       result.confidenceOrigin(), Instant.now()))
                                               .withProvenance("experience-consolidation")
                                               .withProperties(properties);

                if (memory.pleasure() != null) {nodeInput = nodeInput.withPleasure(memory.pleasure());}
                if (memory.arousal() != null) {nodeInput = nodeInput.withArousal(memory.arousal());}
                if (memory.dominance() != null) {nodeInput = nodeInput.withDominance(memory.dominance());}

                graduatedInputs.add(nodeInput);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to graduate memory "
                                       + memory.memoryId() + " for tenant " + tenantId, e);
            }
            lastProcessedId = memory.memoryId();
        }

        if (!graduatedInputs.isEmpty()) {
            mindMapStore.addNodes(graduatedInputs, tenantId);
            for (NodeInput input : graduatedInputs) {
                pendingSignals.add(new AttentionSignal(
                    input.properties().get("agent-id"), tenantId,
                    SignalCategory.EXPERIENCE_GRADUATED,
                    null, input.name(), 0.6,
                    "experience graduated to " + input.properties().getOrDefault("cognitiveKind", "unknown")));
            }
        }

        if (lastProcessedId != null && !lastProcessedId.equals(cursor)) {
            saveCursor(tenantId, lastProcessedId);
        }
    }


    private Map<String, GraduationContext> buildCorroborationMap(
            List<Memory> experiences, String tenantId) {
        Map<String, GraduationContext> map = new HashMap<>();
        for (Memory m : experiences) {
            String observed = m.attributes().get(ExperienceAttributeKeys.SUBJECT);
            if (observed == null || map.containsKey(observed)) {continue;}
            var scan = new MemoryScanRequest(tenantId,
                                             ExperienceEvents.DOMAIN.name(),
                                             ExperienceAttributeKeys.SUBJECT, observed,
                                             minCorroboration, null);
            int count        = memoryStore.scan(scan).size();
            int textSimCount = 0;
            if (textSimilarity != null && count < minCorroboration) {
                textSimCount = textSimilarity.countSimilar(m, experiences);
            }
            map.put(observed, new GraduationContext(count, textSimCount, tenantId));
        }
        return map;
    }

    private String findOrCreateCognitiveSubgraph(String tenantId) {
        return SubgraphUtils.ensureSubgraph(mindMapStore, "Cognitive", SubgraphTypes.COGNITIVE, tenantId);
    }

    private Set<String> loadExistingSourceMemoryIds(String tenantId) {
        return mindMapStore.search(
                MindMapQuery.of(tenantId, 1000).withType(SubgraphTypes.COGNITIVE))
            .stream()
            .map(n -> n.property("source-memory-id"))
            .flatMap(Optional::stream)
            .collect(Collectors.toSet());
    }

    private String loadCursor(String tenantId) {
        return findSentinelNode(tenantId)
            .flatMap(n -> n.property(CURSOR_PROPERTY))
            .orElse(null);
    }

    private void saveCursor(String tenantId, String memoryId) {
        Optional<MindMapNode> sentinel = findSentinelNode(tenantId);
        if (sentinel.isPresent()) {
            mindMapStore.updateNode(sentinel.get().id(),
                NodeUpdate.empty().withPropertiesToSet(Map.of(CURSOR_PROPERTY, memoryId)),
                tenantId);
        } else {
            String sgId = findOrCreateTypeSystemSubgraph(tenantId);
            mindMapStore.addNode(
                NodeInput.of(SENTINEL_NAME, sgId)
                    .withProperties(Map.of(CURSOR_PROPERTY, memoryId))
                    .withProvenance("experience-consolidation"),
                tenantId);
        }
    }

    private Optional<MindMapNode> findSentinelNode(String tenantId) {
        return mindMapStore.search(
                MindMapQuery.of(tenantId, 100).withType(SubgraphTypes.TYPE_SYSTEM))
            .stream()
            .filter(n -> SENTINEL_NAME.equals(n.name()))
            .findFirst();
    }

    private String findOrCreateTypeSystemSubgraph(String tenantId) {
        return SubgraphUtils.ensureSubgraph(mindMapStore, "Type System", SubgraphTypes.TYPE_SYSTEM, tenantId);
    }
}
