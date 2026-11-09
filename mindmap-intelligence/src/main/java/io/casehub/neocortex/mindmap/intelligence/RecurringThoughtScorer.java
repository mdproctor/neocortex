package io.casehub.neocortex.mindmap.intelligence;

import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.cognitive.RecurringThoughtConfig;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryScanRequest;
import io.casehub.neocortex.memory.experience.ExperienceAttributeKeys;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.casehub.neocortex.mindmap.AttentionBriefing;
import io.casehub.neocortex.mindmap.ContinuumPosition;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.RecurringThoughtAssessment;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scores recurring thought patterns across seven symptoms to classify an agent's
 * position on the HEALTHY→FIXATION→OBSESSION→RUMINATION continuum.
 *
 * <p>Literature grounding:
 * <ul>
 *   <li>Nolen-Hoeksema (1991) — rumination as repetitive negative thinking about distress</li>
 *   <li>Ehring &amp; Watkins (2008) — transdiagnostic repetitive thought as a unified process</li>
 *   <li>Eysenck (1967) — neuroticism × negative affect interaction</li>
 *   <li>Davis &amp; Nolen-Hoeksema (1998) — perseverative processing in depression</li>
 * </ul>
 *
 * <p>Scoring thresholds and constants calibrated empirically unless otherwise noted.
 */
public class RecurringThoughtScorer {

    // Equal weighting — no symptom prioritised without empirical evidence for differential weighting
    private static final Map<String, Double> DEFAULT_WEIGHTS = Map.of(
            "thoughtRecurrence", 1.0,
            "habituationFailure", 1.0,
            "gapClosureResistance", 1.0,
            "attentionalCapture", 1.0,
            "arousalTrajectory", 1.0,
            "valence", 1.0,
            "actionTendency", 1.0
    );

    private final MindMapStore mindMapStore;
    private final CaseMemoryStore memoryStore;

    public RecurringThoughtScorer(MindMapStore mindMapStore, CaseMemoryStore memoryStore) {
        this.mindMapStore = mindMapStore;
        this.memoryStore = memoryStore;
    }

    public RecurringThoughtAssessment score(String topicNodeId, String tenantId,
                                            RecurringThoughtConfig config,
                                            @Nullable AttentionBriefing briefing,
                                            @Nullable HabituationState habituation) {
        var node = mindMapStore.getNode(topicNodeId, tenantId);
        if (node == null) {
            return healthy(topicNodeId);
        }

        List<Memory> memories = queryExperienceMemories(node.name(), tenantId, config);

        if (memories.size() < config.minObservations()) {
            return healthy(topicNodeId);
        }

        double thoughtRecurrence = scoreThoughtRecurrence(memories, config);
        double habituationFailure = scoreHabituationFailure(memories, habituation);
        double gapClosureResistance = scoreGapClosureResistance(node);
        double attentionalCapture = scoreAttentionalCapture(topicNodeId, briefing);
        double arousalTrajectory = scoreArousalTrajectory(node);
        double valenceScore = scoreValence(node);
        double actionTendency = scoreActionTendency(topicNodeId, tenantId);

        Map<String, Double> weights = config.symptomWeights().isEmpty()
                ? DEFAULT_WEIGHTS : config.symptomWeights();

        double composite = weightedAverage(weights,
                thoughtRecurrence, habituationFailure, gapClosureResistance,
                attentionalCapture, arousalTrajectory, valenceScore, actionTendency);

        ContinuumPosition position = classify(composite, config);

        return new RecurringThoughtAssessment(topicNodeId,
                thoughtRecurrence, habituationFailure, gapClosureResistance,
                attentionalCapture, arousalTrajectory, valenceScore, actionTendency,
                composite, position);
    }

    private List<Memory> queryExperienceMemories(String topicName, String tenantId,
                                                 RecurringThoughtConfig config) {
        Instant cutoff = Instant.now().minus(config.recurrenceWindow());
        var request = new MemoryScanRequest(tenantId, ExperienceEvents.DOMAIN.name(),
                                            ExperienceAttributeKeys.SUBJECT, topicName, 200, null);
        var all = memoryStore.scan(request);
        return all.stream()
                  .filter(m -> m.createdAt() != null && m.createdAt().isAfter(cutoff))
                  .toList();
    }

    double scoreThoughtRecurrence(List<Memory> memories, RecurringThoughtConfig config) {
        // Normalise to [0,1] — 10+ recurrences saturates at 1.0. Calibrated empirically.
        return Math.min(1.0, memories.size() / 10.0);
    }

    double scoreHabituationFailure(List<Memory> memories,
                                    @Nullable HabituationState habituation) {
        double crossSession = 0.0;
        // Cross-session threshold — at least 4 observations for habituation assessment. Calibrated empirically.
        if (memories.size() >= 4) {
            int half = memories.size() / 2;
            int olderCount = half;
            int recentCount = memories.size() - half;
            if (recentCount >= olderCount) {
                crossSession = 1.0;
            }
        }

        double inSession = 0.0;
        if (habituation != null) {
            var counts = habituation.observationCounts();
            var novelties = habituation.noveltyScores();
            for (var entry : counts.entrySet()) {
                // Habituation failure: >5 observations without novelty decline. Calibrated empirically.
                // Novelty threshold aligned with HabituationConfig.noveltyThreshold default.
                if (entry.getValue() > 5 && novelties.getOrDefault(entry.getKey(), 1.0) > 0.3) {
                    inSession = 1.0;
                    break;
                }
            }
        }

        return Math.max(crossSession, inSession);
    }

    double scoreGapClosureResistance(@Nullable MindMapNode node) {
        if (node == null || node.confidence() == null) return 0.0;
        double confidence = node.confidence().value();
        // Gap closure resistance: Loewenstein (1994) — partial knowledge persists when confidence below midpoint.
        return confidence < 0.5 ? 1.0 - confidence : 0.0;
    }

    double scoreAttentionalCapture(String topicNodeId, @Nullable AttentionBriefing briefing) {
        if (briefing == null) return 0.0;
        var signals = briefing.topN(100);
        if (signals.isEmpty()) return 0.0;

        long topicCount = signals.stream()
                .filter(s -> topicNodeId.equals(s.sourceNodeId()))
                .count();
        return Math.min(1.0, (double) topicCount / signals.size());
    }

    double scoreArousalTrajectory(@Nullable MindMapNode node) {
        if (node == null) return 0.0;
        Double arousal = node.arousal();
        Double pleasure = node.pleasure();
        if (arousal == null) return 0.0;

        // Rumination signal: low arousal + negative valence. Nolen-Hoeksema (1991).
        if (arousal < -0.3 && pleasure != null && pleasure < -0.3) {
            return 0.9;
        }
        return Math.max(0.0, arousal);
    }

    double scoreValence(@Nullable MindMapNode node) {
        if (node == null || node.pleasure() == null) return 0.0;
        return Math.max(0.0, -node.pleasure());
    }

    double scoreActionTendency(String topicNodeId, String tenantId) {
        var query = io.casehub.neocortex.mindmap.MindMapQuery.of(tenantId, 50)
                .withTraits(Set.of("CapsGenerated"));
        var attractors = mindMapStore.search(query);

        if (attractors.isEmpty()) return 0.0;

        boolean hasApproach = false;
        boolean hasAvoidance = false;
        double strongestAvoidance = 0.0;

        for (var attractor : attractors) {
            String category = attractor.property("dominant-input").orElse("");
            String lc = category.toLowerCase();
            if (lc.contains("approach") || lc.contains("engage") || lc.contains("explore")) {
                hasApproach = true;
            }
            if (lc.contains("avoid") || lc.contains("withdraw") || lc.contains("freeze")
                    || lc.contains("shutdown") || lc.contains("disengage")) {
                hasAvoidance = true;
                double strength = attractor.property("attractor-strength")
                        .map(Double::parseDouble).orElse(0.0);
                strongestAvoidance = Math.max(strongestAvoidance, strength);
            }
        }

        // Avoidance scoring — strong avoidance (>0.5) indicates Frijda (1986) action tendency. Calibrated empirically.
        if (hasAvoidance && !hasApproach) {
            return strongestAvoidance > 0.5 ? 1.0 : 0.75;
        }
        if (hasAvoidance && hasApproach) return 0.5;
        return 0.0;
    }

    private static double weightedAverage(Map<String, Double> weights,
                                           double... scores) {
        String[] names = {"thoughtRecurrence", "habituationFailure", "gapClosureResistance",
                "attentionalCapture", "arousalTrajectory", "valence", "actionTendency"};
        double sum = 0.0;
        double weightSum = 0.0;
        for (int i = 0; i < names.length; i++) {
            double w = weights.getOrDefault(names[i], 1.0);
            sum += scores[i] * w;
            weightSum += w;
        }
        return weightSum > 0.0 ? Math.min(1.0, sum / weightSum) : 0.0;
    }

    static ContinuumPosition classify(double composite, RecurringThoughtConfig config) {
        if (composite >= config.ruminationThreshold()) return ContinuumPosition.RUMINATION;
        if (composite >= config.obsessionThreshold()) return ContinuumPosition.OBSESSION;
        if (composite >= config.fixationThreshold()) return ContinuumPosition.FIXATION;
        return ContinuumPosition.HEALTHY;
    }

    private static RecurringThoughtAssessment healthy(String topicNodeId) {
        return new RecurringThoughtAssessment(topicNodeId,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, ContinuumPosition.HEALTHY);
    }
}
