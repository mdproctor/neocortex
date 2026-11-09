package io.casehub.neocortex.mindmap.intelligence;

import io.casehub.neocortex.mindmap.MindMapEdge;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.MindMapSubgraph;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import jakarta.enterprise.context.Dependent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects information gaps in the MindMap knowledge graph using
 * Loewenstein's (1994) information-gap theory. Curiosity peaks at
 * partial knowledge — moderate confidence with connections to known
 * concepts — and is absent at the extremes.
 * <p>
 * Gap intensity follows Golman &amp; Loewenstein (2016): entropy × attention weight,
 * where attention weight derives from edge connectivity to the broader graph.
 */
@Dependent
public class InformationGapDetector {

    // Below this + no edges = metacognitive unawareness (Loewenstein 1994). Calibrated empirically.
    static final double LOW_CONFIDENCE_FLOOR = 0.1;
    // Above this, gap intensity too low to register. Calibrated empirically.
    static final double HIGH_CONFIDENCE_CEILING = 0.85;
    // Brown & McNeill (1966) tip-of-tongue threshold. Calibrated empirically.
    static final double TIP_OF_TONGUE_CONFIDENCE = 0.6;
    // Minimum connectivity for tip-of-tongue classification. Calibrated empirically.
    static final int TIP_OF_TONGUE_MIN_EDGES = 4;
    // BFS depth limit — graduated decay 1.0/0.7/0.4. Calibrated empirically.
    static final int MAX_GOAL_DEPTH = 3;
    // Minimum intensity filter. Calibrated empirically.
    private static final double MIN_GAP_INTENSITY = 0.1;

    private final MindMapStore store;

    public InformationGapDetector(MindMapStore store) {
        this.store = store;
    }

    /**
     * Scan the knowledge graph for information gaps, sorted by intensity descending.
     *
     * @param tenantId         tenant scope
     * @param activeGoalNodeIds node IDs of active goals (edges to these boost gap score), or null
     * @return gap scores sorted by intensity descending, filtered to >= 0.1
     */
    public List<InformationGapScore> detectGaps(String tenantId,
                                                 @Nullable Set<String> activeGoalNodeIds) {
        var subgraphs = store.listSubgraphs(tenantId);
        if (subgraphs.isEmpty()) return List.of();

        var gaps = new ArrayList<InformationGapScore>();

        for (MindMapSubgraph sg : subgraphs) {
            if (SubgraphTypes.TYPE_SYSTEM.equals(sg.type())) continue;

            var nodes = store.search(MindMapQuery.of(tenantId, 200)
                    .withSubgraphId(sg.id()));

            for (MindMapNode node : nodes) {
                var score = scoreNode(node, tenantId, activeGoalNodeIds);
                if (score != null && score.gapIntensity() >= MIN_GAP_INTENSITY) {
                    gaps.add(score);
                }
            }
        }

        gaps.sort(Comparator.comparingDouble(InformationGapScore::gapIntensity).reversed());
        return List.copyOf(gaps);
    }

    @Nullable
    private InformationGapScore scoreNode(MindMapNode node, String tenantId,
                                           @Nullable Set<String> goalNodeIds) {
        double confidence = node.confidence() != null ? node.confidence().value() : 0.5;
        if (confidence >= HIGH_CONFIDENCE_CEILING) return null;

        List<MindMapEdge> edges = store.neighbors(node.id(), tenantId);
        int edgeCount = edges.size();

        // Loewenstein: no curiosity when you don't know what you don't know
        if (confidence < LOW_CONFIDENCE_FLOOR && edgeCount == 0) return null;

        double entropy = computeEntropy(confidence);
        double attentionWeight = Math.log1p(edgeCount) / Math.log1p(10);
        double gapIntensity = Math.min(1.0, entropy * Math.min(attentionWeight, 1.0));

        InformationGapScore.GapType gapType = classifyGap(confidence, edgeCount);
        double goalRelevance = computeGoalRelevance(node, tenantId, goalNodeIds);

        if (goalRelevance > 0.0) {
            gapIntensity = Math.min(1.0, gapIntensity * (1 + goalRelevance));
        }

        return new InformationGapScore(
                node.id(), node.name(),
                node.subgraphType() != null ? node.subgraphType() : "unknown",
                gapIntensity, entropy, edgeCount, confidence, gapType, goalRelevance);
    }

    /**
     * Binary entropy H(p) = -p*log2(p) - (1-p)*log2(1-p).
     * Maximum at p=0.5, zero at p=0 and p=1.
     */
    public static double computeEntropy(double confidence) {
        if (confidence <= 0.0 || confidence >= 1.0) return 0.0;
        return -(confidence * log2(confidence) + (1 - confidence) * log2(1 - confidence));
    }

    private InformationGapScore.GapType classifyGap(double confidence, int edgeCount) {
        if (confidence >= TIP_OF_TONGUE_CONFIDENCE && edgeCount >= TIP_OF_TONGUE_MIN_EDGES) {
            return InformationGapScore.GapType.TIP_OF_TONGUE;
        }
        if (confidence < LOW_CONFIDENCE_FLOOR && edgeCount > 0) {
            return InformationGapScore.GapType.CONNECTED_UNKNOWN;
        }
        return InformationGapScore.GapType.PARTIAL_KNOWLEDGE;
    }

    private double computeGoalRelevance(MindMapNode node, String tenantId,
                                         @Nullable Set<String> goalNodeIds) {
        if (goalNodeIds == null || goalNodeIds.isEmpty()) return 0.0;
        if (goalNodeIds.contains(node.id())) return 1.0;

        Set<String> visited = new HashSet<>();
        visited.add(node.id());
        Set<String> currentLevel = Set.of(node.id());

        for (int depth = 1; depth <= MAX_GOAL_DEPTH; depth++) {
            Set<String> nextLevel = new HashSet<>();
            for (String nodeId : currentLevel) {
                List<MindMapEdge> edges = store.neighbors(nodeId, tenantId);
                for (MindMapEdge edge : edges) {
                    String neighbor = edge.sourceNodeId().equals(nodeId)
                            ? edge.targetNodeId() : edge.sourceNodeId();
                    if (goalNodeIds.contains(neighbor)) {
                        return switch (depth) {
                            case 1 -> 1.0;
                            case 2 -> 0.7;
                            case 3 -> 0.4;
                            default -> 0.0;
                        };
                    }
                    if (visited.add(neighbor)) nextLevel.add(neighbor);
                }
            }
            currentLevel = nextLevel;
            if (currentLevel.isEmpty()) break;
        }
        return 0.0;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
