package io.casehub.neocortex.cognition.drive;

import io.casehub.neocortex.mindmap.intelligence.CuriosityChannelComputer;
import io.casehub.neocortex.mindmap.intelligence.InformationGapDetector;
import io.casehub.neocortex.mindmap.intelligence.InformationGapScore;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Information-gap curiosity drive based on Loewenstein (1994).
 * Evaluates MindMap nodes for partial-knowledge sweet spots via
 * {@link InformationGapDetector}.
 *
 * <p>Replaces the previous retention-statistics approach with entropy-based
 * gap intensity that peaks at moderate confidence — encoding Loewenstein's
 * non-linearity directly.
 */
public class CuriosityDrive implements DriveSource {

    private static final int                       TOP_N    = 5; // Top 5 gaps considered per evaluation. Calibrated empirically.
    private final        InformationGapDetector    gapDetector;
    private final        CuriosityChannelComputer  channelComputer;
    private final        double                    deprivationBias;
    private final        Supplier<Set<String>>     activeGoalNodeIds;
    private volatile     List<InformationGapScore> lastGaps = List.of();
    private volatile     CuriosityChannel          lastChannel;

    public CuriosityDrive(InformationGapDetector gapDetector,
                          CuriosityChannelComputer channelComputer,
                          double deprivationBias,
                          Supplier<Set<String>> activeGoalNodeIds) {
        this.gapDetector       = gapDetector;
        this.channelComputer   = channelComputer;
        this.deprivationBias   = deprivationBias;
        this.activeGoalNodeIds = activeGoalNodeIds;
    }

    public CuriosityDrive(InformationGapDetector gapDetector,
                          CuriosityChannelComputer channelComputer,
                          double deprivationBias) {
        this(gapDetector, channelComputer, deprivationBias, Set::of);
    }

    @Override
    public DriveIntensity evaluate(String agentId, String tenantId) {
        var goals = activeGoalNodeIds.get();
        var gaps = gapDetector.detectGaps(tenantId, goals.isEmpty() ? null : goals);
        this.lastGaps    = gaps;
        this.lastChannel = channelComputer.compute(gaps, deprivationBias);

        if (gaps.isEmpty()) {
            return new DriveIntensity(DriveAxis.CURIOSITY, 0.0, "no knowledge gaps");
        }

        var topGaps = gaps.stream().limit(TOP_N).toList();
        double intensity = topGaps.stream()
                                  .mapToDouble(InformationGapScore::gapIntensity)
                                  .average().orElse(0.0);
        intensity = Math.clamp(intensity, 0.0, 1.0);

        String trigger = topGaps.stream()
                                .map(this::formatTrigger)
                                .reduce((a, b) -> a + ", " + b)
                                .orElse("unknown gaps");

        return new DriveIntensity(DriveAxis.CURIOSITY, intensity, trigger);
    }

    private String formatTrigger(InformationGapScore g) {
        String base = g.nodeName() + " (" + g.gapType().name().toLowerCase().replace('_', ' ') + ")";
        if (g.goalRelevance() >= 0.7) return base + " [goal-relevant]"; // High goal relevance threshold. Calibrated empirically.
        return base;
    }

    public List<InformationGapScore> lastGaps() {
        return lastGaps;
    }

    public CuriosityChannel lastChannel() {
        return lastChannel;
    }
}
