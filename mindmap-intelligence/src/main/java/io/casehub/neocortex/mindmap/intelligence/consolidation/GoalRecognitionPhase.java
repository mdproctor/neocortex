package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryScanRequest;
import io.casehub.neocortex.mindmap.AttentionSignal;
import io.casehub.neocortex.mindmap.CognitiveGoalRecognizer;
import io.casehub.neocortex.mindmap.GoalTier;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.MindMapSubgraph;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.NodeUpdate;
import io.casehub.neocortex.mindmap.RecognizedGoal;
import io.casehub.neocortex.mindmap.SignalCategory;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

@ApplicationScoped
@Priority(45)
public class GoalRecognitionPhase implements ConsolidationPhase {

    private static final Logger LOG = Logger.getLogger(GoalRecognitionPhase.class.getName());
    private static final String SENTINEL_NAME = "goal-recognition-cursor";
    private static final String CURSOR_PROPERTY = "lastProcessedMemoryId";
    private static final double JARO_WINKLER_THRESHOLD = 0.85;


    private final MindMapStore mindMapStore;
    private final CaseMemoryStore memoryStore;
    private final CognitiveGoalRecognizer recognizer;
    private final SubThoughtConsolidationPhase subThoughtPhase;
    private final List<AttentionSignal> pendingSignals = new ArrayList<>();

    @Inject
    public GoalRecognitionPhase(Instance<MindMapStore> mindMapStore,
                                 Instance<CaseMemoryStore> memoryStore,
                                 Instance<CognitiveGoalRecognizer> recognizer,
                                 Instance<SubThoughtConsolidationPhase> subThoughtPhase) {
        this.mindMapStore = mindMapStore.isResolvable() ? mindMapStore.get() : null;
        this.memoryStore = memoryStore.isResolvable() ? memoryStore.get() : null;
        this.recognizer = recognizer.isResolvable() ? recognizer.get() : null;
        this.subThoughtPhase = subThoughtPhase.isResolvable() ? subThoughtPhase.get() : null;
    }

    public GoalRecognitionPhase(MindMapStore mindMapStore,
                          CaseMemoryStore memoryStore,
                          CognitiveGoalRecognizer recognizer) {
        this.mindMapStore = mindMapStore;
        this.memoryStore = memoryStore;
        this.recognizer = recognizer;
        this.subThoughtPhase = null;
    }

    @Override
    public String name() {
        return "goal-recognition";
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
        if (mindMapStore == null || memoryStore == null || recognizer == null) {return;}

        String goalSgId = findGoalSubgraph(tenantId);
        if (goalSgId == null) {return;}

        String cursor = loadCursor(tenantId);
        List<Memory> experiences = memoryStore.scan(
                new MemoryScanRequest(tenantId, "experience", null, null, 100, cursor));
        if (experiences.isEmpty()) {return;}

        List<MindMapNode> existingGoals = mindMapStore.nodesIn(goalSgId, tenantId);

        StringBuilder combinedText = new StringBuilder();
        for (Memory mem : experiences) {
            if (mem.text() != null && !mem.text().isBlank()) {
                combinedText.append(mem.text()).append("\n");
            }
        }
        if (subThoughtPhase != null) {
            for (String entity : subThoughtPhase.unresolvedIntentions()) {
                combinedText.append("Unresolved intention regarding ").append(entity).append("\n");
            }
        }

        if (combinedText.isEmpty()) {return;}

        List<RecognizedGoal> recognized = recognizer.recognize(
                combinedText.toString(), existingGoals, tenantId);

        for (RecognizedGoal goal : recognized) {
            if (findMatch(goal.description(), existingGoals) != null) {
                LOG.fine("Skipping duplicate goal: " + goal.description());
                continue;
            }

            Map<String, String> props = new HashMap<>();
            props.put("description", goal.description());
            props.put("status", "active");
            props.put("need-tier", "TASKS");
            props.put("initial-emotion", "HOPE");
            props.put("initial-emotion-intensity", String.format("%.2f", goal.confidence()));
            if (goal.origin() != null) {props.put("origin", goal.origin());}
            if (goal.suggestedHorizon() != null) {props.put("horizon", goal.suggestedHorizon());}
            props.put("goal-tier", GoalTier.fromHorizon(goal.suggestedHorizon()).name());

            String newNodeId = mindMapStore.addNode(NodeInput.of(goal.description(), goalSgId)
                                                             .withProperties(props), tenantId);
            pendingSignals.add(new AttentionSignal(
                    null, tenantId, SignalCategory.GOAL_RECOGNIZED,
                    newNodeId, goal.description(), goal.confidence(),
                    "recognized from experience — origin: " + goal.origin()));
            LOG.fine("Created recognized goal: " + goal.description());
        }

        if (!experiences.isEmpty()) {
            saveCursor(tenantId, experiences.getLast().memoryId());
        }
    }


    private MindMapNode findMatch(String description, List<MindMapNode> existing) {
        for (MindMapNode node : existing) {
            if (description.equalsIgnoreCase(node.name())) {return node;}
            String desc = node.properties().get("description");
            if (desc != null && description.equalsIgnoreCase(desc)) {return node;}
        }
        for (MindMapNode node : existing) {
            if (JaroWinkler.similarity(description, node.name()) >= JARO_WINKLER_THRESHOLD) {return node;}
            String desc = node.properties().get("description");
            if (desc != null && JaroWinkler.similarity(description, desc) >= JARO_WINKLER_THRESHOLD) {return node;}
        }
        return null;
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
            String sgId = findTypeSystemSubgraph(tenantId);
            if (sgId == null) return;
            mindMapStore.addNode(
                NodeInput.of(SENTINEL_NAME, sgId)
                    .withProperties(Map.of(CURSOR_PROPERTY, memoryId))
                    .withProvenance("goal-recognition"),
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

    private String findTypeSystemSubgraph(String tenantId) {
        for (MindMapSubgraph sg : mindMapStore.listSubgraphs(tenantId)) {
            if (SubgraphTypes.TYPE_SYSTEM.equals(sg.type())) return sg.id();
        }
        return null;
    }

    private String findGoalSubgraph(String tenantId) {
        for (MindMapSubgraph sg : mindMapStore.listSubgraphs(tenantId)) {
            if (SubgraphTypes.GOAL.equals(sg.type())) {
                return sg.id();
            }
        }
        return null;
    }
}
